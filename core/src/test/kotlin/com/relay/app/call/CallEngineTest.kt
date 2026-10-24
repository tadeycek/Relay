package com.relay.app.call

import com.relay.app.call.CT.ALICE
import com.relay.app.call.CT.A_PHONE
import com.relay.app.call.CT.BOB
import com.relay.app.call.CT.B_DESK
import com.relay.app.call.CT.B_LAP
import com.relay.app.call.CT.B_PHONE
import com.relay.app.call.CT.CAROL
import com.relay.app.call.CT.callId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per transition of 06 §2, named `<from>_<trigger>_<to>`; glare and accept-race have their own sections. */
class CallEngineTest {
    private fun CallState.callOrNull(): CallInfo? = when (this) {
        is CallState.RingingOut -> call; is CallState.RingingIn -> call; is CallState.Connecting -> call
        is CallState.Active -> call; is CallState.Ended -> call; CallState.Idle -> null
    }
    private fun ended(r: Rig) = r.state as CallState.Ended
    private fun List<CallEffect>.records() = filterIsInstance<CallEffect.RecordCallEvent>()
    private fun List<CallEffect>.cancelled() = filterIsInstance<CallEffect.CancelTimer>().map { it.timer }.toSet()

    // =========================================================== callee: Idle -> Incoming -> ...

    @Test fun idle_inviteReceived_ringsInAndAnswersRinging() {
        val r = Rig(BOB, B_PHONE)
        val fx = r.recv(ALICE, CallPacket.Invite(r.h(1, A_PHONE, life = 45_000)))
        assertEquals(CallState.RingingIn(CallInfo(callId(1), ALICE, true), A_PHONE), r.state)
        assertTrue(fx.contains(CallEffect.StartRing(callId(1), incoming = true)))
        val ringing = fx.run { r.run { packets<CallPacket.Ringing>() } }.single()
        assertEquals(A_PHONE, ringing.to); assertEquals(B_PHONE, ringing.header.device)
        assertEquals(ALICE, (fx.filterIsInstance<CallEffect.SendSignal>().single()).toIdentity)
        assertTrue(fx.contains(CallEffect.StartTimer(callId(1), CallTimer.RING_TIMEOUT, 45_000)))
        assertEquals(CallEffect.StateChanged(r.state), fx.last())
    }

    @Test fun idle_expiredInvite_isNotRung() {
        val r = Rig(BOB, B_PHONE)
        val fx = r.recv(ALICE, CallPacket.Invite(r.h(1, A_PHONE, life = 45_000, ts = r.clock.now - 50_000)))
        assertEquals(CallState.Idle, r.state); assertTrue(fx.isEmpty())
    }

    @Test fun idle_inviteFromDisallowedPeer_isIgnored() {
        val r = Rig(BOB, B_PHONE) { it.copy(isAllowedPeer = { p -> p != ALICE }) }
        assertTrue(r.recv(ALICE, CallPacket.Invite(r.h(1, A_PHONE))).isEmpty()); assertEquals(CallState.Idle, r.state)
    }

