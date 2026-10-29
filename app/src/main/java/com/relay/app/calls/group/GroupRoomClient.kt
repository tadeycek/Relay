package com.relay.app.calls.group

import com.relay.app.call.group.GroupParticipant
import org.json.JSONArray
import org.json.JSONObject

sealed interface RoomFrame {
    data class Welcome(val you: String, val participants: List<GroupParticipant>, val deafened: Map<String, Boolean>) : RoomFrame
    data class Joined(val participant: GroupParticipant, val deafened: Boolean) : RoomFrame
    data class Left(val pid: String) : RoomFrame
    data class State(val pid: String, val muted: Boolean, val deafened: Boolean) : RoomFrame
    data class End(val by: String?, val reason: String?) : RoomFrame
    data class Error(val code: String?) : RoomFrame
}

object RoomFrames {
    const val MAX_FRAME_CHARS = 65_536
    const val HEARTBEAT = """{"type":"heartbeat"}"""
    const val LEAVE = """{"type":"leave"}"""
    const val END = """{"type":"end"}"""
    private val PID = Regex("[0-9a-f]{16}"); private val KEY = Regex("[0-9a-f]{64}")
    private val DEVICE = Regex("[A-Za-z0-9_-]{1,64}"); private val SESSION = Regex("[A-Za-z0-9_-]{1,256}"); private val TRACK = Regex("[A-Za-z0-9_-]{1,64}")

    fun state(muted: Boolean, deafened: Boolean): String = """{"type":"state","muted":$muted,"deafened":$deafened}"""

    /** Null for anything malformed; the socket stays usable. Hostile content never reaches the engine. */
    fun parse(text: String): RoomFrame? {
        if (text.isEmpty() || text.length > MAX_FRAME_CHARS) return null
        val o = try { JSONObject(text) } catch (_: Exception) { return null }
        return when (o.opt("type") as? String) {
            "welcome" -> {
                val you = (o.opt("you") as? String)?.takeIf(PID::matches) ?: return null
                val arr = o.optJSONArray("participants") ?: return null
                val list = ArrayList<GroupParticipant>(); val deaf = HashMap<String, Boolean>()
                for (i in 0 until minOf(arr.length(), 64)) {
                    val e = arr.optJSONObject(i) ?: continue
                    participant(e)?.let { list += it; deaf[it.pid] = e.optBoolean("deafened", false) }
                }
                RoomFrame.Welcome(you, list, deaf)
            }
            "joined" -> participant(o)?.let { RoomFrame.Joined(it, o.optBoolean("deafened", false)) }
            "left" -> (o.opt("pid") as? String)?.takeIf(PID::matches)?.let { RoomFrame.Left(it) }
            "state" -> {
                val pid = (o.opt("pid") as? String)?.takeIf(PID::matches) ?: return null
                val muted = o.opt("muted") as? Boolean ?: return null
                RoomFrame.State(pid, muted, o.opt("deafened") as? Boolean ?: false)
            }
            "end" -> RoomFrame.End((o.opt("by") as? String)?.takeIf(KEY::matches), (o.opt("reason") as? String)?.take(32))
            "error" -> RoomFrame.Error((o.opt("code") as? String)?.take(48))
            else -> null
        }
    }

    private fun participant(o: JSONObject): GroupParticipant? {
        val pid = (o.opt("pid") as? String)?.takeIf(PID::matches) ?: return null
        val pubkey = (o.opt("pubkey") as? String)?.takeIf(KEY::matches) ?: return null
        val device = (o.opt("device") as? String)?.takeIf(DEVICE::matches) ?: return null
        val session = if (o.isNull("sessionId")) null else (o.opt("sessionId") as? String)?.takeIf(SESSION::matches) ?: return null
        val track = if (o.isNull("track")) null else (o.opt("track") as? String)?.takeIf(TRACK::matches) ?: return null
        if (track != null && session == null) return null
        return GroupParticipant(pid, pubkey, device, session, track, o.opt("muted") as? Boolean ?: false)
    }
}

enum class RoomTerminal { LEFT, ENDED, REVOKED, NOT_IN_ROSTER, NOT_FOUND, WRONG_SECRET, ROOM_FULL, GRACE_EXPIRED, PROTOCOL }

