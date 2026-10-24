package com.relay.app.call

/**
 * The 1:1 call state machine (06 §2, LP-D09, LP-D10). PURE: no threads, no clock or random reads except through
 * [ClockPort] and [RandomPort], no I/O. The host feeds [CallEvent]s one at a time (never re-entrantly) and applies
 * the returned [CallEffect]s in order. A single instance holds at most one call, so two simultaneous calls are
 * structurally impossible. See `CALL_ENGINE.md` in this package for the full event/effect contract.
 *
 * Packets must already have passed [CallBodyCodec.decode] (size, freshness, sender-role binding, replay). The engine
 * re-checks everything the codec cannot know: that the packet belongs to THE current call (id, caller, callee), comes
 * from the right identity and device, and is addressed to this device.
 */
class CallEngine(val config: CallEngineConfig, private val clock: ClockPort, private val random: RandomPort) {
    var state: CallState = CallState.Idle
        private set

    private class Cx(
        val id: String, val peer: String, val incoming: Boolean, val caller: String, val callee: String,
        var peerDevice: String?, val inviteExpires: Long,
    ) {
        var iceRequested = false
        var peerOpened = false
        var pendingOffer: CallPacket.Offer? = null
        var gen = 0
        var pendingOfferGen: Int? = null
        var remoteDescGen = -1
        var answeredGen = -1
        /** Set once this device told its own identity it won the accept race (one self-signal per call). */
        var announcedWinner = false
        /** A callee device answered "busy"; reported as BUSY if nobody else picks up before the ring timeout. */
        var busySeen = false
        var descSentGen = -1
        val localCands = ArrayList<Pair<Int, IceCandidate>>()
        val remoteCands = ArrayList<Pair<Int, IceCandidate>>()
        val lateAccepts = LinkedHashSet<String>()
        val armed = HashSet<CallTimer>()
        val info get() = CallInfo(id, peer, incoming)
    }

    private var cx: Cx? = null
    private val recentEnded = LinkedHashSet<String>()
    private lateinit var fx: ArrayList<CallEffect>

    private val now get() = clock.nowMs()

    /** Sizes of every internal buffer, for the bounded-memory tests. */
    internal fun stats(): Map<String, Int> = mapOf(
        "localCandidates" to (cx?.localCands?.size ?: 0), "remoteCandidates" to (cx?.remoteCands?.size ?: 0),
        "recentEnded" to recentEnded.size, "armedTimers" to (cx?.armed?.size ?: 0), "lateAccepts" to (cx?.lateAccepts?.size ?: 0),
    )

    /** Whether [id] is still in the bounded memory of recently ended calls (tests only). */
    internal fun remembersEnded(id: String) = id in recentEnded

    fun handle(event: CallEvent): List<CallEffect> {
        val before = state
        fx = ArrayList()
        when (event) {
            is CallEvent.StartCall -> startCall(event.callee)
            CallEvent.AcceptIncoming -> acceptIncoming()
            CallEvent.DeclineIncoming -> declineIncoming()
            CallEvent.HangUp -> hangUp()
            is CallEvent.SetMuted -> setMuted(event.muted)
            is CallEvent.SignalReceived -> onSignal(event.sender, event.packet)
            is CallEvent.TimerFired -> onTimer(event.callId, event.timer)
            is CallEvent.IceServersReady -> onIceServers(event)
            is CallEvent.IceServersFailed -> forCall(event.callId) { c -> if (c.iceRequested && !c.peerOpened && connectingOrActive()) endWithSignal(CallOutcome.FAILED, CallReasons.TURN_FAILED) }
            is CallEvent.LocalDescription -> onLocalDescription(event)
            is CallEvent.LocalCandidate -> onLocalCandidate(event)
            is CallEvent.PeerState -> onPeerState(event)
            is CallEvent.PeerFailed -> forCall(event.callId) { if (connectingOrActive()) endWithSignal(CallOutcome.FAILED, CallReasons.PEER_ERROR) }
        }
        if (state != before) fx.add(CallEffect.StateChanged(state))
        return fx
    }

