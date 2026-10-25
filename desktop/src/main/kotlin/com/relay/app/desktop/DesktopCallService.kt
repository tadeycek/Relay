package com.relay.app.desktop

import com.relay.app.call.*
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** What the call service needs from the mailbox transport (WP14). Implemented by [DesktopMailboxTransport]. */
interface DesktopCallLink {
    var callRouter: DesktopCallRouter?
    fun isOnline(): Boolean
    /** One call body as a live `signal` frame. */
    suspend fun signal(to: String, payload: RelayPayload): SendResult
    /** The stored, `urgent: true` copy of the first invite. */
    suspend fun deliverUrgent(to: String, payload: RelayPayload): SendResult
    /** Authenticated `GET /v1/call/ice`; the handle cancels the request and silences a late reply. */
    fun fetchIce(reply: (status: Int, body: String?) -> Unit): AutoCloseable
}

/** A call-log row. Written only by the local call host into its own table; a remote message can never create one. */
data class DesktopCallRecord(
    val callId: String,
    val peer: String,
    val incoming: Boolean,
    val outcome: CallOutcome,
    val reason: String?,
    val durationMs: Long,
    val atMs: Long,
)

object DesktopCallLog {
    private val CALL_ID = Regex("^[0-9a-f]{32}$")
    private val REASON = Regex("^[a-z0-9_]{1,32}$")

    fun valid(r: DesktopCallRecord) = CALL_ID.matches(r.callId) && (r.reason == null || REASON.matches(r.reason)) && r.durationMs >= 0

    fun duration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun label(r: DesktopCallRecord, contactName: String): String = when (r.outcome) {
        CallOutcome.MISSED -> "Missed call"
        CallOutcome.DECLINED -> if (r.incoming) "You declined the call" else "$contactName declined your call"
        CallOutcome.BUSY -> if (r.incoming) "You were on another call" else "$contactName was busy"
        CallOutcome.NO_ANSWER -> "No answer"
        CallOutcome.CANCELLED -> "Call cancelled"
        CallOutcome.FAILED -> "Call failed"
        CallOutcome.COMPLETED -> if (r.durationMs > 0) "Call ended · ${duration(r.durationMs)}" else "Call ended"
        CallOutcome.ANSWERED_ELSEWHERE -> "Answered on another device"
        CallOutcome.GLARE_LOST, CallOutcome.LOST_RACE -> "Call ended"
    }
}

/** Only the own identity or a QR-verified, non-blocked, non-deleted contact may reach the call engine. */
object DesktopCallEligibility {
    fun isEligible(c: DesktopContact): Boolean =
        c.nostrPubkey != null && c.qrVerified && c.deletedAt == null && !c.trustLevel.equals("BLOCKED", ignoreCase = true)

    fun senderFilter(own: String, contacts: () -> List<DesktopContact>): (String) -> Boolean = { key ->
        key == own || contacts().any { it.nostrPubkey == key && isEligible(it) }
    }
}

/** Honest refusals for the Call button. Null = allowed. */
object DesktopCallGuard {
    fun startBlock(linked: Boolean, hasMailbox: Boolean, status: TransportStatus, eligible: Boolean, group: Boolean): String? = when {
        group -> "Group calls aren't available. Open a direct chat to call."
        !linked -> "Link this computer to your phone before calling."
        !hasMailbox -> "Calls need a mailbox, and none is configured."
        status != TransportStatus.ONLINE -> "You're offline. Calls need a connection to the mailbox."
        !eligible -> "Only contacts you verified by QR code can be called."
        else -> null
    }
}

/** Validated `GET /v1/call/ice` answer. Bounded and scheme-checked, like the phone's parser. */
sealed interface DesktopIceResponse {
    data class Ok(val servers: List<IceServer>, val expiresAtMs: Long) : DesktopIceResponse {
        override fun toString() = "Ok(servers=${servers.size}, expiresAtMs=$expiresAtMs)" // never prints credentials
    }
    data class Failed(val code: String) : DesktopIceResponse

    companion object {
        private const val MAX_SERVERS = 8
        private const val MAX_URLS = 8
        private const val MAX_CREDENTIAL = 512
        private const val MAX_URL = 512
        private val SCHEME = Regex("^(stun|turn|turns):", RegexOption.IGNORE_CASE)

        fun parse(status: Int, body: String?): DesktopIceResponse {
            if (status != 200 || body == null) return Failed("http_$status")
            val json = try { JSONObject(body) } catch (_: Exception) { return Failed("bad_response") }
            val array = json.optJSONArray("iceServers") ?: return Failed("bad_response")
            val servers = ArrayList<IceServer>()
            for (i in 0 until minOf(array.length(), MAX_SERVERS)) {
                val entry = array.optJSONObject(i) ?: continue
                val urls = entry.optJSONArray("urls") ?: continue
                val clean = (0 until urls.length()).mapNotNull { urls.optString(it).takeIf { u -> u.isNotBlank() && u.length <= MAX_URL } }
                    .filter { SCHEME.containsMatchIn(it) }.take(MAX_URLS)
                if (clean.isEmpty()) continue
                val rawUser = entry.optString("username")
                val rawCred = entry.optString("credential")
                // A server whose credential is oversized is dropped whole: half a credential is a failed relay later.
                if (rawUser.length > MAX_CREDENTIAL || rawCred.length > MAX_CREDENTIAL) continue
                val username = rawUser.takeIf { it.isNotEmpty() }
                val credential = rawCred.takeIf { it.isNotEmpty() }
                servers += IceServer(clean, username, credential)
            }
            if (servers.isEmpty()) return Failed("no_ice_servers")
            if (servers.none { s -> s.urls.any { it.startsWith("turn", ignoreCase = true) } && s.username != null && s.credential != null }) return Failed("no_turn_server")
            val expires = json.optLong("expiresAt", 0L).takeIf { it > 0 } ?: return Failed("no_expiry")
            return Ok(servers, expires)
        }
    }
}