    @Test fun idle_nonInvitePacketForUnknownCall_isIgnored() {
        val r = Rig(BOB, B_PHONE)
        for (p in listOf(CallPacket.Cancel(r.h(1, A_PHONE)), CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 0, CT.SDP), CallPacket.End(r.h(1, A_PHONE), B_PHONE),
            CallPacket.AnsweredElsewhere(r.h(1, A_PHONE), B_DESK), CallPacket.Timeout(r.h(1, A_PHONE))))
            assertTrue(p.op.wire, r.recv(ALICE, p).isEmpty())
        assertEquals(CallState.Idle, r.state)
    }

    @Test fun incoming_localAccept_connectingSendsAcceptAndRequestsIceServers() {
        val r = ringingInRig()
        val fx = r.send(CallEvent.AcceptIncoming)
        assertEquals(CallState.Connecting(CallInfo(callId(1), ALICE, true), A_PHONE), r.state)
        val accept = r.run { fx.packets<CallPacket.Accept>() }.single()
        assertEquals(A_PHONE, accept.to); assertEquals(B_PHONE, accept.header.device)
        assertTrue(fx.contains(CallEffect.StopRing(callId(1)))); assertTrue(fx.contains(CallEffect.RequestIceServers(callId(1))))
        assertTrue(fx.cancelled().contains(CallTimer.RING_TIMEOUT))
        assertTrue(r.run { fx.timers() }.containsAll(listOf(CallTimer.OFFER_WAIT, CallTimer.CONNECT_TIMEOUT)))
        assertEquals(1, fx.records().size.let { 1 }) // no record yet
        assertTrue(fx.records().isEmpty())
    }

    @Test fun incoming_localDecline_endsDeclinedAndTellsCallerAndSiblings() {
        val r = ringingInRig()
        val fx = r.send(CallEvent.DeclineIncoming)
        assertEquals(CallOutcome.DECLINED, ended(r).outcome)
        val d = r.run { fx.packets<CallPacket.Decline>() }.single(); assertEquals(A_PHONE, d.to)
        val sends = fx.filterIsInstance<CallEffect.SendSignal>()
        assertEquals(ALICE, sends.first { it.packet is CallPacket.Decline }.toIdentity)
        val ae = sends.first { it.packet is CallPacket.AnsweredElsewhere }
        assertEquals(BOB, ae.toIdentity)
        assertEquals("declined", (ae.packet as CallPacket.AnsweredElsewhere).reason); assertEquals(B_PHONE, (ae.packet as CallPacket.AnsweredElsewhere).winner)
        assertEquals(CallOutcome.DECLINED, fx.records().single().outcome)
        assertTrue(fx.contains(CallEffect.StopRing(callId(1))))
    }

    @Test fun incoming_callerCancels_endsMissedAndRecords() {
        val r = ringingInRig()
        val fx = r.recv(ALICE, CallPacket.Cancel(r.h(1, A_PHONE)))
        assertEquals(CallOutcome.MISSED, ended(r).outcome)
        assertEquals(CallOutcome.MISSED, fx.records().single().outcome); assertTrue(fx.records().single().incoming)
        assertTrue(fx.contains(CallEffect.StopRing(callId(1))))
    }

    @Test fun incoming_callerCancelsForGlare_endsWithoutMissedRecord() {
        val r = ringingInRig()
        val fx = r.recv(ALICE, CallPacket.Cancel(r.h(1, A_PHONE), CallReasons.GLARE))
        assertEquals(CallOutcome.GLARE_LOST, ended(r).outcome); assertTrue(fx.records().isEmpty())
    }

    @Test fun incoming_callerTimeout_endsMissed() {
        val r = ringingInRig()
        r.recv(ALICE, CallPacket.Timeout(r.h(1, A_PHONE)))
        assertEquals(CallOutcome.MISSED, ended(r).outcome)
    }

    @Test fun incoming_ringTimerFires_endsMissedWithoutSignals() {
        val r = ringingInRig()
        val fx = r.send(CallEvent.TimerFired(callId(1), CallTimer.RING_TIMEOUT))
        assertEquals(CallOutcome.MISSED, ended(r).outcome)
        assertTrue(fx.none { it is CallEffect.SendSignal }); assertEquals(CallOutcome.MISSED, fx.records().single().outcome)
    }

    @Test fun incoming_answeredElsewhereFromCaller_endsSilently() {
        val r = ringingInRig()
        val fx = r.recv(ALICE, CallPacket.AnsweredElsewhere(r.h(1, A_PHONE), B_DESK))
        assertEquals(CallOutcome.ANSWERED_ELSEWHERE, ended(r).outcome)
        assertTrue("LP-D10: no missed-call record for a call answered on another device", fx.records().isEmpty())
        assertTrue(fx.contains(CallEffect.StopRing(callId(1))))
    }

    @Test fun incoming_selfSyncDecline_endsSilently() {
        val r = ringingInRig()
        val fx = r.recv(BOB, CallPacket.AnsweredElsewhere(r.h(1, B_DESK, caller = ALICE, callee = BOB), B_DESK, "declined"))
        assertEquals(CallOutcome.ANSWERED_ELSEWHERE, ended(r).outcome); assertTrue(fx.records().isEmpty())
    }

    @Test fun incoming_answeredElsewhereNamingSelfAsWinner_isIgnored() {
        val r = ringingInRig()
        assertTrue(r.recv(ALICE, CallPacket.AnsweredElsewhere(r.h(1, A_PHONE), B_PHONE)).isEmpty())
        assertTrue(r.state is CallState.RingingIn)
    }

    @Test fun incoming_answeredElsewhereFromWrongSenderOrBinding_isIgnored() {
        val r = ringingInRig()
        // answered_elsewhere is not self-authenticating: a third party, a mismatched caller/callee pair, or a different caller device is dropped
        assertTrue(r.recv(CAROL, CallPacket.AnsweredElsewhere(r.h(1, A_PHONE), B_DESK)).isEmpty())
        assertTrue(r.recv(ALICE, CallPacket.AnsweredElsewhere(r.h(1, A_PHONE, caller = CAROL), B_DESK)).isEmpty())
        assertTrue(r.recv(ALICE, CallPacket.AnsweredElsewhere(r.h(1, B_LAP), B_DESK)).isEmpty())
        assertTrue(r.recv(ALICE, CallPacket.AnsweredElsewhere(r.h(2, A_PHONE), B_DESK)).isEmpty())
        assertTrue(r.state is CallState.RingingIn)
    }

    @Test fun incoming_duplicateInvite_isIdempotent() {
        val r = ringingInRig()
        assertTrue(r.recv(ALICE, CallPacket.Invite(r.h(1, A_PHONE, life = 45_000))).isEmpty())
        assertTrue(r.state is CallState.RingingIn)
    }

    @Test fun incoming_offerBeforeLocalAccept_isIgnored() {
        val r = ringingInRig()
        assertTrue(r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 0, CT.SDP)).isEmpty())
        assertTrue(r.state is CallState.RingingIn)
    }

    @Test fun incoming_foreignDeviceCancel_isIgnored() {
        val r = ringingInRig()
        assertTrue(r.recv(ALICE, CallPacket.Cancel(r.h(1, B_LAP))).isEmpty())
    }

    @Test fun incoming_hangUpWhileRinging_actsAsDecline() {
        val r = ringingInRig()
        r.send(CallEvent.HangUp)
        assertEquals(CallOutcome.DECLINED, ended(r).outcome)
    }

    // =========================================================== callee: Connecting

    @Test fun calleeConnecting_iceServersThenOffer_opensPeerAppliesOfferAndSendsAnswer() {
        val r = acceptedCalleeRig(); val id = callId(1)
        val fx1 = r.send(CallEvent.IceServersReady(id, SERVERS))
        assertEquals(CallEffect.OpenPeerConnection(id, PeerRole.ANSWERER, SERVERS, false), fx1.single { it is CallEffect.OpenPeerConnection })
        val fx2 = r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 0, CT.SDP))
        assertTrue(fx2.contains(CallEffect.ApplyRemoteOffer(id, 0, CT.SDP)))
        assertTrue(fx2.cancelled().contains(CallTimer.OFFER_WAIT))
        val fx3 = r.send(CallEvent.LocalDescription(id, 0, SdpKind.ANSWER, CT.SDP))
        val ans = r.run { fx3.packets<CallPacket.Answer>() }.single()
        assertEquals(A_PHONE, ans.to); assertEquals(0, ans.gen); assertEquals(CT.SDP, ans.sdp); assertEquals(B_PHONE, ans.header.device)
    }

    @Test fun calleeConnecting_offerBeforeIceServers_isHeldThenApplied() {
        val r = acceptedCalleeRig(); val id = callId(1)
        val early = r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 0, CT.SDP))
        assertTrue(early.none { it is CallEffect.ApplyRemoteOffer })
        val fx = r.send(CallEvent.IceServersReady(id, SERVERS))
        val kinds = fx.map { it::class.simpleName }
        assertTrue(kinds.indexOf("OpenPeerConnection") in 0 until kinds.indexOf("ApplyRemoteOffer"))
    }

    @Test fun calleeConnecting_noOfferWithinWait_endsLostRaceAndTellsCallerDevice() {
        val r = acceptedCalleeRig()
        val fx = r.send(CallEvent.TimerFired(callId(1), CallTimer.OFFER_WAIT))
        val e = ended(r); assertEquals(CallOutcome.LOST_RACE, e.outcome); assertEquals(CallReasons.NO_OFFER, e.reason)
        assertEquals(A_PHONE, r.run { fx.packets<CallPacket.End>() }.single().to)
        assertTrue(fx.records().isEmpty())
    }

    @Test fun calleeConnecting_answeredElsewhereWithOtherWinner_endsLostRaceSilently() {
        val r = acceptedCalleeRig()
        val fx = r.recv(ALICE, CallPacket.AnsweredElsewhere(r.h(1, A_PHONE), B_DESK))
        assertEquals(CallOutcome.LOST_RACE, ended(r).outcome)
        assertTrue(fx.none { it is CallEffect.SendSignal }); assertTrue(fx.records().isEmpty())
        assertTrue("peer connection never opened, nothing to close", fx.none { it is CallEffect.ClosePeerConnection })
    }

    @Test fun calleeConnecting_answeredElsewhereNamingSelf_keepsConnecting() {
        val r = acceptedCalleeRig()
        assertTrue(r.recv(ALICE, CallPacket.AnsweredElsewhere(r.h(1, A_PHONE), B_PHONE)).isEmpty())
        assertTrue(r.state is CallState.Connecting)
    }

    /**
     * Review finding (G4): the winning device never told its own identity it had won, so a sibling depended
     * entirely on the caller's live answered_elsewhere - and rang to its timeout, recording a missed call that
     * was actually answered, whenever that live signal was lost.
     */
    @Test fun calleeReceivesCallerOffer_tellsItsOwnIdentityItWon() {
        val r = acceptedCalleeRig()
        r.send(CallEvent.IceServersReady(callId(1), SERVERS))
        val fx = r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 0, CT.SDP))
        val self = fx.filterIsInstance<CallEffect.SendSignal>().filter { it.toIdentity == BOB }
        assertEquals("exactly one self-signal is sent", 1, self.size)
        val packet = self.single().packet
        assertTrue("it is an answered_elsewhere naming this device", packet is CallPacket.AnsweredElsewhere)
        assertEquals(B_PHONE, (packet as CallPacket.AnsweredElsewhere).winner)
        // A second offer must not repeat it.
        assertTrue(r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 1, CT.SDP)).none { it is CallEffect.SendSignal && it.toIdentity == BOB })
    }

    @Test fun siblingWhoGetsThatSelfSignalStopsRinging() {
        val sibling = ringingInRig()
        // The winner is a DIFFERENT device of this identity, so this one stands down.
        sibling.recv(BOB, CallPacket.AnsweredElsewhere(sibling.h(1, B_DESK), B_DESK))
        assertEquals(CallOutcome.ANSWERED_ELSEWHERE, ended(sibling).outcome)
    }

    @Test fun calleeConnecting_offerFromOtherDeviceOrToOtherDevice_isIgnored() {
        val r = acceptedCalleeRig(); r.send(CallEvent.IceServersReady(callId(1), SERVERS))
        assertTrue(r.recv(ALICE, CallPacket.Offer(r.h(1, B_LAP), B_PHONE, 0, CT.SDP)).isEmpty())
        assertTrue(r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_DESK, 0, CT.SDP)).isEmpty())
    }

    @Test fun calleeConnecting_iceServersFail_endsFailed() {
        val r = acceptedCalleeRig()
        val fx = r.send(CallEvent.IceServersFailed(callId(1)))
        assertEquals(CallOutcome.FAILED, ended(r).outcome); assertEquals(CallReasons.TURN_FAILED, ended(r).reason)
        assertEquals(A_PHONE, r.run { fx.packets<CallPacket.End>() }.single().to)
    }

    @Test fun connecting_connectTimeout_endsFailedAndClosesPeer() {
        val r = acceptedCalleeRig(); r.send(CallEvent.IceServersReady(callId(1), SERVERS))
        val fx = r.send(CallEvent.TimerFired(callId(1), CallTimer.CONNECT_TIMEOUT))
        assertEquals(CallOutcome.FAILED, ended(r).outcome); assertEquals(CallReasons.CONNECT_TIMEOUT, ended(r).reason)
        assertTrue(fx.contains(CallEffect.ClosePeerConnection(callId(1)))); assertEquals(CallOutcome.FAILED, fx.records().single().outcome)
    }

    @Test fun connecting_peerFailsOrCloses_endsFailed() {
        for (s in listOf(IceState.FAILED, IceState.CLOSED)) {
            val r = acceptedCalleeRig(); r.send(CallEvent.IceServersReady(callId(1), SERVERS))
            r.send(CallEvent.PeerState(callId(1), s)); assertEquals(CallOutcome.FAILED, ended(r).outcome)
        }
        val r = acceptedCalleeRig(); r.send(CallEvent.IceServersReady(callId(1), SERVERS)); r.send(CallEvent.PeerFailed(callId(1)))
        assertEquals(CallReasons.PEER_ERROR, ended(r).reason)
    }

    @Test fun connecting_remoteEnd_endsFailed() {
        val r = acceptedCalleeRig()
        r.recv(ALICE, CallPacket.End(r.h(1, A_PHONE), B_PHONE))
        assertEquals(CallOutcome.FAILED, ended(r).outcome)
    }

    @Test fun connecting_userHangUp_sendsEndAndEndsCancelled() {
        val r = acceptedCalleeRig()
        val fx = r.send(CallEvent.HangUp)
        assertEquals(CallOutcome.CANCELLED, ended(r).outcome)
        assertEquals(A_PHONE, r.run { fx.packets<CallPacket.End>() }.single().to)
    }

    // =========================================================== active

    @Test fun connecting_peerConnected_becomesActive() {
        val r = acceptedCalleeRig(); val id = callId(1)
        r.send(CallEvent.IceServersReady(id, SERVERS)); r.tick(2000)
        val fx = r.send(CallEvent.PeerState(id, IceState.CONNECTED))
        val a = r.state as CallState.Active
        assertEquals(r.clock.now, a.connectedAtMs); assertFalse(a.restarting)
        assertTrue(fx.cancelled().containsAll(listOf(CallTimer.CONNECT_TIMEOUT, CallTimer.OFFER_WAIT)))
    }

    @Test fun active_localHangUp_sendsEndClosesAndRecordsCompleted() {
        val r = activeCalleeRig(); r.tick(65_000)
        val fx = r.send(CallEvent.HangUp)
        assertEquals(CallOutcome.COMPLETED, ended(r).outcome)
        assertEquals(A_PHONE, r.run { fx.packets<CallPacket.End>() }.single().to)
        assertTrue(fx.contains(CallEffect.ClosePeerConnection(callId(1))))
        assertEquals(65_000L, fx.records().single().durationMs)
        assertEquals(CallEffect.StateChanged(r.state), fx.last())
    }

    @Test fun active_remoteEnd_endsCompletedWithoutEchoingEnd() {
        val r = activeCalleeRig(); r.tick(5000)
        val fx = r.recv(ALICE, CallPacket.End(r.h(1, A_PHONE), B_PHONE))
        assertEquals(CallOutcome.COMPLETED, ended(r).outcome)
        assertTrue(fx.none { it is CallEffect.SendSignal }); assertTrue(fx.contains(CallEffect.ClosePeerConnection(callId(1))))
    }

    @Test fun active_remoteEndFromWrongDevice_isIgnored() {
        val r = activeCalleeRig()
        assertTrue(r.recv(ALICE, CallPacket.End(r.h(1, B_LAP), B_PHONE)).isEmpty()); assertTrue(r.state is CallState.Active)
    }

    @Test fun active_muteAndRemoteMute() {
        val r = activeCalleeRig(); val id = callId(1)
        val fx = r.send(CallEvent.SetMuted(true))
        assertTrue(fx.contains(CallEffect.SetMicMuted(id, true)))
        assertTrue(r.run { fx.packets<CallPacket.Mute>() }.single().muted)
        r.recv(ALICE, CallPacket.Mute(r.h(1, A_PHONE), B_PHONE, true))
        assertTrue((r.state as CallState.Active).remoteMuted)
    }

    @Test fun active_iceDisconnected_callerStartsRestartOfferAndTimer() {
        val r = activeCallerRig(); val id = callId(1)
        val fx = r.send(CallEvent.PeerState(id, IceState.DISCONNECTED))
        assertTrue((r.state as CallState.Active).restarting)
        assertTrue(fx.contains(CallEffect.CreateOffer(id, 1, iceRestart = true)))
        assertTrue(fx.contains(CallEffect.StartTimer(id, CallTimer.ICE_RESTART, 20_000)))
        val sent = r.send(CallEvent.LocalDescription(id, 1, SdpKind.OFFER, CT.SDP))
        val o = r.run { sent.packets<CallPacket.Offer>() }.single(); assertEquals(1, o.gen); assertEquals(B_PHONE, o.to)
    }

    @Test fun active_iceRestartAnswered_applyAnswerThenConnectedClearsRestart() {
        val r = activeCallerRig(); val id = callId(1)
        r.send(CallEvent.PeerState(id, IceState.DISCONNECTED)); r.send(CallEvent.LocalDescription(id, 1, SdpKind.OFFER, CT.SDP))
        val fx = r.recv(BOB, CallPacket.Answer(r.h(1, B_PHONE), A_PHONE, 1, CT.SDP))
        assertTrue(fx.contains(CallEffect.ApplyRemoteAnswer(id, 1, CT.SDP)))
        val back = r.send(CallEvent.PeerState(id, IceState.CONNECTED))
        assertFalse((r.state as CallState.Active).restarting); assertTrue(back.cancelled().contains(CallTimer.ICE_RESTART))
    }

    @Test fun active_iceRestartTimeout_endsFailedWithEnd() {
        val r = activeCallerRig(); val id = callId(1)
        r.send(CallEvent.PeerState(id, IceState.DISCONNECTED))
        val fx = r.send(CallEvent.TimerFired(id, CallTimer.ICE_RESTART))
        assertEquals(CallOutcome.FAILED, ended(r).outcome); assertEquals(CallReasons.RESTART_TIMEOUT, ended(r).reason)
        assertEquals(B_PHONE, r.run { fx.packets<CallPacket.End>() }.single().to)
    }

    @Test fun active_iceFailed_alsoRestartsInsteadOfDropping() {
        val r = activeCallerRig()
        r.send(CallEvent.PeerState(callId(1), IceState.FAILED))
        assertTrue((r.state as CallState.Active).restarting)
    }

    @Test fun active_iceClosed_endsFailed() {
        val r = activeCallerRig(); r.send(CallEvent.PeerState(callId(1), IceState.CLOSED))
        assertEquals(CallOutcome.FAILED, ended(r).outcome)
    }

    @Test fun active_disconnectedTwice_startsOnlyOneRestart() {
        val r = activeCallerRig(); val id = callId(1)
        r.send(CallEvent.PeerState(id, IceState.DISCONNECTED))
        assertTrue(r.send(CallEvent.PeerState(id, IceState.DISCONNECTED)).isEmpty())
    }

    @Test fun active_calleeSideRestartOffer_isInitiatedByCalleeToo() {
        val r = activeCalleeRig(); val id = callId(1)
        val fx = r.send(CallEvent.PeerState(id, IceState.DISCONNECTED))
        assertTrue(fx.contains(CallEffect.CreateOffer(id, 1, true)))
    }

    @Test fun active_restartOfferFromPeer_isAppliedAndAnswered() {
        val r = activeCalleeRig(); val id = callId(1)
        val fx = r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 1, CT.SDP))
        assertTrue(fx.contains(CallEffect.ApplyRemoteOffer(id, 1, CT.SDP)))
        val back = r.send(CallEvent.LocalDescription(id, 1, SdpKind.ANSWER, CT.SDP))
        assertEquals(1, r.run { back.packets<CallPacket.Answer>() }.single().gen)
    }

    @Test fun active_restartCollision_callerIgnoresCalleeOfferCalleeYieldsToCaller() {
        val caller = activeCallerRig(); val callee = activeCalleeRig()
        val id = callId(1)
        caller.send(CallEvent.PeerState(id, IceState.DISCONNECTED)); callee.send(CallEvent.PeerState(id, IceState.DISCONNECTED))
        // both sent gen 1 offers; each receives the other's
        assertTrue("impolite caller keeps its own offer", caller.recv(BOB, CallPacket.Offer(caller.h(1, B_PHONE), A_PHONE, 1, CT.SDP)).isEmpty())
        val fx = callee.recv(ALICE, CallPacket.Offer(callee.h(1, A_PHONE), B_PHONE, 1, CT.SDP))
        assertTrue("polite callee rolls back and applies the caller's offer", fx.contains(CallEffect.ApplyRemoteOffer(id, 1, CT.SDP)))
    }

    @Test fun active_staleOrDuplicateOffersAndAnswers_areIgnored() {
        val r = activeCalleeRig(); val id = callId(1)
        assertTrue("gen 0 already negotiated", r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 0, CT.SDP)).isEmpty())
        r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 1, CT.SDP))
        assertTrue("duplicate gen 1", r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 1, CT.SDP)).isEmpty())
        assertTrue("answer with no outstanding offer", r.recv(ALICE, CallPacket.Answer(r.h(1, A_PHONE), B_PHONE, 1, CT.SDP)).isEmpty())
        val c = activeCallerRig()
        assertTrue("answer for a generation never offered", c.recv(BOB, CallPacket.Answer(c.h(1, B_PHONE), A_PHONE, 5, CT.SDP)).isEmpty())
        assertTrue("duplicate answer for gen 0", c.recv(BOB, CallPacket.Answer(c.h(1, B_PHONE), A_PHONE, 0, CT.SDP)).isEmpty())
        assertEquals(id, (r.state as CallState.Active).call.id)
    }

    // =========================================================== caller

    @Test fun idle_startCall_sendsStoredUrgentInviteAndStartsTimers() {
        val r = Rig(ALICE, A_PHONE); r.random.queue += callId(7)
        val fx = r.send(CallEvent.StartCall(BOB))
        assertEquals(CallState.RingingOut(CallInfo(callId(7), BOB, false), false), r.state)
        val s = fx.filterIsInstance<CallEffect.SendSignal>().single()
        assertEquals(SignalMode.LIVE_AND_STORED_URGENT, s.mode); assertEquals(BOB, s.toIdentity)
        val inv = s.packet as CallPacket.Invite
        assertEquals(listOf(callId(7), ALICE, BOB, A_PHONE, "audio"), inv.header.let { listOf(it.call, it.caller, it.callee, it.device, it.media) })
        assertEquals(r.clock.now, inv.header.ts); assertEquals(r.clock.now + 45_000, inv.header.expires)
        assertTrue(r.run { fx.timers() }.containsAll(listOf(CallTimer.RING_TIMEOUT, CallTimer.INVITE_RESEND)))
        CallBodyCodec.encode(inv) // must be encodable
    }

    @Test fun startCall_whileNotIdle_isRejected() {
        val r = ringingOutRig()
        val fx = r.send(CallEvent.StartCall(CAROL))
        assertEquals(listOf<CallEffect>(CallEffect.CommandRejected(CallReasons.BUSY_SELF)), fx)
        assertTrue(r.state is CallState.RingingOut)
    }

    @Test fun startCall_toSelfOrGarbage_isRejected() {
        val r = Rig(ALICE, A_PHONE)
        assertEquals(listOf<CallEffect>(CallEffect.CommandRejected(CallReasons.INVALID_CALLEE)), r.send(CallEvent.StartCall(ALICE)))
        assertEquals(listOf<CallEffect>(CallEffect.CommandRejected(CallReasons.INVALID_CALLEE)), r.send(CallEvent.StartCall("nope")))
        assertEquals(CallState.Idle, r.state)
    }

    @Test fun startCall_afterEnded_isAllowed() {
        val r = ringingOutRig(); r.send(CallEvent.HangUp); assertTrue(r.state is CallState.Ended)
        r.random.queue += callId(2); r.send(CallEvent.StartCall(BOB))
        assertEquals(callId(2), (r.state as CallState.RingingOut).call.id)
    }

    @Test fun ringingOut_resendTimer_resendsInviteWithFreshNonceAndSameExpiry() {
        val r = ringingOutRig()
        val first = r.log.filterIsInstance<CallEffect.SendSignal>().first().packet as CallPacket.Invite
        r.tick(5000)
        val fx = r.send(CallEvent.TimerFired(callId(1), CallTimer.INVITE_RESEND))
        val s = fx.filterIsInstance<CallEffect.SendSignal>().single(); val again = s.packet as CallPacket.Invite
        assertEquals(SignalMode.LIVE, s.mode)
        assertNotEquals(first.header.nonce, again.header.nonce); assertEquals(first.header.expires, again.header.expires)
        assertEquals(r.clock.now, again.header.ts)
        assertTrue(fx.contains(CallEffect.StartTimer(callId(1), CallTimer.INVITE_RESEND, 5000)))
    }

    @Test fun ringingOut_resendAfterInviteExpiry_isNotSent() {
        val r = ringingOutRig(); r.tick(45_000)
        assertTrue(r.send(CallEvent.TimerFired(callId(1), CallTimer.INVITE_RESEND)).none { it is CallEffect.SendSignal })
    }

    @Test fun ringingOut_calleeRinging_startsRingback() {
        val r = ringingOutRig()
        val fx = r.recv(BOB, CallPacket.Ringing(r.h(1, B_PHONE), A_PHONE))
        assertTrue((r.state as CallState.RingingOut).calleeRinging); assertTrue(fx.contains(CallEffect.StartRing(callId(1), incoming = false)))
        assertTrue("second device ringing does not restart the ringback", r.recv(BOB, CallPacket.Ringing(r.h(1, B_DESK), A_PHONE)).none { it is CallEffect.StartRing })
    }

    // ---- accept race (LP-D10) ----

    @Test fun ringingOut_firstAccept_connectingAndAnnouncesWinner() {
        val r = ringingOutRig()
        val fx = r.recv(BOB, CallPacket.Accept(r.h(1, B_DESK), A_PHONE))
        assertEquals(CallState.Connecting(CallInfo(callId(1), BOB, false), B_DESK), r.state)
        val ae = r.run { fx.packets<CallPacket.AnsweredElsewhere>() }.single()
        assertEquals(B_DESK, ae.winner); assertEquals("answered", ae.reason); assertEquals(A_PHONE, ae.header.device)
        assertEquals(BOB, fx.filterIsInstance<CallEffect.SendSignal>().single { it.packet is CallPacket.AnsweredElsewhere }.toIdentity)
        assertTrue(fx.contains(CallEffect.RequestIceServers(callId(1))))
        assertTrue(fx.cancelled().containsAll(listOf(CallTimer.RING_TIMEOUT, CallTimer.INVITE_RESEND)))
    }

    @Test fun acceptRace_secondDeviceAccept_losesAndIsToldAgainOnce() {
        val r = acceptedCallerRig(winner = B_DESK)
        val fx = r.recv(BOB, CallPacket.Accept(r.h(1, B_PHONE), A_PHONE))
        assertEquals(B_DESK, (r.state as CallState.Connecting).peerDevice)
        assertEquals(B_DESK, r.run { fx.packets<CallPacket.AnsweredElsewhere>() }.single().winner)
        // the same loser retrying is not answered again (bounded chatter)
        assertTrue(r.recv(BOB, CallPacket.Accept(r.h(1, B_PHONE), A_PHONE)).isEmpty())
        // a third device is told too
        assertEquals(1, r.recv(BOB, CallPacket.Accept(r.h(1, B_LAP), A_PHONE)).filterIsInstance<CallEffect.SendSignal>().size)
    }

    @Test fun acceptRace_duplicateAcceptFromWinner_isIgnored() {
        val r = acceptedCallerRig(winner = B_DESK)
        assertTrue(r.recv(BOB, CallPacket.Accept(r.h(1, B_DESK), A_PHONE)).isEmpty())
    }

    @Test fun acceptRace_lateAcceptWhileActive_stillAnsweredElsewhere() {
        val r = activeCallerRig()
        val fx = r.recv(BOB, CallPacket.Accept(r.h(1, B_LAP), A_PHONE))
        assertEquals(B_PHONE, r.run { fx.packets<CallPacket.AnsweredElsewhere>() }.single().winner); assertTrue(r.state is CallState.Active)
    }

    @Test fun ringingOut_acceptForAnotherCallerDeviceOrBadBinding_isIgnored() {
        val r = ringingOutRig()
        assertTrue(r.recv(BOB, CallPacket.Accept(r.h(1, B_DESK), CT.A_PHONE.replace('1', '9'))).isEmpty())
        assertTrue(r.recv(BOB, CallPacket.Accept(r.h(1, B_DESK, callee = CAROL), A_PHONE)).isEmpty())
        assertTrue(r.recv(CAROL, CallPacket.Accept(r.h(1, B_DESK), A_PHONE)).isEmpty())
        assertTrue(r.recv(BOB, CallPacket.Accept(r.h(2, B_DESK), A_PHONE)).isEmpty())
        assertTrue(r.state is CallState.RingingOut)
    }

    // ---- remaining caller transitions ----

    @Test fun ringingOut_calleeDeclines_endsDeclined() {
        val r = ringingOutRig()
        val fx = r.recv(BOB, CallPacket.Decline(r.h(1, B_PHONE), A_PHONE))
        assertEquals(CallOutcome.DECLINED, ended(r).outcome); assertEquals(CallOutcome.DECLINED, fx.records().single().outcome); assertFalse(fx.records().single().incoming)
        assertTrue(fx.contains(CallEffect.StopRing(callId(1)))); assertTrue(fx.cancelled().containsAll(listOf(CallTimer.RING_TIMEOUT, CallTimer.INVITE_RESEND)))
    }

    /**
     * Review finding: one busy device used to end the whole call, although LP-D10 rings every device and the
     * first to accept wins. Busy is recorded but not terminal while other devices could still answer; the call
     * ends BUSY only at the ring timeout.
     */
    @Test fun ringingOut_oneBusyDeviceDoesNotEndTheCall() {
        val r = ringingOutRig()
        r.recv(BOB, CallPacket.Busy(r.h(1, B_DESK), A_PHONE))
        assertTrue("a busy device must not end a call another device can answer", r.state is CallState.RingingOut)
        r.recv(BOB, CallPacket.Accept(r.h(1, B_PHONE), A_PHONE))
        assertTrue("the phone that answers still gets through", r.state is CallState.Connecting)
    }

    @Test fun ringingOut_nobodyAnswers_endsBusyAtRingTimeout() {
        val r = ringingOutRig()
        r.recv(BOB, CallPacket.Busy(r.h(1, B_DESK), A_PHONE))
        r.send(CallEvent.TimerFired(callId(1), CallTimer.RING_TIMEOUT))
        assertEquals(CallOutcome.BUSY, ended(r).outcome)
    }

    @Test fun ringingOut_userHangUp_broadcastsCancelAndEndsCancelled() {
        val r = ringingOutRig()
        val fx = r.send(CallEvent.HangUp)
        assertEquals(CallOutcome.CANCELLED, ended(r).outcome)
        assertEquals(BOB, fx.filterIsInstance<CallEffect.SendSignal>().single().toIdentity)
        r.run { fx.packets<CallPacket.Cancel>() }.single()
        assertEquals(CallOutcome.CANCELLED, fx.records().single().outcome)
    }

    @Test fun ringingOut_ringTimeout_broadcastsTimeoutAndEndsNoAnswer() {
        val r = ringingOutRig(); r.tick(45_000)
        val fx = r.send(CallEvent.TimerFired(callId(1), CallTimer.RING_TIMEOUT))
        assertEquals(CallOutcome.NO_ANSWER, ended(r).outcome)
        r.run { fx.packets<CallPacket.Timeout>() }.single(); assertEquals(CallOutcome.NO_ANSWER, fx.records().single().outcome)
    }

    @Test fun callerConnecting_iceServersReady_opensOffererAndCreatesOffer() {
        val r = acceptedCallerRig(); val id = callId(1)
        val fx = r.send(CallEvent.IceServersReady(id, SERVERS))
        assertEquals(CallEffect.OpenPeerConnection(id, PeerRole.OFFERER, SERVERS, false), fx[0]); assertEquals(CallEffect.CreateOffer(id, 0, false), fx[1])
        val sent = r.send(CallEvent.LocalDescription(id, 0, SdpKind.OFFER, CT.SDP))
        val o = r.run { sent.packets<CallPacket.Offer>() }.single()
        assertEquals(B_PHONE, o.to); assertEquals(0, o.gen); assertEquals(A_PHONE, o.header.device)
    }

    @Test fun callerConnecting_relayOnlyConfig_reachesPeerOpen() {
        val r = ringingOutRig(tweak = { it.copy(relayOnly = true) })
        r.recv(BOB, CallPacket.Accept(r.h(1, B_PHONE), A_PHONE))
        assertTrue((r.send(CallEvent.IceServersReady(callId(1), SERVERS))[0] as CallEffect.OpenPeerConnection).relayOnly)
    }

    @Test fun callerConnecting_answerThenCandidates_areAppliedAfterRemoteDescription() {
        val r = acceptedCallerRig(); val id = callId(1)
        r.send(CallEvent.IceServersReady(id, SERVERS)); r.send(CallEvent.LocalDescription(id, 0, SdpKind.OFFER, CT.SDP))
        val early = r.recv(BOB, CallPacket.Ice(r.h(1, B_PHONE), A_PHONE, 0, listOf(CT.cand(1))))
        assertTrue("held until the answer is applied", early.none { it is CallEffect.AddRemoteCandidates })
        val fx = r.recv(BOB, CallPacket.Answer(r.h(1, B_PHONE), A_PHONE, 0, CT.SDP))
        assertEquals(CallEffect.ApplyRemoteAnswer(id, 0, CT.SDP), fx[0]); assertEquals(CallEffect.AddRemoteCandidates(id, 0, listOf(CT.cand(1))), fx[1])
        val late = r.recv(BOB, CallPacket.Ice(r.h(1, B_PHONE), A_PHONE, 0, listOf(CT.cand(2))))
        assertEquals(CallEffect.AddRemoteCandidates(id, 0, listOf(CT.cand(2))), late.first())
    }

    @Test fun localCandidates_areBatchedOnTheFlushTimerAndSentOnlyAfterTheOffer() {
        val r = acceptedCallerRig(); val id = callId(1)
        r.send(CallEvent.IceServersReady(id, SERVERS))
        val a = r.send(CallEvent.LocalCandidate(id, 0, CT.cand(1)))
        assertTrue(a.contains(CallEffect.StartTimer(id, CallTimer.ICE_FLUSH, 250)))
        assertTrue(r.send(CallEvent.LocalCandidate(id, 0, CT.cand(2))).none { it is CallEffect.StartTimer })
        val early = r.send(CallEvent.TimerFired(id, CallTimer.ICE_FLUSH))
        assertTrue("offer not sent yet: keep buffering", early.none { it is CallEffect.SendSignal })
        r.send(CallEvent.LocalDescription(id, 0, SdpKind.OFFER, CT.SDP))
        val fx = r.send(CallEvent.TimerFired(id, CallTimer.ICE_FLUSH))
        val ice = r.run { fx.packets<CallPacket.Ice>() }.single()
        assertEquals(listOf(CT.cand(1), CT.cand(2)), ice.candidates); assertEquals(B_PHONE, ice.to)
    }

    @Test fun localCandidateBuffer_isBounded() {
        val r = acceptedCallerRig(); val id = callId(1); r.send(CallEvent.IceServersReady(id, SERVERS))
        repeat(500) { r.send(CallEvent.LocalCandidate(id, 0, CT.cand(it))) }
        assertTrue(r.engine.stats().getValue("localCandidates") <= 64)
    }

    @Test fun iceFromPeerForStaleGeneration_isDropped() {
        val r = activeCallerRig()
        assertTrue(r.recv(BOB, CallPacket.Ice(r.h(1, B_PHONE), A_PHONE, 0, listOf(CT.cand(9)))).any { it is CallEffect.AddRemoteCandidates })
        r.send(CallEvent.PeerState(callId(1), IceState.DISCONNECTED)); r.send(CallEvent.LocalDescription(callId(1), 1, SdpKind.OFFER, CT.SDP))
        r.recv(BOB, CallPacket.Answer(r.h(1, B_PHONE), A_PHONE, 1, CT.SDP))
        assertTrue(r.recv(BOB, CallPacket.Ice(r.h(1, B_PHONE), A_PHONE, 0, listOf(CT.cand(9)))).isEmpty())
    }

    // =========================================================== glare

    private fun glareRig(mine: Int): Rig {
        val r = Rig(ALICE, A_PHONE); r.random.queue += callId(mine); r.send(CallEvent.StartCall(BOB)); return r
    }

    @Test fun glare_myCallIdLower_myInviteSurvivesAndTheirsIsIgnored() {
        val r = glareRig(mine = 1)
        val fx = r.recv(BOB, CallPacket.Invite(r.h(2, B_PHONE, caller = BOB, callee = ALICE, life = 45_000)))
        assertTrue(fx.isEmpty()); assertEquals(callId(1), (r.state as CallState.RingingOut).call.id)
    }

    @Test fun glare_myCallIdHigher_iCancelMineAndAcceptTheirs() {
        val r = glareRig(mine = 2)
        val fx = r.recv(BOB, CallPacket.Invite(r.h(1, B_PHONE, caller = BOB, callee = ALICE, life = 45_000)))
        val cancel = r.run { fx.packets<CallPacket.Cancel>() }.single()
        assertEquals(callId(2), cancel.header.call); assertEquals(CallReasons.GLARE, cancel.reason)
        val accept = r.run { fx.packets<CallPacket.Accept>() }.single()
        assertEquals(callId(1), accept.header.call); assertEquals(B_PHONE, accept.to)
        assertEquals(CallState.Connecting(CallInfo(callId(1), BOB, true), B_PHONE), r.state)
        assertTrue(fx.records().isEmpty()); assertTrue(fx.contains(CallEffect.RequestIceServers(callId(1))))
        assertTrue(fx.cancelled().containsAll(listOf(CallTimer.RING_TIMEOUT, CallTimer.INVITE_RESEND)))
        // the stale timer of the losing call does nothing
        assertTrue(r.send(CallEvent.TimerFired(callId(2), CallTimer.RING_TIMEOUT)).isEmpty())
    }

    @Test fun glare_cancelOfLosingCallIsNotRecordedAsEndedBeforeAcceptIsSent() {
        val r = glareRig(mine = 2)
        val fx = r.recv(BOB, CallPacket.Invite(r.h(1, B_PHONE, caller = BOB, callee = ALICE, life = 45_000)))
        val order = fx.filterIsInstance<CallEffect.SendSignal>().map { it.packet.op }
        assertEquals(listOf(CallOp.CANCEL, CallOp.ACCEPT), order)
    }

    @Test fun glare_inviteFromThirdPartyWhileRingingOut_isBusy() {
        val r = ringingOutRig()
        val fx = r.recv(CAROL, CallPacket.Invite(r.h(3, B_PHONE, caller = CAROL, callee = ALICE, life = 45_000)))
        assertEquals(CallOutcome.MISSED, fx.records().single().outcome); r.run { fx.packets<CallPacket.Busy>() }.single()
        assertEquals(callId(1), (r.state as CallState.RingingOut).call.id)
    }

    @Test fun glare_tieBreakIsAFixedTotalOrder() {
        val ids = listOf(0, 1, 2, 0xfffff, 0x10, 0xf)
        for (a in ids) for (b in ids) if (a != b) {
            val x = glareRig(mine = a); val fx = x.recv(BOB, CallPacket.Invite(x.h(b, B_PHONE, caller = BOB, callee = ALICE, life = 45_000)))
            val survivesMine = a < b
            assertEquals("a=$a b=$b", survivesMine, x.state is CallState.RingingOut)
            assertEquals(survivesMine, fx.isEmpty())
        }
    }

    // =========================================================== busy

    @Test fun busy_inviteWhileConnectingActiveOrRingingIn_isBusyAndRecordedMissed() {
        val states = listOf(ringingInRig(), acceptedCalleeRig(), activeCalleeRig())
        for (r in states) {
            val before = r.state
            val fx = r.recv(CAROL, CallPacket.Invite(r.h(9, CT.A_PHONE, caller = CAROL, callee = BOB, life = 45_000)))
            val b = r.run { fx.packets<CallPacket.Busy>() }.single()
            assertEquals(A_PHONE, b.to); assertEquals(CallReasons.BUSY, b.reason)
            assertEquals(CAROL, fx.filterIsInstance<CallEffect.SendSignal>().single().toIdentity)
            val rec = fx.records().single(); assertEquals(CallOutcome.MISSED, rec.outcome); assertEquals(CAROL, rec.peer); assertTrue(rec.incoming)
            assertEquals(before, r.state)
            assertTrue("repeat of the same busy invite is quiet", r.recv(CAROL, CallPacket.Invite(r.h(9, A_PHONE, caller = CAROL, callee = BOB, life = 45_000))).isEmpty())
        }
    }

    @Test fun busy_secondInviteFromSamePeerWithNewCallId_isBusyToo() {
        val r = activeCalleeRig()
        val fx = r.recv(ALICE, CallPacket.Invite(r.h(2, A_PHONE, life = 45_000)))
        r.run { fx.packets<CallPacket.Busy>() }.single(); assertTrue(r.state is CallState.Active)
    }

    // =========================================================== late / duplicate / teardown

    @Test fun ended_everyLaterPacketTimerAndPeerEvent_producesNoEffects() {
        val finals = listOf<() -> Rig>({ activeCalleeRig().also { it.send(CallEvent.HangUp) } }, { ringingOutRig().also { it.send(CallEvent.HangUp) } },
            { ringingInRig().also { it.recv(ALICE, CallPacket.Cancel(it.h(1, A_PHONE))) } })
        for (mk in finals) {
            val r = mk(); val ended = r.state; val id = callId(1)
            val late = listOf(
                CallEvent.SignalReceived(ALICE, CallPacket.Invite(r.h(1, A_PHONE))),
                CallEvent.SignalReceived(ALICE, CallPacket.Cancel(r.h(1, A_PHONE))),
                CallEvent.SignalReceived(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 0, CT.SDP)),
                CallEvent.SignalReceived(BOB, CallPacket.Accept(r.h(1, B_PHONE), A_PHONE)),
                CallEvent.SignalReceived(BOB, CallPacket.Decline(r.h(1, B_PHONE), A_PHONE)),
                CallEvent.SignalReceived(ALICE, CallPacket.End(r.h(1, A_PHONE), B_PHONE)),
                CallEvent.TimerFired(id, CallTimer.RING_TIMEOUT), CallEvent.TimerFired(id, CallTimer.CONNECT_TIMEOUT),
                CallEvent.IceServersReady(id, SERVERS), CallEvent.IceServersFailed(id), CallEvent.PeerState(id, IceState.CONNECTED),
                CallEvent.PeerState(id, IceState.FAILED), CallEvent.LocalDescription(id, 0, SdpKind.OFFER, CT.SDP),
                CallEvent.LocalCandidate(id, 0, CT.cand(1)), CallEvent.PeerFailed(id), CallEvent.HangUp, CallEvent.HangUp,
                CallEvent.AcceptIncoming, CallEvent.DeclineIncoming, CallEvent.SetMuted(true),
            )
            for (e in late) assertTrue("$e after $ended", r.send(e).isEmpty())
            assertEquals(ended, r.state)
        }
    }

    @Test fun teardown_hangUpTwice_secondIsANoOp() {
        val r = activeCalleeRig(); r.send(CallEvent.HangUp)
        assertTrue(r.send(CallEvent.HangUp).isEmpty())
        assertEquals(1, r.log.count { it is CallEffect.ClosePeerConnection })
    }

    @Test fun teardown_cancelsEveryArmedTimerAndClosesPeerOnce() {
        val r = activeCallerRig(); r.send(CallEvent.PeerState(callId(1), IceState.DISCONNECTED)); r.send(CallEvent.LocalCandidate(callId(1), 0, CT.cand(1)))
        val fx = r.send(CallEvent.HangUp)
        assertEquals(0, r.engine.stats().getValue("armedTimers")); assertEquals(1, fx.count { it is CallEffect.ClosePeerConnection })
        assertTrue(fx.cancelled().containsAll(listOf(CallTimer.ICE_RESTART, CallTimer.ICE_FLUSH)))
    }

    @Test fun staleTimerFiringAfterItWasCancelled_isIgnored() {
        val r = acceptedCalleeRig()
        r.recv(ALICE, CallPacket.Offer(r.h(1, A_PHONE), B_PHONE, 0, CT.SDP)) // cancels OFFER_WAIT
        assertTrue(r.send(CallEvent.TimerFired(callId(1), CallTimer.OFFER_WAIT)).isEmpty())
        assertTrue(r.state is CallState.Connecting)
    }

    @Test fun timerFromAnotherCall_isIgnored() {
        val r = ringingInRig()
        assertTrue(r.send(CallEvent.TimerFired(callId(9), CallTimer.RING_TIMEOUT)).isEmpty())
    }

    @Test fun lateInviteForAnEndedCall_doesNotRingAgain() {
        val r = ringingInRig(); r.recv(ALICE, CallPacket.Cancel(r.h(1, A_PHONE)))
        assertTrue(r.recv(ALICE, CallPacket.Invite(r.h(1, A_PHONE, life = 45_000))).isEmpty())
        assertTrue(r.state is CallState.Ended)
    }

    @Test fun anInviteAfterEnded_ringsAsANewCall() {
        val r = ringingInRig(); r.recv(ALICE, CallPacket.Cancel(r.h(1, A_PHONE)))
        r.recv(ALICE, CallPacket.Invite(r.h(2, A_PHONE, life = 45_000)))
        assertEquals(callId(2), (r.state as CallState.RingingIn).call.id)
    }

    @Test fun endedCallMemory_isBounded() {
        val r = Rig(BOB, B_PHONE)
        repeat(200) { n ->
            r.recv(ALICE, CallPacket.Invite(r.h(n + 1, A_PHONE, life = 45_000))); r.recv(ALICE, CallPacket.Cancel(r.h(n + 1, A_PHONE)))
            r.recv(CAROL, CallPacket.Invite(r.h(1000 + n, A_PHONE, caller = CAROL, callee = BOB, life = 45_000)))
        }
        assertTrue(r.engine.stats().getValue("recentEnded") <= 16)
    }

    @Test fun bindingMismatches_areIgnoredOnAnEstablishedCall() {
        val r = activeCalleeRig()
        val bad = listOf(
            CallEvent.SignalReceived(ALICE, CallPacket.End(r.h(1, A_PHONE, caller = CAROL), B_PHONE)),
            CallEvent.SignalReceived(CAROL, CallPacket.End(r.h(1, A_PHONE), B_PHONE)),
            CallEvent.SignalReceived(ALICE, CallPacket.End(r.h(1, A_PHONE, callee = CAROL), B_PHONE)),
            CallEvent.SignalReceived(ALICE, CallPacket.End(r.h(1, A_PHONE), B_DESK)),
            CallEvent.SignalReceived(ALICE, CallPacket.End(r.h(2, A_PHONE), B_PHONE)),
            CallEvent.SignalReceived(BOB, CallPacket.End(r.h(1, B_PHONE), B_PHONE)),
            CallEvent.SignalReceived(ALICE, CallPacket.Mute(r.h(1, B_LAP), B_PHONE, true)),
        )
        for (e in bad) assertTrue("$e", r.send(e).isEmpty())
        assertTrue(r.state is CallState.Active)
    }

    @Test fun ownDeviceEchoOfASelfSignal_isIgnored() {
        val r = ringingInRig()
        assertTrue(r.recv(BOB, CallPacket.AnsweredElsewhere(r.h(1, B_PHONE, caller = ALICE, callee = BOB), B_PHONE, "declined")).isEmpty())
        assertTrue(r.state is CallState.RingingIn)
    }

    @Test fun commandsInTheWrongState_areNoOps() {
        val r = Rig(BOB, B_PHONE)
        for (e in listOf(CallEvent.AcceptIncoming, CallEvent.DeclineIncoming, CallEvent.HangUp, CallEvent.SetMuted(true))) assertTrue("$e", r.send(e).isEmpty())
        val c = ringingOutRig(); assertTrue(c.send(CallEvent.AcceptIncoming).isEmpty()); assertTrue(c.send(CallEvent.DeclineIncoming).isEmpty())
        val i = ringingInRig(); assertTrue(i.send(CallEvent.SetMuted(true)).isEmpty())
    }

    @Test fun everyEmittedPacketIsEncodableAndPassesTheReceiverCodec() {
        val r = activeCallerRig()
        r.send(CallEvent.LocalCandidate(callId(1), 0, CT.cand(1))); r.send(CallEvent.TimerFired(callId(1), CallTimer.ICE_FLUSH)); r.send(CallEvent.SetMuted(true)); r.send(CallEvent.HangUp)
        val sends = r.log.filterIsInstance<CallEffect.SendSignal>()
        assertTrue(sends.size >= 6)
        for (s in sends) {
            val wire = CallBodyCodec.encode(s.packet)
            val res = CallBodyCodec.decode(wire, CT.ctx(s.toIdentity, ALICE, now = r.clock.now))
            assertTrue("${s.packet.op}: $res", res is CallParseResult.Ok)
        }
    }
}