interface RoomSocketHandle { fun send(text: String): Boolean; fun close(code: Int, reason: String) }
interface RoomSocketListener {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(code: Int, reason: String)
    /** [httpStatus] is set when the upgrade itself was refused; [errorCode] is the server's `error.code`. */
    fun onFailure(httpStatus: Int?, errorCode: String?)
}
fun interface RoomSocketPort { fun open(room: String, secret: String, pid: String?, listener: RoomSocketListener): RoomSocketHandle }
interface RoomTimers {
    fun now(): Long
    fun after(delayMs: Long, task: () -> Unit): () -> Unit
}
interface RoomClientListener {
    fun onWelcome(pid: String, fresh: Boolean)
    fun onParticipants(participants: List<GroupParticipant>, deafened: Map<String, Boolean>)
    fun onReconnecting(attempt: Int)
    fun onTerminal(reason: RoomTerminal, by: String? = null)
}

/**
 * The room control socket (`GET /v1/call/rooms/<room>/ws`) as a state machine over [RoomSocketPort]. It owns
 * the participant snapshot, the heartbeat and the reconnect policy; WebSocket I/O and NIP-98 signing live in the
 * port. Not thread-safe: the host calls it and delivers socket callbacks on one serial context.
 *
 * Policy (PROTOCOL.md): a dropped socket keeps its pid for 30 s, so reconnects reuse the pid inside that grace;
 * after it a fresh join (no pid) is tried, which the host must treat as a new session (`fresh = true`). Close
 * 4009 (removed / revoked / replaced), a 401/403/404/409 on the upgrade, an `end` frame and our own leave are
 * terminal: they never auto-reconnect.
 */
class RoomClient(private val port: RoomSocketPort, private val timers: RoomTimers, private val listener: RoomClientListener) {
    private companion object {
        const val GRACE_MS = 30_000L
        const val GIVE_UP_MS = 90_000L
        const val HEARTBEAT_MS = 10_000L
    }

    private var room: String? = null
    private var secret: String? = null
    private var myPid: String? = null
    private var generation = 0
    private var deadGeneration = -1
    private var handle: RoomSocketHandle? = null
    private var terminal = false
    private var droppedAt: Long? = null
    private var attempt = 0
    private var freshJoin = false
    private var cancelHeartbeat: (() -> Unit)? = null
    private var cancelRetry: (() -> Unit)? = null
    private val participants = LinkedHashMap<String, GroupParticipant>()
    private val deafened = HashMap<String, Boolean>()

    val pid: String? get() = myPid

    fun join(room: String, secret: String) {
        this.room = room; this.secret = secret
        terminal = false; attempt = 0; droppedAt = null; freshJoin = false
        open(null)
    }

    fun sendState(muted: Boolean, deafened: Boolean) { if (!terminal) handle?.send(RoomFrames.state(muted, deafened)) }

    /** The room's capability rotated in place (G5 advance): the open socket stays, the next reconnect uses [secret]. */
    fun rotateSecret(secret: String) { if (!terminal) this.secret = secret }

    /** Tell the room we are leaving, then settle: no reconnect follows. */
    fun leave() {
        if (terminal) return
        handle?.send(RoomFrames.LEAVE)
        finish(RoomTerminal.LEFT, null, closeCode = 1000, closeReason = "left")
    }

    /** Creator only: asks the room to end for everyone; the `end` frame settles the client. */
    fun end() { if (!terminal) handle?.send(RoomFrames.END) }

    /** Local disposal (crash path / screen gone): no frames, no callbacks. */
    fun close() {
        terminal = true; generation++
        cancelTimers()
        runCatching { handle?.close(1000, "closing") }
        handle = null
    }

    private fun open(pid: String?) {
        val gen = ++generation
        cancelRetry?.invoke(); cancelRetry = null
        handle = port.open(room!!, secret!!, pid, object : RoomSocketListener {
            override fun onOpen() = Unit
            override fun onText(text: String) { if (live(gen)) frame(text) }
            override fun onClosed(code: Int, reason: String) { if (live(gen)) closed(gen, code) }
            override fun onFailure(httpStatus: Int?, errorCode: String?) { if (live(gen)) failed(gen, httpStatus, errorCode) }
        })
    }