    // ------------------------------------------------------------------------------------------ helpers

    private fun connectingOrActive() = state is CallState.Connecting || state is CallState.Active
    private fun forCall(callId: String, body: (Cx) -> Unit) { val c = cx; if (c != null && c.id == callId) body(c) }

    private fun header(c: Cx) = CallHeader(c.id, now, now + CallLimits.SIGNAL_TTL_MS, c.caller, c.callee, config.deviceId, random.hex(16))

    private fun send(to: String, p: CallPacket, mode: SignalMode = SignalMode.LIVE) { fx.add(CallEffect.SendSignal(to, p, mode)) }

    private fun startTimer(c: Cx, t: CallTimer, delay: Long) { c.armed.add(t); fx.add(CallEffect.StartTimer(c.id, t, delay)) }
    private fun cancelTimer(c: Cx, t: CallTimer) { if (c.armed.remove(t)) fx.add(CallEffect.CancelTimer(c.id, t)) }

    private fun rememberEnded(id: String) {
        recentEnded.add(id)
        while (recentEnded.size > MAX_RECENT_ENDED) { val it = recentEnded.iterator(); it.next(); it.remove() }
    }

    private fun end(outcome: CallOutcome, reason: String?) {
        val c = cx ?: return
        if (state is CallState.RingingIn || state is CallState.RingingOut) fx.add(CallEffect.StopRing(c.id))
        for (t in c.armed.toList()) cancelTimer(c, t)
        if (c.peerOpened) fx.add(CallEffect.ClosePeerConnection(c.id))
        val s = state
        if (outcome.isRecorded) {
            val duration = if (s is CallState.Active) (now - s.connectedAtMs).coerceAtLeast(0) else 0L
            fx.add(CallEffect.RecordCallEvent(c.id, c.peer, c.incoming, outcome, reason, duration))
        }
        rememberEnded(c.id)
        state = CallState.Ended(c.info, outcome, reason)
        cx = null
    }

    private fun endWithSignal(outcome: CallOutcome, reason: String) {
        val c = cx ?: return
        c.peerDevice?.let { send(c.peer, CallPacket.End(header(c), it, reason)) }
        end(outcome, reason)
    }

    // ------------------------------------------------------------------------------------------ commands

    private fun startCall(callee: String) {
        if (state !is CallState.Idle && state !is CallState.Ended) { fx.add(CallEffect.CommandRejected(CallReasons.BUSY_SELF)); return }
        if (callee.length != 64 || callee == config.selfPubkey || !callee.all { it in '0'..'9' || it in 'a'..'f' }) {
            fx.add(CallEffect.CommandRejected(CallReasons.INVALID_CALLEE)); return
        }
        val expires = now + config.ringTimeoutMs.coerceAtMost(CallLimits.INVITE_TTL_MS)
        // A fresh id must never collide with a remembered one (a peer would drop it as a stale invite).
        var id = random.hex(16)
        var tries = 0
        while ((id in recentEnded || !ID_FORMAT.matches(id)) && tries++ < 4) id = random.hex(16)
        if (id in recentEnded || !ID_FORMAT.matches(id)) { fx.add(CallEffect.CommandRejected(CallReasons.ID_COLLISION)); return }
        val c = Cx(id, callee, false, config.selfPubkey, callee, null, expires)
        cx = c
        state = CallState.RingingOut(c.info, calleeRinging = false)
        send(callee, CallPacket.Invite(header(c).copy(expires = expires)), SignalMode.LIVE_AND_STORED_URGENT)
        startTimer(c, CallTimer.RING_TIMEOUT, config.ringTimeoutMs)
        startTimer(c, CallTimer.INVITE_RESEND, config.inviteResendMs)
    }

    private fun acceptIncoming() {
        val s = state as? CallState.RingingIn ?: return
        val c = cx ?: return
        fx.add(CallEffect.StopRing(c.id))
        cancelTimer(c, CallTimer.RING_TIMEOUT)
        beginCalleeConnect(c, s.callerDevice)
    }