enum class DesktopCallStage { IDLE, RINGING_OUT, RINGING_IN, CONNECTING, ACTIVE, ENDED }

/** Everything the call UI renders. [connectedAtMs] drives the in-call timer. */
data class DesktopCallUi(
    val stage: DesktopCallStage = DesktopCallStage.IDLE,
    val callId: String? = null,
    val peer: String? = null,
    val peerName: String? = null,
    val connectedAtMs: Long? = null,
    val muted: Boolean = false,
    val status: String = "",
    val notice: String? = null,
) {
    val live: Boolean get() = stage != DesktopCallStage.IDLE && stage != DesktopCallStage.ENDED
}

object DesktopCallStatus {
    fun text(state: CallState): String = when (state) {
        CallState.Idle -> ""
        is CallState.RingingOut -> if (state.calleeRinging) "Ringing…" else "Calling…"
        is CallState.RingingIn -> "Incoming call"
        is CallState.Connecting -> "Connecting…"
        is CallState.Active -> if (state.restarting) "Reconnecting…" else if (state.remoteMuted) "Connected · they are muted" else "Connected"
        is CallState.Ended -> when (state.outcome) {
            CallOutcome.COMPLETED -> "Call ended"
            CallOutcome.MISSED -> "Missed call"
            CallOutcome.DECLINED -> "Call declined"
            CallOutcome.BUSY -> "Busy"
            CallOutcome.NO_ANSWER -> "No answer"
            CallOutcome.CANCELLED -> "Call cancelled"
            CallOutcome.FAILED -> "Call failed"
            CallOutcome.ANSWERED_ELSEWHERE -> "Answered on another device"
            CallOutcome.GLARE_LOST, CallOutcome.LOST_RACE -> "Call ended"
        }
    }
}

/**
 * The assembled desktop call host (WP14): [DesktopCallHost] + signal queue + TURN port + timers + peer, wired to the
 * mailbox through [DesktopCallLink] and to the UI through [state]. It lives as long as the engine, NOT the window.
 *
 * The sender filter runs inside [DesktopCallHost.onWire], BEFORE the engine decodes (and records the nonce of) a packet.
 */
