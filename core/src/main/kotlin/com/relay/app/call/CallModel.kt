package com.relay.app.call

/** Hard bounds of the `TYPE:CALL` wire format. Every one is asserted by a test. */
object CallLimits {
    const val PREFIX = "TYPE:CALL|J:"

    /**
     * Whole body (prefix + base64 JSON), in chars. WP02 measured wrapped signal frames: the largest specimen, a
     * 4,952-char plain body, produced a 14,290-byte whole frame (<= 14 KiB). 4,096 is below every measured specimen,
     * so any body this codec accepts fits the 16 KiB server envelope limit and the 14 KiB target.
     */
    const val MAX_WIRE_CHARS = 4096
    const val MAX_JSON_BYTES = 3072

    /** Bound on the INFLATED sdp. Deflate is never allowed to be an unbounded allocation primitive. */
    const val MAX_SDP_BYTES = 16 * 1024
    const val MAX_CANDIDATES = 20
    const val MAX_CANDIDATE_CHARS = 256
    const val MAX_MID_CHARS = 16
    const val MAX_MLINE_INDEX = 7
    const val MAX_REASON_CHARS = 32
    const val MAX_GEN = 1000

    const val INVITE_TTL_MS = 45_000L
    const val SIGNAL_TTL_MS = 60_000L

    /** `expires - ts` may never exceed this, whatever the sender claims. */
    const val MAX_LIFETIME_MS = 60_000L
    const val DEFAULT_SKEW_MS = 10_000L
    const val MEDIA_AUDIO = "audio"
    const val PRIMARY_DEVICE = "primary"
}

enum class CallOp(val wire: String) {
    INVITE("invite"), RINGING("ringing"), ACCEPT("accept"), OFFER("offer"), ANSWER("answer"), ICE("ice"),
    ANSWERED_ELSEWHERE("answered_elsewhere"), DECLINE("decline"), BUSY("busy"), CANCEL("cancel"), END("end"),
    TIMEOUT("timeout"), MUTE("mute");

    companion object {
        fun fromWire(s: String): CallOp? = values().firstOrNull { it.wire == s }
    }
}

/**
 * Fields bound into EVERY packet: caller, callee, sending device, call id, nonce, expiry and media mode (readiness
 * gate G4). [ts] and [expires] are absolute epoch milliseconds; [device] is the SENDING device (`primary` or 32 hex).
 */
data class CallHeader(
    val call: String,
    val ts: Long,
    val expires: Long,
    val caller: String,
    val callee: String,
    val device: String,
    val nonce: String,
    val media: String = CallLimits.MEDIA_AUDIO,
)

data class IceCandidate(val mid: String, val index: Int, val candidate: String)

/**
 * One decoded call packet. [to] is the target device on directed packets (the receiver ignores a packet whose `to` is
 * not its own device); broadcast packets (invite, cancel, timeout, answered_elsewhere) have no `to`.
 */
sealed class CallPacket {
    abstract val header: CallHeader
    abstract val op: CallOp

    data class Invite(override val header: CallHeader) : CallPacket() { override val op get() = CallOp.INVITE }
    data class Ringing(override val header: CallHeader, val to: String) : CallPacket() { override val op get() = CallOp.RINGING }
    data class Accept(override val header: CallHeader, val to: String) : CallPacket() { override val op get() = CallOp.ACCEPT }
    data class Offer(override val header: CallHeader, val to: String, val gen: Int, val sdp: String) : CallPacket() { override val op get() = CallOp.OFFER }
    data class Answer(override val header: CallHeader, val to: String, val gen: Int, val sdp: String) : CallPacket() { override val op get() = CallOp.ANSWER }
    data class Ice(override val header: CallHeader, val to: String, val gen: Int, val candidates: List<IceCandidate>) : CallPacket() { override val op get() = CallOp.ICE }

    /** [winner] is the callee device that owns the call; [reason] is `answered` or `declined`. */
    data class AnsweredElsewhere(override val header: CallHeader, val winner: String, val reason: String = "answered") : CallPacket() { override val op get() = CallOp.ANSWERED_ELSEWHERE }
    data class Decline(override val header: CallHeader, val to: String, val reason: String? = null) : CallPacket() { override val op get() = CallOp.DECLINE }
    data class Busy(override val header: CallHeader, val to: String, val reason: String? = null) : CallPacket() { override val op get() = CallOp.BUSY }
    data class Cancel(override val header: CallHeader, val reason: String? = null) : CallPacket() { override val op get() = CallOp.CANCEL }
    data class End(override val header: CallHeader, val to: String, val reason: String? = null) : CallPacket() { override val op get() = CallOp.END }
    data class Timeout(override val header: CallHeader, val reason: String? = null) : CallPacket() { override val op get() = CallOp.TIMEOUT }
    data class Mute(override val header: CallHeader, val to: String, val muted: Boolean) : CallPacket() { override val op get() = CallOp.MUTE }
}

enum class CallRejectReason {
    NOT_CALL_BODY, TOO_LARGE, BAD_ENCODING, BAD_JSON, UNKNOWN_VERSION, UNKNOWN_OP, MISSING_FIELD, UNKNOWN_FIELD, BAD_FIELD,
    UNSUPPORTED_MEDIA, EXPIRED, FUTURE_DATED, LIFETIME_TOO_LONG, WRONG_BINDING, REPLAY, SDP_TOO_LARGE, BAD_SDP,
    TOO_MANY_CANDIDATES, BAD_CANDIDATE,
}

sealed class CallParseResult {
    data class Ok(val packet: CallPacket) : CallParseResult()
    data class Rejected(val reason: CallRejectReason) : CallParseResult()
}

/**
 * What the receiver knows about the packet's transport: [senderPubkey] is the sender authenticated by the NIP-17 seal,
 * never a field of the body. [skewToleranceMs] is the accepted clock difference in both directions.
 */
data class CallReceiveContext(
    val selfPubkey: String,
    val senderPubkey: String,
    val nowMs: Long,
    val skewToleranceMs: Long = CallLimits.DEFAULT_SKEW_MS,
)
