package com.relay.app.call

/** Timers the engine asks the host to run. A fired timer is fed back as [CallEvent.TimerFired]. */
enum class CallTimer { RING_TIMEOUT, INVITE_RESEND, OFFER_WAIT, CONNECT_TIMEOUT, ICE_RESTART, ICE_FLUSH }

enum class PeerRole { OFFERER, ANSWERER }

enum class SdpKind { OFFER, ANSWER }

/** Reduced ICE connection state reported by the host's peer connection. */
enum class IceState { CONNECTED, DISCONNECTED, FAILED, CLOSED }

/** How the host must deliver a signal: [LIVE] = mailbox socket `signal` frame only; the other also stores an urgent delivery. */
enum class SignalMode { LIVE, LIVE_AND_STORED_URGENT }

data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

enum class CallOutcome { COMPLETED, MISSED, DECLINED, BUSY, CANCELLED, NO_ANSWER, ANSWERED_ELSEWHERE, FAILED, GLARE_LOST, LOST_RACE }

/** Outcomes that are written to the conversation as call events (local only). The others end silently. */
val CallOutcome.isRecorded: Boolean
    get() = this != CallOutcome.ANSWERED_ELSEWHERE && this != CallOutcome.GLARE_LOST && this != CallOutcome.LOST_RACE

data class CallInfo(val id: String, val peer: String, val incoming: Boolean)

sealed class CallState {
    object Idle : CallState() { override fun toString() = "Idle" }
    data class RingingOut(val call: CallInfo, val calleeRinging: Boolean) : CallState()
    data class RingingIn(val call: CallInfo, val callerDevice: String) : CallState()
    data class Connecting(val call: CallInfo, val peerDevice: String) : CallState()
    data class Active(val call: CallInfo, val peerDevice: String, val restarting: Boolean, val remoteMuted: Boolean, val connectedAtMs: Long) : CallState()
    data class Ended(val call: CallInfo, val outcome: CallOutcome, val reason: String?) : CallState()
}

/** Everything the host can tell the engine. */
sealed class CallEvent {
    // user commands
    data class StartCall(val callee: String) : CallEvent()
    object AcceptIncoming : CallEvent() { override fun toString() = "AcceptIncoming" }
    object DeclineIncoming : CallEvent() { override fun toString() = "DeclineIncoming" }
    object HangUp : CallEvent() { override fun toString() = "HangUp" }
    data class SetMuted(val muted: Boolean) : CallEvent()

    /** A packet that already passed [CallBodyCodec.decode]; [sender] is the authenticated identity of the NIP-17 seal. */
    data class SignalReceived(val sender: String, val packet: CallPacket) : CallEvent()

    data class TimerFired(val callId: String, val timer: CallTimer) : CallEvent()
    data class IceServersReady(val callId: String, val servers: List<IceServer>) : CallEvent()
    data class IceServersFailed(val callId: String) : CallEvent()

    // peer connection reports
    data class LocalDescription(val callId: String, val gen: Int, val kind: SdpKind, val sdp: String) : CallEvent()
    data class LocalCandidate(val callId: String, val gen: Int, val candidate: IceCandidate) : CallEvent()
    data class PeerState(val callId: String, val state: IceState) : CallEvent()
    data class PeerFailed(val callId: String) : CallEvent()
}

/** Everything the engine asks the host to do. A batch is applied in order; [StateChanged] is always the last effect of a batch. */
sealed class CallEffect {
    data class SendSignal(val toIdentity: String, val packet: CallPacket, val mode: SignalMode) : CallEffect()
    data class StartRing(val callId: String, val incoming: Boolean) : CallEffect()
    data class StopRing(val callId: String) : CallEffect()
    data class RequestIceServers(val callId: String) : CallEffect()
    data class OpenPeerConnection(val callId: String, val role: PeerRole, val iceServers: List<IceServer>, val relayOnly: Boolean) : CallEffect()
    data class CreateOffer(val callId: String, val gen: Int, val iceRestart: Boolean) : CallEffect()

    /** Host: set the remote offer (rolling back any local pending offer), create and set the answer, report [CallEvent.LocalDescription]. */
    data class ApplyRemoteOffer(val callId: String, val gen: Int, val sdp: String) : CallEffect()
    data class ApplyRemoteAnswer(val callId: String, val gen: Int, val sdp: String) : CallEffect()
    data class AddRemoteCandidates(val callId: String, val gen: Int, val candidates: List<IceCandidate>) : CallEffect()
    data class SetMicMuted(val callId: String, val muted: Boolean) : CallEffect()
    data class ClosePeerConnection(val callId: String) : CallEffect()
    data class StartTimer(val callId: String, val timer: CallTimer, val delayMs: Long) : CallEffect()
    data class CancelTimer(val callId: String, val timer: CallTimer) : CallEffect()
    data class RecordCallEvent(val callId: String, val peer: String, val incoming: Boolean, val outcome: CallOutcome, val reason: String?, val durationMs: Long) : CallEffect()
    data class CommandRejected(val reason: String) : CallEffect()
    data class StateChanged(val state: CallState) : CallEffect()
}

data class CallEngineConfig(
    val selfPubkey: String,
    /** This device's id: `primary` or 32 lowercase hex. */
    val deviceId: String,
    val relayOnly: Boolean = false,
    val ringTimeoutMs: Long = CallLimits.INVITE_TTL_MS,
    val inviteResendMs: Long = 5_000,
    val offerWaitMs: Long = 5_000,
    val connectTimeoutMs: Long = 15_000,
    val iceRestartMs: Long = 20_000,
    val iceFlushMs: Long = 250,
    /** Host policy: only verified contacts may ring this device. */
    val isAllowedPeer: (String) -> Boolean = { true },
)

/** Terminal reasons carried in `reason` fields and [CallEffect.RecordCallEvent]. */
object CallReasons {
    const val HANGUP = "hangup"
    const val BUSY = "busy"
    const val GLARE = "glare"
    const val DECLINED = "declined"
    const val NO_ANSWER = "no_answer"
    const val NO_OFFER = "no_offer"
    const val CONNECT_TIMEOUT = "connect_timeout"
    const val RESTART_TIMEOUT = "restart_timeout"
    const val ICE_FAILED = "ice_failed"
    const val TURN_FAILED = "turn_failed"
    const val PEER_ERROR = "peer_error"
    const val BUSY_SELF = "busy_local"
    const val INVALID_CALLEE = "invalid_callee"
    const val ID_COLLISION = "id_collision"
}
