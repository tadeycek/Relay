package com.relay.app.calls

import com.relay.app.call.CallEngineConfig
import com.relay.app.call.CallLimits
import com.relay.app.call.CallParseResult
import com.relay.app.call.CallRuntime
import com.relay.app.call.CallState
import com.relay.app.call.ClockPort
import com.relay.app.call.PeerConnectionPort
import com.relay.app.call.RandomPort
import com.relay.app.call.SignalPort
import com.relay.app.call.TimerPort
import com.relay.app.call.TurnCredentialPort
import com.relay.app.call.CallUiPort

/**
 * The Android host of the `:core` call engine (WP13), with **no Android type in it**, so every host
 * decision — sender filtering, which body is a call, the command surface and the "one call at a time"
 * policy — is exercised by JVM tests against the real engine and codec.
 *
 * Bindings from `core/.../CALL_ENGINE.md`:
 *  - `ClockPort` is wall-clock epoch ms; `RandomPort` must be cryptographically secure.
 *  - `Ended` is sticky: a new `StartCall` is accepted from it, and [runtime] is never "reset".
 *  - Effects are applied in order on one serial context; the caller of every method here must be that
 *    context (the Android side uses a single-thread executor, see `CallController`).
 */
class CallHostCore(
    selfPubkey: String,
    deviceId: String = CallLimits.PRIMARY_DEVICE,
    relayOnly: Boolean = false,
    clock: ClockPort,
    random: RandomPort,
    signal: SignalPort,
    turn: TurnCredentialPort,
    peer: PeerConnectionPort,
    timers: TimerPort,
    ui: CallUiPort,
    isAllowedPeer: (String) -> Boolean = { true },
) {

    private val self = selfPubkey
    private val allowedPeer = isAllowedPeer

    val runtime: CallRuntime = CallRuntime(
        config = CallEngineConfig(
            selfPubkey = selfPubkey,
            deviceId = deviceId,
            relayOnly = relayOnly,
            // Host policy: only a verified contact may ring this device, and only from a sealed envelope.
            isAllowedPeer = isAllowedPeer,
        ),
        clock = clock,
        random = random,
        signal = signal,
        turn = turn,
        peer = peer,
        timers = timers,
        ui = ui,
    )

    val state: CallState get() = runtime.state

    fun startCall(callee: String) = runtime.dispatch(com.relay.app.call.CallEvent.StartCall(callee))
    fun accept() = runtime.dispatch(com.relay.app.call.CallEvent.AcceptIncoming)
    fun decline() = runtime.dispatch(com.relay.app.call.CallEvent.DeclineIncoming)
    fun hangUp() = runtime.dispatch(com.relay.app.call.CallEvent.HangUp)
    fun setMuted(muted: Boolean) = runtime.dispatch(com.relay.app.call.CallEvent.SetMuted(muted))

    /**
     * Feeds one mailbox body. Returns null when the body is not a call body at all (so the caller can
     * fall through to the message path), the parse result otherwise.
     *
     * Sender filtering (CALL_ENGINE.md rule 6): the nonce is recorded during decode, before the engine's
     * peer check, so a packet whose authenticated sender is neither the peer nor this identity is
     * dropped here and never reaches the engine.
     */
    fun onWire(senderPubkey: String, body: String): CallParseResult? {
        if (!isCallBody(body)) return null
        if (senderPubkey != self && !allowedPeer(senderPubkey)) return null
        return runtime.onWire(senderPubkey, body)
    }

    companion object {
        fun isCallBody(body: String): Boolean =
            body.startsWith(CallLimits.PREFIX) && body.length <= CallLimits.MAX_WIRE_CHARS + CallLimits.PREFIX.length
    }
}