    private fun beginCalleeConnect(c: Cx, callerDevice: String) {
        c.peerDevice = callerDevice
        state = CallState.Connecting(c.info, callerDevice)
        send(c.peer, CallPacket.Accept(header(c), callerDevice))
        startTimer(c, CallTimer.OFFER_WAIT, config.offerWaitMs)
        startTimer(c, CallTimer.CONNECT_TIMEOUT, config.connectTimeoutMs)
        c.iceRequested = true
        fx.add(CallEffect.RequestIceServers(c.id))
    }

    private fun declineIncoming() {
        val s = state as? CallState.RingingIn ?: return
        val c = cx ?: return
        send(c.peer, CallPacket.Decline(header(c), s.callerDevice, CallReasons.DECLINED))
        // Tell this identity's other devices to stop ringing (they treat it as handled elsewhere).
        send(config.selfPubkey, CallPacket.AnsweredElsewhere(header(c), config.deviceId, "declined"))
        end(CallOutcome.DECLINED, CallReasons.DECLINED)
    }

    private fun hangUp() {
        val c = cx ?: return
        when (val s = state) {
            is CallState.RingingIn -> declineIncoming()
            is CallState.RingingOut -> { send(c.peer, CallPacket.Cancel(header(c), CallReasons.HANGUP)); end(CallOutcome.CANCELLED, CallReasons.HANGUP) }
            is CallState.Connecting -> endWithSignal(CallOutcome.CANCELLED, CallReasons.HANGUP)
            is CallState.Active -> endWithSignal(CallOutcome.COMPLETED, CallReasons.HANGUP)
            else -> Unit
        }
    }

    private fun setMuted(muted: Boolean) {
        val s = state as? CallState.Active ?: return
        val c = cx ?: return
        fx.add(CallEffect.SetMicMuted(c.id, muted))
        send(c.peer, CallPacket.Mute(header(c), s.peerDevice, muted))
    }

    // ------------------------------------------------------------------------------------------ timers

    private fun onTimer(callId: String, t: CallTimer) = forCall(callId) { c ->
        if (!c.armed.remove(t)) return@forCall
        when (t) {
            CallTimer.RING_TIMEOUT -> when (state) {
                is CallState.RingingOut -> {
                    val reason = if (c.busySeen) CallReasons.BUSY else CallReasons.NO_ANSWER
                    send(c.peer, CallPacket.Timeout(header(c), reason))
                    end(if (c.busySeen) CallOutcome.BUSY else CallOutcome.NO_ANSWER, reason)
                }
                is CallState.RingingIn -> end(CallOutcome.MISSED, "timeout")
                else -> Unit
            }
            CallTimer.INVITE_RESEND -> if (state is CallState.RingingOut && now < c.inviteExpires) {
                send(c.peer, CallPacket.Invite(header(c).copy(expires = c.inviteExpires)))
                startTimer(c, CallTimer.INVITE_RESEND, config.inviteResendMs)
            }
            CallTimer.OFFER_WAIT -> if (state is CallState.Connecting && c.incoming) endWithSignal(CallOutcome.LOST_RACE, CallReasons.NO_OFFER)
            CallTimer.CONNECT_TIMEOUT -> if (state is CallState.Connecting) endWithSignal(CallOutcome.FAILED, CallReasons.CONNECT_TIMEOUT)
            CallTimer.ICE_RESTART -> if ((state as? CallState.Active)?.restarting == true) endWithSignal(CallOutcome.FAILED, CallReasons.RESTART_TIMEOUT)
            CallTimer.ICE_FLUSH -> flushLocalCandidates(c)
        }
    }

    // ------------------------------------------------------------------------------------------ signals

