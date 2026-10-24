package com.relay.app.call

/**
 * Interprets [CallEffect]s against the platform ports and feeds results back as [CallEvent]s. This is the whole host
 * glue: Android (WP13) and desktop (WP14) implement the ports and construct one runtime per device.
 *
 * Threading contract: NOT thread-safe. The host must call [dispatch], [onWire] and every port callback from one
 * serial context (a single-threaded dispatcher). Re-entrant calls (a port answering synchronously inside an effect) are
 * queued and run after the current batch, so effects are always applied in the engine's order.
 */
class CallRuntime(
    config: CallEngineConfig,
    private val clock: ClockPort,
    random: RandomPort,
    private val signal: SignalPort,
    private val turn: TurnCredentialPort,
    private val peer: PeerConnectionPort,
    private val timers: TimerPort,
    private val ui: CallUiPort,
    private val replay: NonceReplayGuard = NonceReplayGuard(),
    private val skewToleranceMs: Long = CallLimits.DEFAULT_SKEW_MS,
) {
    val engine = CallEngine(config, clock, random)
    val state: CallState get() = engine.state

    private val queue = ArrayDeque<CallEvent>()
    private var draining = false
    private val self = config.selfPubkey

    init {
        turn.attach(::dispatch); peer.attach(::dispatch); timers.attach(::dispatch)
    }

    /** Feeds one event. Safe to call re-entrantly from a port callback (it is queued). Events beyond [MAX_QUEUE] are dropped. */
    fun dispatch(event: CallEvent) {
        if (queue.size >= MAX_QUEUE) return
        queue.addLast(event)
        if (draining) return
        draining = true
        try {
            while (true) {
                val e = queue.removeFirstOrNull() ?: break
                for (fx in engine.handle(e)) apply(fx)
            }
        } finally { draining = false }
    }

    /**
     * Decodes a `TYPE:CALL` body from the mailbox (live `signal` frame or stored inbox delivery) and dispatches it.
     * [senderPubkey] MUST be the sender authenticated by the NIP-17 seal. Rejected packets cause no engine activity.
     */
    fun onWire(senderPubkey: String, wire: String): CallParseResult {
        val r = CallBodyCodec.decode(wire, CallReceiveContext(self, senderPubkey, clock.nowMs(), skewToleranceMs), replay)
        if (r is CallParseResult.Ok) dispatch(CallEvent.SignalReceived(senderPubkey, r.packet))
        return r
    }

    private fun apply(fx: CallEffect) {
        when (fx) {
            is CallEffect.SendSignal -> {
                val wire = try { CallBodyCodec.encode(fx.packet) } catch (e: IllegalArgumentException) { null }
                if (wire != null) signal.send(fx.toIdentity, wire, fx.mode) else dispatch(CallEvent.PeerFailed(fx.packet.header.call))
            }
            is CallEffect.StartRing -> ui.startRing(fx.callId, fx.incoming)
            is CallEffect.StopRing -> ui.stopRing(fx.callId)
            is CallEffect.RequestIceServers -> turn.request(fx.callId)
            is CallEffect.OpenPeerConnection -> peer.open(fx.callId, fx.role, fx.iceServers, fx.relayOnly)
            is CallEffect.CreateOffer -> peer.createOffer(fx.callId, fx.gen, fx.iceRestart)
            is CallEffect.ApplyRemoteOffer -> peer.applyRemoteOffer(fx.callId, fx.gen, fx.sdp)
            is CallEffect.ApplyRemoteAnswer -> peer.applyRemoteAnswer(fx.callId, fx.gen, fx.sdp)
            is CallEffect.AddRemoteCandidates -> peer.addRemoteCandidates(fx.callId, fx.gen, fx.candidates)
            is CallEffect.SetMicMuted -> peer.setMicMuted(fx.callId, fx.muted)
            is CallEffect.ClosePeerConnection -> peer.close(fx.callId)
            is CallEffect.StartTimer -> timers.start(fx.callId, fx.timer, fx.delayMs)
            is CallEffect.CancelTimer -> timers.cancel(fx.callId, fx.timer)
            is CallEffect.RecordCallEvent -> ui.record(fx)
            is CallEffect.CommandRejected -> ui.commandRejected(fx.reason)
            is CallEffect.StateChanged -> ui.stateChanged(fx.state)
        }
    }

    private companion object { const val MAX_QUEUE = 1024 }
}