class DesktopCallService(
    selfPubkey: String,
    deviceId: String,
    private val link: DesktopCallLink,
    isAllowedPeer: (String) -> Boolean,
    private val record: (DesktopCallRecord) -> Unit,
    private val nameOf: (String) -> String,
    private val clock: ClockPort = DesktopCallEpochClock,
    random: RandomPort = DesktopCallSecureRandom(),
    relayOnly: Boolean = false,
    makePeer: ((CallEvent) -> Unit) -> PeerConnectionPort = { post -> DesktopCallPeer(post) },
    private val lingerMs: Long = 3_000,
    private val flushMs: Long = 1_500,
    /** Bound on the stored (urgent) leg: it must not hold the per-recipient queue, and a queued hang-up, past the close flush. */
    private val urgentTimeoutMs: Long = 1_000,
    private val noticeMs: Long = 6_000,
    var onChange: () -> Unit = {},
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostRef = AtomicReference<DesktopCallHost?>()
    private val inflight = AtomicInteger()
    private val closed = AtomicBoolean(false)
    private val rand = random
    private val _state = MutableStateFlow(DesktopCallUi())
    val state: StateFlow<DesktopCallUi> = _state.asStateFlow()

    private val post: (CallEvent) -> Unit = { hostRef.get()?.post(it) }
    private val queue = DesktopCallSignalQueue(
        schedule = { it() },
        deliver = { to, wire, mode, done -> deliver(to, wire, mode, done) },
        failed = { _ -> _state.update { if (it.live) it.copy(notice = "Couldn't reach the mailbox. The other side may not get the call.") else it } },
    )
    private val host: DesktopCallHost

    init {
        host = DesktopCallHost(
            selfPubkey, deviceId, clock, random, queue,
            DesktopCallTurnPort(clock::nowMs, { _, reply ->
                link.fetchIce { status, body ->
                    when (val r = DesktopIceResponse.parse(status, body)) {
                        is DesktopIceResponse.Ok -> reply(r.servers, r.expiresAtMs)
                        is DesktopIceResponse.Failed -> reply(null, null)
                    }
                }
            }, post),
            makePeer(post),
            DesktopCallTimers(post = { event, valid -> hostRef.get()?.post(event, valid) }),
            UiPort(), isAllowedPeer, relayOnly,
        )
        hostRef.set(host)
        link.callRouter = DesktopCallRouter { sender, body, _ -> host.onWire(sender, body); true }
    }

    private fun deliver(to: String, wire: String, mode: SignalMode, done: (Boolean) -> Unit) {
        inflight.incrementAndGet()
        scope.launch {
            var ok = false
            try {
                val ts = clock.nowMs()
                ok = link.signal(to, RelayPayload("c" + rand.hex(12), ts, wire)) is SendResult.Sent
                if (mode == SignalMode.LIVE_AND_STORED_URGENT) {
                    val stored = kotlinx.coroutines.withTimeoutOrNull(urgentTimeoutMs) {
                        link.deliverUrgent(to, RelayPayload("u" + rand.hex(12), ts, wire)) is SendResult.Sent
                    } ?: false
                    ok = ok || stored
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                ok = false
            } finally {
                // done() first: it starts the next queued item (a hang-up) synchronously, which raises inflight before
                // this one is released, so close() can never observe 0 while a queued signal is still pending.
                try { done(ok) } finally { inflight.decrementAndGet() }
            }
        }
    }

    // ---- commands (blocking until the serial host has applied them; call off the UI thread) -------

    /** Null = started; otherwise the honest reason it was refused. */
    fun start(peer: String): String? {
        if (closed.get()) return "Calls are shutting down."
        if (_state.value.live) return "You're already on a call."
        _state.update { DesktopCallUi(muted = false) }
        host.startCall(peer)
        return null
    }
    fun accept() = host.accept()
    fun decline() = host.decline()
    fun hangUp() = host.hangUp()
    fun setMuted(muted: Boolean) {
        // The engine only mutes an Active call; marking it muted earlier would show "Unmute" with a live mic.
        if (host.state !is CallState.Active) return
        host.setMuted(muted)
        _state.update { if (it.stage == DesktopCallStage.ACTIVE) it.copy(muted = muted) else it }
    }

    // ---- engine callbacks (on the serial host thread) -------------------------------------------

    private inner class UiPort : CallUiPort {
        override fun startRing(callId: String, incoming: Boolean) = Unit // sound is optional (WP14 step 2); the ring window is the signal
        override fun stopRing(callId: String) = Unit
        override fun record(event: CallEffect.RecordCallEvent) {
            runCatching { record(DesktopCallRecord(event.callId, event.peer, event.incoming, event.outcome, event.reason, event.durationMs, clock.nowMs())) }
            runCatching { onChange() }
        }
        override fun commandRejected(reason: String) {
            val text = when (reason) {
                CallReasons.BUSY_SELF -> "You're already on a call."
                CallReasons.INVALID_CALLEE -> "That contact can't be called."
                CallReasons.ID_COLLISION -> "Try the call again."
                else -> "The call couldn't start."
            }
            _state.update { it.copy(notice = text) }
            // With no call there is no bar to carry it forever: show it, then let it go.
            scope.launch { delay(noticeMs); _state.update { if (it.notice == text && !it.live) it.copy(notice = null) else it } }
        }
        override fun stateChanged(state: CallState) {
            val info = when (state) {
                CallState.Idle -> null
                is CallState.RingingOut -> state.call
                is CallState.RingingIn -> state.call
                is CallState.Connecting -> state.call
                is CallState.Active -> state.call
                is CallState.Ended -> state.call
            }
            if (info == null) { _state.update { DesktopCallUi() }; return }
            val stage = when (state) {
                is CallState.RingingOut -> DesktopCallStage.RINGING_OUT
                is CallState.RingingIn -> DesktopCallStage.RINGING_IN
                is CallState.Connecting -> DesktopCallStage.CONNECTING
                is CallState.Active -> DesktopCallStage.ACTIVE
                else -> DesktopCallStage.ENDED
            }
            _state.update {
                DesktopCallUi(stage, info.id, info.peer, runCatching { nameOf(info.peer) }.getOrDefault(info.peer.take(8)),
                    (state as? CallState.Active)?.connectedAtMs, it.muted && stage != DesktopCallStage.ENDED, DesktopCallStatus.text(state), it.notice)
            }
            if (state is CallState.Ended) {
                val id = info.id
                scope.launch { delay(lingerMs); _state.update { if (it.callId == id && it.stage == DesktopCallStage.ENDED) DesktopCallUi() else it } }
            }
            runCatching { onChange() }
        }
    }

    /** Hang up, flush the hang-up signal, cancel TURN/timers, close the peer. Idempotent. The caller stops the transport AFTER this. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (link.callRouter != null) link.callRouter = null
        try {
            host.close()
        } finally {
            val end = System.nanoTime() + flushMs * 1_000_000
            while (inflight.get() > 0 && System.nanoTime() < end) Thread.sleep(10)
            scope.cancel()
            _state.value = DesktopCallUi()
        }
    }
}
