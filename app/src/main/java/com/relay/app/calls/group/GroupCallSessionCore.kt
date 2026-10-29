package com.relay.app.calls.group

import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallEffect
import com.relay.app.call.group.GroupParticipant
import com.relay.app.groups.GroupProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class LiveVerdict { CONFIRMED, GONE, MISMATCH, UNKNOWN }

object GroupLiveState {
    /** `GET /v1/call/rooms/<room>`: only a room bound to exactly our roster revision and hash is confirmed. */
    fun verify(status: Int, body: String, roster: GroupProtocol.State, room: String): LiveVerdict {
        if (status == 404) return LiveVerdict.GONE
        if (status != 200) return LiveVerdict.UNKNOWN
        val json = try { JSONObject(body) } catch (_: Exception) { return LiveVerdict.UNKNOWN }
        if (json.optString("room") != room) return LiveVerdict.MISMATCH
        val bound = json.optBoolean("rostered") && json.optLong("revision", -1) == roster.revision &&
            json.optString("rosterHash") == runCatching { GroupCallCodec.rosterHash(roster) }.getOrNull()
        return if (bound) LiveVerdict.CONFIRMED else LiveVerdict.MISMATCH
    }
}

enum class SessionPhase { JOINING, CONNECTED, RECONNECTING }
enum class SessionEnd { LEFT, ENDED, REVOKED, ROOM_GONE, FAILED }

interface GroupCallSessionListener {
    fun onPhase(phase: SessionPhase)
    fun onParticipants(participants: List<GroupParticipant>, deafened: Map<String, Boolean>)
    fun onWarnUnknown(pid: String)
    fun onDeafened(deafened: Boolean)
    fun onFinished(end: SessionEnd, message: String)
}

data class RosterChangeOutcome(val revoked: List<String>, val closed: Boolean, val successorNeeded: Boolean)

/**
 * The group call, minus Android: room socket frames -> core engine -> SFU session, on ONE serial command queue
 * (so engine calls, socket frames and SFU negotiation never interleave). Public methods may be called from any
 * thread; they only enqueue. The platform supplies the ports (socket, SFU API/peer, HTTP, timers).
 *
 * The microphone is opened by the SFU publish, which runs only after the room was joined AND a live-state
 * readback matched our roster ([GroupLiveState]) and the core engine confirmed it. Every end path funnels through
 * [terminate], which is idempotent and releases the peer (mic included) exactly once.
 */