    private fun live(gen: Int) = !terminal && gen == generation && gen != deadGeneration

    private fun frame(text: String) {
        when (val f = RoomFrames.parse(text)) {
            is RoomFrame.Welcome -> {
                myPid = f.you
                participants.clear(); deafened.clear()
                f.participants.forEach { participants[it.pid] = it }
                deafened.putAll(f.deafened)
                val fresh = freshJoin
                freshJoin = false; droppedAt = null; attempt = 0
                startHeartbeat()
                listener.onWelcome(f.you, fresh)
                publish()
            }
            is RoomFrame.Joined -> { participants[f.participant.pid] = f.participant; deafened[f.participant.pid] = f.deafened; publish() }
            is RoomFrame.Left -> { participants.remove(f.pid); deafened.remove(f.pid); publish() }
            is RoomFrame.State -> {
                participants[f.pid]?.let { participants[f.pid] = it.copy(muted = f.muted); deafened[f.pid] = f.deafened; publish() }
            }
            is RoomFrame.End -> finish(RoomTerminal.ENDED, f.by, closeCode = null, closeReason = null)
            is RoomFrame.Error, null -> Unit
        }
    }

    private fun publish() = listener.onParticipants(participants.values.toList(), deafened.toMap())

    private fun closed(gen: Int, code: Int) {
        when (code) {
            4009 -> finish(RoomTerminal.REVOKED, null, null, null)
            4003 -> finish(RoomTerminal.PROTOCOL, null, null, null)
            1000 -> finish(RoomTerminal.ENDED, null, null, null) // the room closed us normally (ended / expired)
            else -> dropped(gen)
        }
    }

    private fun failed(gen: Int, status: Int?, code: String?) {
        when {
            status == 404 -> finish(RoomTerminal.NOT_FOUND, null, null, null)
            status == 403 && code == "not_in_roster" -> finish(RoomTerminal.NOT_IN_ROSTER, null, null, null)
            status == 403 -> finish(RoomTerminal.WRONG_SECRET, null, null, null)
            status == 409 && code == "room_full" -> finish(RoomTerminal.ROOM_FULL, null, null, null)
            status == 401 -> finish(RoomTerminal.REVOKED, null, null, null)
            status == 400 -> finish(RoomTerminal.PROTOCOL, null, null, null)
            else -> dropped(gen) // transport error, 429, 5xx: retry inside the policy
        }
    }

    private fun dropped(gen: Int) {
        deadGeneration = gen
        cancelHeartbeat?.invoke(); cancelHeartbeat = null
        val since = droppedAt ?: timers.now().also { droppedAt = it }
        if (timers.now() - since > GIVE_UP_MS) return finish(RoomTerminal.GRACE_EXPIRED, null, null, null)
        attempt++
        listener.onReconnecting(attempt)
        val delay = minOf(500L shl minOf(attempt - 1, 3), 4_000L)
        cancelRetry = timers.after(delay) {
            if (terminal) return@after
            val elapsed = timers.now() - since
            if (elapsed > GIVE_UP_MS) return@after finish(RoomTerminal.GRACE_EXPIRED, null, null, null)
            if (elapsed > GRACE_MS || myPid == null) { freshJoin = true; open(null) } else open(myPid)
        }
    }

    private fun startHeartbeat() {
        cancelHeartbeat?.invoke()
        fun beat() { cancelHeartbeat = timers.after(HEARTBEAT_MS) { if (!terminal) { handle?.send(RoomFrames.HEARTBEAT); beat() } } }
        beat()
    }

    private fun cancelTimers() {
        cancelHeartbeat?.invoke(); cancelHeartbeat = null
        cancelRetry?.invoke(); cancelRetry = null
    }

    private fun finish(reason: RoomTerminal, by: String?, closeCode: Int?, closeReason: String?) {
        if (terminal) return
        terminal = true
        cancelTimers()
        if (closeCode != null) runCatching { handle?.close(closeCode, closeReason.orEmpty()) }
        listener.onTerminal(reason, by)
    }
}
