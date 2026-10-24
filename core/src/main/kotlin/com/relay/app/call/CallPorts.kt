package com.relay.app.call

/** Time is only ever read through this port, so tests and replays are deterministic. */
interface ClockPort { fun nowMs(): Long }

/** Randomness (call ids, nonces) is only ever drawn through this port. [hex] returns `2 * bytes` lowercase hex chars. */
interface RandomPort { fun hex(bytes: Int): String }

/** Ports whose results arrive asynchronously (or from another thread in the host) implement this; [CallRuntime] attaches itself. */
interface EventSource {
    /** [sink] is [CallRuntime.dispatch]. It is safe to call from any thread only if the host serialises it (see CALL_ENGINE.md). */
    fun attach(sink: (CallEvent) -> Unit)
}

/** Delivers an encoded `TYPE:CALL` body to every socket of [toIdentity] (including this identity's own other devices when it is the sender's identity). */
interface SignalPort {
    /** [SignalMode.LIVE_AND_STORED_URGENT] additionally stores the same body as an urgent delivery (the wake path). Failures are best effort. */
    fun send(toIdentity: String, wire: String, mode: SignalMode)
}

/** `GET /v1/call/ice`. Answer with [CallEvent.IceServersReady] or [CallEvent.IceServersFailed] through the attached sink. */
interface TurnCredentialPort : EventSource {
    fun request(callId: String)
}

/**
 * The WebRTC seam: NO WebRTC type crosses it. Results come back through the attached sink as
 * [CallEvent.LocalDescription], [CallEvent.LocalCandidate], [CallEvent.PeerState] and [CallEvent.PeerFailed].
 */
interface PeerConnectionPort : EventSource {
    fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean)
    fun createOffer(callId: String, gen: Int, iceRestart: Boolean)
    fun applyRemoteOffer(callId: String, gen: Int, sdp: String)
    fun applyRemoteAnswer(callId: String, gen: Int, sdp: String)
    fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>)
    fun setMicMuted(callId: String, muted: Boolean)
    fun close(callId: String)
}

/** One-shot timers keyed by (callId, timer). Starting an already-armed key replaces it. Fire with [CallEvent.TimerFired]. */
interface TimerPort : EventSource {
    fun start(callId: String, timer: CallTimer, delayMs: Long)
    fun cancel(callId: String, timer: CallTimer)
}

/** Ringtone/ringback, call UI and the local conversation record. */
interface CallUiPort {
    fun startRing(callId: String, incoming: Boolean)
    /** Idempotent: may be called for a call that never started ringing. */
    fun stopRing(callId: String)
    fun stateChanged(state: CallState)
    fun record(event: CallEffect.RecordCallEvent)
    fun commandRejected(reason: String)
}