    private fun toOf(p: CallPacket): String? = when (p) {
        is CallPacket.Ringing -> p.to; is CallPacket.Accept -> p.to; is CallPacket.Offer -> p.to; is CallPacket.Answer -> p.to
        is CallPacket.Ice -> p.to; is CallPacket.Decline -> p.to; is CallPacket.Busy -> p.to; is CallPacket.End -> p.to
        is CallPacket.Mute -> p.to
        is CallPacket.Invite, is CallPacket.AnsweredElsewhere, is CallPacket.Cancel, is CallPacket.Timeout -> null
    }

    private fun onSignal(sender: String, p: CallPacket) {
        val h = p.header
        if (sender == config.selfPubkey && h.device == config.deviceId) return // echo of our own self-signal
        if (p is CallPacket.Invite) { onInvite(sender, p); return }
        val c = cx ?: return
        if (h.call != c.id || h.caller != c.caller || h.callee != c.callee) return
        if (sender == config.selfPubkey) {
            // The only valid self signal: a sibling device of the callee reporting the call handled.
            if (p !is CallPacket.AnsweredElsewhere || c.callee != config.selfPubkey) return
        } else {
            if (sender != c.peer) return
            val pin = c.peerDevice
            if (pin != null && p !is CallPacket.Accept && h.device != pin) return
        }
        val to = toOf(p)
        if (to != null && to != config.deviceId) return

        when (val s = state) {
            is CallState.RingingIn -> when (p) {
                is CallPacket.Cancel -> if (p.reason == CallReasons.GLARE) end(CallOutcome.GLARE_LOST, CallReasons.GLARE) else end(CallOutcome.MISSED, p.reason ?: "cancelled")
                is CallPacket.Timeout -> end(CallOutcome.MISSED, "timeout")
                is CallPacket.AnsweredElsewhere -> if (p.winner != config.deviceId) end(CallOutcome.ANSWERED_ELSEWHERE, p.reason)
                else -> Unit
            }
            is CallState.RingingOut -> when (p) {
                is CallPacket.Ringing -> if (!s.calleeRinging) { state = s.copy(calleeRinging = true); fx.add(CallEffect.StartRing(c.id, incoming = false)) }
                is CallPacket.Accept -> callerAccepted(c, h.device)
                is CallPacket.Decline -> end(CallOutcome.DECLINED, p.reason ?: CallReasons.DECLINED)
                // A busy device is not the whole identity: LP-D10 rings every device and the first to accept
                // wins, so one busy/busy-line device must not kill a call another device can still answer.
                // The call ends BUSY only when the ring timeout expires with nobody accepting.
                is CallPacket.Busy -> c.busySeen = true
                else -> Unit
            }
            is CallState.Connecting -> when (p) {
                is CallPacket.Accept -> lateAccept(c, h.device)
                is CallPacket.AnsweredElsewhere -> if (sender == c.peer && c.incoming && p.winner != config.deviceId) end(CallOutcome.LOST_RACE, "answered_elsewhere")
                is CallPacket.Offer -> onRemoteOffer(c, p)
                is CallPacket.Answer -> onRemoteAnswer(c, p)
                is CallPacket.Ice -> onRemoteIce(c, p)
                is CallPacket.End -> end(CallOutcome.FAILED, p.reason ?: "remote_end")
                is CallPacket.Cancel, is CallPacket.Timeout -> if (c.incoming) end(CallOutcome.FAILED, "caller_cancelled")
                is CallPacket.Decline -> if (!c.incoming) end(CallOutcome.DECLINED, p.reason ?: CallReasons.DECLINED)
                is CallPacket.Busy -> if (!c.incoming) end(CallOutcome.BUSY, p.reason ?: CallReasons.BUSY)
                else -> Unit
            }
            is CallState.Active -> when (p) {
                is CallPacket.Accept -> lateAccept(c, h.device)
                is CallPacket.Offer -> onRemoteOffer(c, p)
                is CallPacket.Answer -> onRemoteAnswer(c, p)
                is CallPacket.Ice -> onRemoteIce(c, p)
                is CallPacket.End -> end(if (p.reason in FAILURE_REASONS) CallOutcome.FAILED else CallOutcome.COMPLETED, p.reason ?: "remote_end")
                is CallPacket.Mute -> if (s.remoteMuted != p.muted) state = s.copy(remoteMuted = p.muted)
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun onInvite(sender: String, p: CallPacket.Invite) {
        val h = p.header
        if (sender == config.selfPubkey || h.callee != config.selfPubkey || h.caller != sender) return
        if (!config.isAllowedPeer(sender)) return
        if (h.call in recentEnded) return
        if (h.expires <= now) return
        val cur = cx
        if (cur != null) {
            if (cur.id == h.call) return
            if (state is CallState.RingingOut && sender == cur.peer) {
                if (cur.id < h.call) return // glare: the lower call id survives and the peer will yield to it
                // We lose: cancel our own invite, then take theirs as if the user had accepted it.
                send(cur.callee, CallPacket.Cancel(header(cur), CallReasons.GLARE))
                end(CallOutcome.GLARE_LOST, CallReasons.GLARE)
                val c = Cx(h.call, sender, true, sender, config.selfPubkey, h.device, h.expires)
                cx = c
                beginCalleeConnect(c, h.device)
                return
            }
            busy(sender, h)
            return
        }
        val c = Cx(h.call, sender, true, sender, config.selfPubkey, h.device, h.expires)
        cx = c
        state = CallState.RingingIn(c.info, h.device)
        fx.add(CallEffect.StartRing(c.id, incoming = true))
        send(sender, CallPacket.Ringing(header(c), h.device))
        startTimer(c, CallTimer.RING_TIMEOUT, minOf(config.ringTimeoutMs, h.expires - now).coerceAtLeast(1))
    }

    private fun busy(sender: String, inv: CallHeader) {
        val hdr = CallHeader(inv.call, now, now + CallLimits.SIGNAL_TTL_MS, inv.caller, inv.callee, config.deviceId, random.hex(16))
        send(sender, CallPacket.Busy(hdr, inv.device, CallReasons.BUSY))
        fx.add(CallEffect.RecordCallEvent(inv.call, sender, true, CallOutcome.MISSED, CallReasons.BUSY, 0))
        rememberEnded(inv.call)
    }

    // ---- caller: accept race

    private fun callerAccepted(c: Cx, device: String) {
        fx.add(CallEffect.StopRing(c.id))
        cancelTimer(c, CallTimer.RING_TIMEOUT)
        cancelTimer(c, CallTimer.INVITE_RESEND)
        c.peerDevice = device
        state = CallState.Connecting(c.info, device)
        send(c.peer, CallPacket.AnsweredElsewhere(header(c), device, "answered"))
        c.iceRequested = true
        fx.add(CallEffect.RequestIceServers(c.id))
        startTimer(c, CallTimer.CONNECT_TIMEOUT, config.connectTimeoutMs)
    }

    private fun lateAccept(c: Cx, device: String) {
        val winner = c.peerDevice ?: return
        if (c.incoming || device == winner || c.lateAccepts.size >= MAX_LATE_ACCEPTS || !c.lateAccepts.add(device)) return
        send(c.peer, CallPacket.AnsweredElsewhere(header(c), winner, "answered"))
    }

    // ---- negotiation

    private fun onIceServers(e: CallEvent.IceServersReady) = forCall(e.callId) { c ->
        if (state !is CallState.Connecting || !c.iceRequested || c.peerOpened) return@forCall
        c.peerOpened = true
        fx.add(CallEffect.OpenPeerConnection(c.id, if (c.incoming) PeerRole.ANSWERER else PeerRole.OFFERER, e.servers, config.relayOnly))
        if (!c.incoming) {
            c.pendingOfferGen = 0
            fx.add(CallEffect.CreateOffer(c.id, 0, false))
        } else c.pendingOffer?.let { c.pendingOffer = null; applyRemoteOffer(c, it) }
    }

    private fun onRemoteOffer(c: Cx, p: CallPacket.Offer) {
        val s = state
        if (s is CallState.Connecting && !c.incoming) return // caller's first offer is ours; a callee never offers first
        if (p.gen <= c.remoteDescGen) return // duplicate or stale
        val pending = c.pendingOfferGen
        if (pending != null) {
            if (p.gen < pending) return
            if (c.caller == config.selfPubkey) return // impolite peer keeps its own offer; the callee yields
            c.pendingOfferGen = null
        }
        c.gen = maxOf(c.gen, p.gen)
        if (!c.peerOpened) {
            val held = c.pendingOffer
            if (held == null || p.gen > held.gen) c.pendingOffer = p
            cancelTimer(c, CallTimer.OFFER_WAIT)
            return
        }
        cancelTimer(c, CallTimer.OFFER_WAIT)
        // The caller's first offer proves this device won the accept race. Tell our own identity so sibling
        // devices stop ringing now: the caller's live answered_elsewhere can be lost, and a sibling would
        // otherwise ring to its timeout and record a missed call that was actually answered here (G4).
        if (c.incoming && !c.announcedWinner) {
            c.announcedWinner = true
            send(config.selfPubkey, CallPacket.AnsweredElsewhere(header(c), config.deviceId, "answered"))
        }
        applyRemoteOffer(c, p)
    }

    private fun applyRemoteOffer(c: Cx, p: CallPacket.Offer) {
        cancelTimer(c, CallTimer.OFFER_WAIT)
        c.remoteDescGen = p.gen
        fx.add(CallEffect.ApplyRemoteOffer(c.id, p.gen, p.sdp))
        flushRemoteCandidates(c)
    }

    private fun onRemoteAnswer(c: Cx, p: CallPacket.Answer) {
        if (c.pendingOfferGen != p.gen) return
        c.pendingOfferGen = null
        c.remoteDescGen = p.gen
        fx.add(CallEffect.ApplyRemoteAnswer(c.id, p.gen, p.sdp))
        flushRemoteCandidates(c)
    }

    private fun onRemoteIce(c: Cx, p: CallPacket.Ice) {
        if (p.gen < c.remoteDescGen) return
        if (p.gen == c.remoteDescGen) { fx.add(CallEffect.AddRemoteCandidates(c.id, p.gen, p.candidates)); return }
        for (cand in p.candidates) if (c.remoteCands.size < MAX_BUFFERED_CANDIDATES) c.remoteCands.add(p.gen to cand)
    }

    private fun flushRemoteCandidates(c: Cx) {
        c.remoteCands.removeAll { it.first < c.remoteDescGen }
        val ready = c.remoteCands.filter { it.first == c.remoteDescGen }.map { it.second }
        if (ready.isEmpty()) return
        c.remoteCands.removeAll { it.first == c.remoteDescGen }
        fx.add(CallEffect.AddRemoteCandidates(c.id, c.remoteDescGen, ready))
    }

    private fun onLocalDescription(e: CallEvent.LocalDescription) = forCall(e.callId) { c ->
        if (!connectingOrActive() || !c.peerOpened) return@forCall
        val device = c.peerDevice ?: return@forCall
        when (e.kind) {
            SdpKind.OFFER -> {
                if (c.pendingOfferGen != e.gen) return@forCall
                if (!sdpSendable(e.sdp)) { endWithSignal(CallOutcome.FAILED, CallReasons.PEER_ERROR); return@forCall }
                send(c.peer, CallPacket.Offer(header(c), device, e.gen, e.sdp))
            }
            SdpKind.ANSWER -> {
                if (e.gen != c.remoteDescGen || c.answeredGen >= e.gen) return@forCall
                if (!sdpSendable(e.sdp)) { endWithSignal(CallOutcome.FAILED, CallReasons.PEER_ERROR); return@forCall }
                c.answeredGen = e.gen
                send(c.peer, CallPacket.Answer(header(c), device, e.gen, e.sdp))
            }
        }
        c.descSentGen = maxOf(c.descSentGen, e.gen)
        if (c.localCands.isNotEmpty() && CallTimer.ICE_FLUSH !in c.armed) startTimer(c, CallTimer.ICE_FLUSH, config.iceFlushMs)
    }

    /** A local SDP the codec would refuse (too big, not SDP) must fail the call rather than throw out of the engine. */
    private fun sdpSendable(sdp: String): Boolean =
        runCatching { CallBodyCodec.encode(CallPacket.Offer(CallHeader("0".repeat(32), 1, 2, "0".repeat(64), "1".repeat(64), "primary", "0".repeat(32)), "primary", 0, sdp)) }.isSuccess

    private fun onLocalCandidate(e: CallEvent.LocalCandidate) = forCall(e.callId) { c ->
        if (!connectingOrActive() || !c.peerOpened || !CallBodyCodec.validCandidate(e.candidate)) return@forCall
        if (c.localCands.size >= MAX_BUFFERED_CANDIDATES) return@forCall
        c.localCands.add(e.gen to e.candidate)
        if (CallTimer.ICE_FLUSH !in c.armed) startTimer(c, CallTimer.ICE_FLUSH, config.iceFlushMs)
    }

    private fun flushLocalCandidates(c: Cx) {
        if (!connectingOrActive()) return
        val device = c.peerDevice ?: return
        c.localCands.removeAll { it.first < c.descSentGen }
        if (c.localCands.isEmpty()) return
        val ready = c.localCands.filter { it.first == c.descSentGen }
        if (c.descSentGen >= 0 && ready.isNotEmpty()) {
            c.localCands.removeAll { it.first == c.descSentGen }
            for (p in CallBodyCodec.splitIce(header(c), device, c.descSentGen, ready.map { it.second }) { random.hex(16) }) send(c.peer, p)
        }
        if (c.localCands.isNotEmpty()) startTimer(c, CallTimer.ICE_FLUSH, config.iceFlushMs)
    }

    private fun onPeerState(e: CallEvent.PeerState) = forCall(e.callId) { c ->
        val s = state
        when (e.state) {
            IceState.CONNECTED -> when (s) {
                is CallState.Connecting -> {
                    cancelTimer(c, CallTimer.CONNECT_TIMEOUT); cancelTimer(c, CallTimer.OFFER_WAIT)
                    state = CallState.Active(c.info, s.peerDevice, restarting = false, remoteMuted = false, connectedAtMs = now)
                }
                is CallState.Active -> if (s.restarting) { cancelTimer(c, CallTimer.ICE_RESTART); state = s.copy(restarting = false) }
                else -> Unit
            }
            IceState.DISCONNECTED, IceState.FAILED -> when (s) {
                is CallState.Active -> if (!s.restarting) {
                    c.gen += 1
                    c.pendingOfferGen = c.gen
                    state = s.copy(restarting = true)
                    fx.add(CallEffect.CreateOffer(c.id, c.gen, iceRestart = true))
                    startTimer(c, CallTimer.ICE_RESTART, config.iceRestartMs)
                }
                is CallState.Connecting -> if (e.state == IceState.FAILED) endWithSignal(CallOutcome.FAILED, CallReasons.ICE_FAILED)
                else -> Unit
            }
            IceState.CLOSED -> if (connectingOrActive()) endWithSignal(CallOutcome.FAILED, CallReasons.ICE_FAILED)
        }
    }

    private companion object {
        /** An `End` carrying one of these means the peer gave up on a broken call: record it as failed, not completed. */
        val FAILURE_REASONS = setOf(CallReasons.CONNECT_TIMEOUT, CallReasons.RESTART_TIMEOUT, CallReasons.ICE_FAILED, CallReasons.TURN_FAILED, CallReasons.PEER_ERROR)
        val ID_FORMAT = Regex("[0-9a-f]{32}")
        const val MAX_RECENT_ENDED = 16
        const val MAX_LATE_ACCEPTS = 8
        const val MAX_BUFFERED_CANDIDATES = 64
    }
}