class GroupCallSessionCore(
    scope: CoroutineScope,
    private val coordinator: GroupCallCoordinator,
    private val http: GroupCallHttp,
    socketPort: RoomSocketPort,
    private val timers: RoomTimers,
    private val sfu: SfuSession,
    private val roster: () -> GroupProtocol.State,
    private val self: String,
    private val device: String,
    private val creator: String?,
    private val listener: GroupCallSessionListener,
    private val random: (Int) -> ByteArray = SecureBytes.provider,
    private val onSecretRotated: (room: String, secret: String, remaining: List<String>) -> Unit = { _, _, _ -> },
) {
    private companion object { const val RESUBSCRIBE_MS = 2_000L; const val RESUBSCRIBE_TRIES = 5 }

    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var room: String? = null
    private var published = false
    private var finished = false
    private var muted = false
    private var deafened = false
    private val wanted = LinkedHashSet<String>()
    private val watchdog = PacketFlowWatchdog()
    @Volatile var lastRosterOutcome: RosterChangeOutcome? = null; private set

    private val client = RoomClient(socketPort, timers, object : RoomClientListener {
        override fun onWelcome(pid: String, fresh: Boolean) = enqueue { welcomed(fresh) }
        override fun onParticipants(participants: List<GroupParticipant>, deafened: Map<String, Boolean>) = enqueue { participantsChanged(participants, deafened) }
        override fun onReconnecting(attempt: Int) = enqueue { if (!finished) listener.onPhase(SessionPhase.RECONNECTING) }
        override fun onTerminal(reason: RoomTerminal, by: String?) = enqueue { terminal(reason) }
    })

    init {
        scope.launch {
            for (command in commands) {
                try { command() } catch (c: CancellationException) { throw c } catch (e: Throwable) {
                    runCatching { terminate(SessionEnd.FAILED, "The call hit an error and was closed.") }
                }
            }
        }
    }

    private fun enqueue(op: suspend () -> Unit) { commands.trySend(op) }

    fun join(roomId: String, secret: String) = enqueue {
        if (finished) return@enqueue
        room = roomId; listener.onPhase(SessionPhase.JOINING)
        client.join(roomId, secret)
    }

    fun leave() = enqueue { terminate(SessionEnd.LEFT, "You left the call.") }

    /** Forced local teardown (screen gone, crash path). Idempotent. */
    fun dispose() = enqueue { terminate(SessionEnd.LEFT, "") }

    fun mute(value: Boolean) = enqueue { if (!finished) execute(coordinator.mute(value)) }
    fun deafen(value: Boolean) = enqueue { if (!finished) execute(coordinator.deafen(value)) }

    /** This device's room pid once the welcome frame arrived; null before that. */
    fun selfPid(): String? = client.pid

    /** Applies the UI's per-person volume to the publisher sessions that person currently owns. */
    fun setParticipantVolume(publisherSessions: List<String>, volume: Float) = enqueue {
        if (!finished) sfu.setParticipantVolume(publisherSessions, volume.coerceIn(0f, 1f))
    }

    /** Called on a timer (every few seconds) with our sender's `packetsSent`; null when stats are unavailable. */
    fun sampleOutbound(nowMs: Long, packetsSent: Long?) = enqueue {
        if (finished || !published || packetsSent == null) return@enqueue
        if (watchdog.onSample(nowMs, packetsSent) == FlowDecision.REPUBLISH) {
            watchdog.reset(nowMs)
            sfu.rebuild()
            sfu.setMuted(muted)
        }
    }

    /** The roster changed. [frozenMembers] is the roster the room was created with (the creator's own copy). */
    fun onRosterChanged(next: GroupProtocol.State, frozenMembers: List<String>) = enqueue {
        if (finished) return@enqueue
        val isCreator = creator == self
        val removed = if (isCreator) GroupRosterChange.removed(frozenMembers, next, self).orEmpty() else emptyList()
        var revoked = emptyList<String>()
        val current = room
        if (isCreator && current != null && removed.isNotEmpty() && next.revision > coordinator.roster().revision) {
            val revocation = GroupRosterChange.revocation(removed, random)
            val answer = runCatching { http.request("POST", "/v1/call/rooms/$current/roster?device=$device", revocation.body) }.getOrNull()
            if (answer?.status == 200) {
                revoked = removed
                // The room hash is rotated in the same call; the remaining members need the new capability
                // before their next pid reconnect. The host seals it only to the members still on the roster.
                runCatching { onSecretRotated(current, revocation.secret, next.members.map { it.key }.filter { it != self }) }
            } else runCatching { http.request("POST", "/v1/call/rooms/$current/end?device=$device", null) } // cannot cut them off: end the room
        }
        val effects = coordinator.updateRoster(next)
        val closed = effects.contains(GroupCallEffect.CloseRoom)
        lastRosterOutcome = RosterChangeOutcome(revoked, closed, successorNeeded = isCreator && closed && removed.isNotEmpty())
        execute(effects, SessionEnd.ENDED, "The group changed, so the call ended.")
    }

    /** Completes once every command queued before it has run (test seam and orderly teardown). */
    suspend fun idle() {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend { done.complete(Unit) }.isSuccess) done.await()
    }

    // ---- everything below runs on the command queue ------------------------------------------------

    private suspend fun welcomed(fresh: Boolean) {
        if (finished) return
        listener.onPhase(SessionPhase.CONNECTED)
        val current = room ?: return
        if (published && !fresh) return // same pid, same SFU session: nothing to redo
        if (!published) {
            val verdict = readback(current)
            val effects = if (verdict == LiveVerdict.CONFIRMED) coordinator.confirmedRoom(current) else null
            if (effects == null) {
                coordinator.roomGone()
                return terminate(SessionEnd.ROOM_GONE, if (verdict == LiveVerdict.MISMATCH) "This call isn't bound to this group." else "That call is no longer available.")
            }
            if (!publishOrFail()) return
            execute(effects)
        } else {
            watchdog.reset(timers.now())
            sfu.rebuild()
            sfu.setMuted(muted)
        }
    }

    private suspend fun readback(current: String): LiveVerdict {
        val answer = runCatching { http.request("GET", "/v1/call/rooms/$current?device=$device", null) }.getOrNull() ?: return LiveVerdict.UNKNOWN
        return GroupLiveState.verify(answer.status, answer.body, roster(), current)
    }

    private suspend fun publishOrFail(): Boolean = try {
        sfu.publish(); published = true; watchdog.reset(timers.now()); true
    } catch (e: SfuFailure) {
        // WP17 fail-closed reasons are named, so the UI can say the truth instead of "connection failed".
        val message = when (e.code) {
            "e2ee_missing" -> "This call isn't end-to-end encrypted on this device, so your audio wasn't published."
            "e2ee_unavailable" -> "The call's encryption keys aren't ready, so your microphone stayed off."
            "e2ee_failed" -> "Your audio couldn't be encrypted, so it wasn't published."
            else -> "Couldn't connect the call audio${if (e.retryable) ". Try again" else ""}."
        }
        terminate(SessionEnd.FAILED, message); false
    }

    private suspend fun participantsChanged(participants: List<GroupParticipant>, deaf: Map<String, Boolean>) {
        if (finished) return
        listener.onParticipants(participants, deaf)
        execute(coordinator.updateParticipants(participants))
    }

    private suspend fun terminal(reason: RoomTerminal) {
        when (reason) {
            RoomTerminal.LEFT -> terminate(SessionEnd.LEFT, "You left the call.")
            RoomTerminal.ENDED -> { coordinator.leave(); terminate(SessionEnd.ENDED, "The call ended.") }
            RoomTerminal.REVOKED -> { coordinator.leave(); terminate(SessionEnd.REVOKED, "You were removed from this call.") }
            RoomTerminal.NOT_IN_ROSTER -> terminate(SessionEnd.REVOKED, "The call doesn't list you as a member of this group.")
            RoomTerminal.NOT_FOUND -> { coordinator.roomGone(); terminate(SessionEnd.ROOM_GONE, "That call is no longer available.") }
            RoomTerminal.WRONG_SECRET -> terminate(SessionEnd.FAILED, "The call's join key was refused. Ask for a new invite.")
            RoomTerminal.ROOM_FULL -> terminate(SessionEnd.FAILED, "The call is full.")
            RoomTerminal.GRACE_EXPIRED -> { coordinator.leave(); terminate(SessionEnd.FAILED, "The connection to the call was lost.") }
            RoomTerminal.PROTOCOL -> terminate(SessionEnd.FAILED, "The call server refused the request.")
        }
    }

    private suspend fun execute(effects: List<GroupCallEffect>, closeAs: SessionEnd = SessionEnd.ENDED, closeMessage: String = "The call ended.") {
        if (finished) return
        val unsubscribe = ArrayList<String>(); val subscribe = ArrayList<String>()
        for (effect in effects) when (effect) {
            is GroupCallEffect.Unsubscribe -> { wanted.remove(effect.ref.sessionId); unsubscribe += effect.ref.sessionId }
            is GroupCallEffect.Subscribe -> { wanted.add(effect.ref.sessionId); subscribe += effect.ref.sessionId }
            is GroupCallEffect.SetMicrophoneMuted -> { muted = effect.muted; sfu.setMuted(effect.muted); client.sendState(muted, deafened) }
            is GroupCallEffect.SetPlaybackDeafened -> { deafened = effect.deafened; sfu.setDeafened(effect.deafened); listener.onDeafened(effect.deafened); client.sendState(muted, deafened) }
            is GroupCallEffect.WarnUnknown -> listener.onWarnUnknown(effect.pid)
            GroupCallEffect.CloseRoom -> return terminate(closeAs, closeMessage)
            is GroupCallEffect.OpenRoom -> Unit
        }
        if (unsubscribe.isNotEmpty()) runCatching { sfu.unsubscribe(unsubscribe) }
        if (subscribe.isNotEmpty() && !trySubscribe()) scheduleResubscribe(1)
    }

    private suspend fun trySubscribe(): Boolean = try {
        sfu.subscribe(wanted.filter { it !in sfu.subscribedSessions }); true
    } catch (e: SfuFailure) { false }

    private fun scheduleResubscribe(attempt: Int) {
        if (attempt > RESUBSCRIBE_TRIES) return
        timers.after(RESUBSCRIBE_MS * attempt) { enqueue { if (!finished && !trySubscribe()) scheduleResubscribe(attempt + 1) } }
    }

    /** The one end path: idempotent; releases the socket, the SFU session and the microphone exactly once. */
    private suspend fun terminate(end: SessionEnd, message: String) {
        if (finished) return
        finished = true
        runCatching { if (end == SessionEnd.LEFT) client.leave() else client.close() }
        if (end == SessionEnd.LEFT) runCatching { coordinator.leave() }
        runCatching { sfu.dispose() }
        listener.onFinished(end, message)
        commands.close()
    }
}
