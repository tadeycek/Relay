package com.relay.app.calls

import com.relay.app.call.CallBodyCodec
import com.relay.app.call.CallHeader
import com.relay.app.call.CallOp
import com.relay.app.call.CallOutcome
import com.relay.app.call.CallPacket
import com.relay.app.call.CallParseResult
import com.relay.app.call.CallReasons
import com.relay.app.call.CallRejectReason
import com.relay.app.call.CallState
import com.relay.app.call.CallTimer
import com.relay.app.call.IceState
import com.relay.app.call.SignalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host contract from `CALL_ENGINE.md`, exercised through the real `CallRuntime`, the real codec
 * and the real engine, with the platform as recording fakes. This is the part of WP13 that must not
 * be a thin untested wrapper.
 */
class CallHostCoreTest {

    private val self = "a1".repeat(32)
    private val peer = "b2".repeat(32)
    private val stranger = "c3".repeat(32)
    private val selfDevice = "primary"
    private val peerDevice = "22".repeat(16)
    private val callId = "00000000000000000000000000000001"

    private val clock = TestClock()
    private val signal = RecordingSignal()
    private val turn = RecordingTurn()
    private val peerPort = RecordingPeer()
    private val timers = RecordingTimers()
    private val ui = RecordingUi()

    private fun host(allowed: (String) -> Boolean = { it == peer || it == self }) = CallHostCore(
        selfPubkey = self,
        deviceId = selfDevice,
        clock = clock,
        random = SeqRandom(),
        signal = signal,
        turn = turn,
        peer = peerPort,
        timers = timers,
        ui = ui,
        isAllowedPeer = allowed,
    )

    private fun wire(packet: CallPacket) = CallBodyCodec.encode(packet)

    private fun header(
        call: String = callId,
        caller: String = peer,
        callee: String = self,
        device: String = peerDevice,
        ts: Long = clock.now,
        expires: Long = ts + 45_000,
        nonce: String = "01".repeat(16),
    ) = CallHeader(call, ts, expires, caller, callee, device, nonce)

    private fun invite(call: String = callId, ts: Long = clock.now, expires: Long = ts + 45_000, nonce: String = "01".repeat(16)) =
        wire(CallPacket.Invite(header(call = call, ts = ts, expires = expires, nonce = nonce)))

    @Test fun dialSendsOneUrgentStoredInviteAndShowsCalling() {
        val h = host()
        h.startCall(peer)
        assertEquals(listOf(CallOp.INVITE.wire), signal.ops())
        assertEquals(SignalMode.LIVE_AND_STORED_URGENT, signal.sent.single().mode)
        assertEquals(peer, signal.sent.single().to)
        assertEquals(callId, signal.sent.single().call)
        val state = ui.lastState()
        assertTrue(state is CallState.RingingOut)
        assertEquals(callId, (state as CallState.RingingOut).call.id)
        assertEquals(45_000L, timers.armed[callId to CallTimer.RING_TIMEOUT])
    }

    @Test fun aResentInviteIsLiveOnlySoOnlyTheFirstIsStored() {
        val h = host()
        h.startCall(peer)
        clock.now += 5_000
        timers.fire(callId, CallTimer.INVITE_RESEND)
        val invites = signal.sent.filter { it.op == CallOp.INVITE.wire }
        assertEquals(2, invites.size)
        assertEquals(SignalMode.LIVE_AND_STORED_URGENT, invites[0].mode)
        assertEquals(SignalMode.LIVE, invites[1].mode)
        // The resend keeps the original expiry so ringing is never extended.
        assertEquals(invites[0].json.getLong("expires"), invites[1].json.getLong("expires"))
    }

    @Test fun anInviteOlderThanItsExpiryNeverRings() {
        val h = host()
        clock.now = 1_800_000_000_000
        // A stored delivery opened from the inbox long after the caller gave up.
        val stale = invite(ts = clock.now - 120_000, expires = clock.now - 60_000)
        val result = h.onWire(peer, stale)
        assertEquals(CallParseResult.Rejected(CallRejectReason.EXPIRED), result)
        assertTrue(ui.rings.isEmpty())
        assertTrue(ui.states.isEmpty())
    }

    @Test fun aFreshInviteRingsAndTellsTheCallerItIsRinging() {
        val h = host()
        val result = h.onWire(peer, invite())
        assertTrue(result is CallParseResult.Ok)
        assertEquals(listOf(callId to true), ui.rings)
        assertEquals(listOf(CallOp.RINGING.wire), signal.ops())
        // The ringing hint goes to the caller's device, not the identity.
        assertEquals(peerDevice, signal.sent.single().json.getString("to"))
        assertTrue(ui.lastState() is CallState.RingingIn)
    }

    @Test fun anInviteFromAnUnverifiedSenderIsDroppedBeforeTheEngine() {
        val h = host()
        val foreign = wire(CallPacket.Invite(header(caller = stranger, callee = self)))
        assertNull(h.onWire(stranger, foreign))
        assertTrue(ui.rings.isEmpty())
        assertTrue(ui.states.isEmpty())
    }

    @Test fun aSecondInviteDuringACallGetsBusyAndA_MISSED_Record() {
        val h = host()
        // A call is already up on this device.
        h.onWire(peer, invite())
        h.accept()
        turn.ready(callId)
        peerPort.peerState(callId, IceState.CONNECTED)
        assertTrue(ui.lastState() is CallState.Active)
        val other = "00000000000000000000000000000002"
        // The same peer invites this device to a second call while the first is active.
        h.onWire(peer, wire(CallPacket.Invite(header(call = other, caller = peer, callee = self, device = peerDevice, nonce = "02".repeat(16)))))
        assertEquals(CallOp.BUSY.wire, signal.sent.last().op)
        assertEquals(peerDevice, signal.sent.last().json.getString("to"))
        val missed = ui.records.filter { it.outcome == CallOutcome.MISSED }
        assertEquals(1, missed.size)
        assertEquals(other, missed.single().callId)
        assertEquals(CallReasons.BUSY, missed.single().reason)
        // Busy must not end the call that is already running.
        assertTrue(ui.lastState() is CallState.Active)
    }

    @Test fun answeredElsewhereStopsTheRingingDeviceSilently() {
        val h = host()
        h.onWire(peer, invite())
        assertTrue(ui.lastState() is CallState.RingingIn)
        val winner = "33333333333333333333333333333333"
        val aew = wire(CallPacket.AnsweredElsewhere(header(nonce = "02".repeat(16)), winner))
        h.onWire(peer, aew)
        val ended = ui.lastState() as CallState.Ended
        assertEquals(CallOutcome.ANSWERED_ELSEWHERE, ended.outcome)
        assertEquals(listOf(callId), ui.stoppedRings)
        assertTrue("answered elsewhere is silent (LP-D10)", ui.records.isEmpty())
    }

    @Test fun decliningTellsTheCallerAndThisIdentitysOtherDevices() {
        val h = host()
        h.onWire(peer, invite())
        h.decline()
        // The first op is the ringing hint that answered the invite; then the decline pair.
        assertEquals(listOf(CallOp.DECLINE.wire, CallOp.ANSWERED_ELSEWHERE.wire), signal.ops().takeLast(2))
        assertEquals(peerDevice, signal.sent[signal.sent.size - 2].json.getString("to"))
        assertEquals(self, signal.sent.last().to)
        val record = ui.records.single()
        assertEquals(CallOutcome.DECLINED, record.outcome)
    }

    @Test fun anUnavailableTurnServiceEndsTheCallAsFailedWithAReason() {
        val h = host()
        h.onWire(peer, invite())
        h.accept()
        assertEquals(listOf(callId), turn.requested)
        turn.failed(callId)
        val ended = ui.lastState() as CallState.Ended
        assertEquals(CallOutcome.FAILED, ended.outcome)
        assertEquals(CallReasons.TURN_FAILED, ended.reason)
        assertEquals(ended.reason, ui.records.single().reason)
        assertEquals("Couldn't reach the call service", CallUiModelFactory.failureText(ended.outcome, ended.reason))
    }

    @Test fun anUnreachablePeerFailsTheCallAndClosesOnce() {
        val h = host()
        h.onWire(peer, invite())
        h.accept()
        turn.ready(callId)
        assertEquals(listOf(callId), peerPort.opened)
        peerPort.peerState(callId, IceState.FAILED)
        val ended = ui.lastState() as CallState.Ended
        assertEquals(CallOutcome.FAILED, ended.outcome)
        assertEquals(CallReasons.ICE_FAILED, ended.reason)
        assertEquals(listOf(callId), peerPort.closed)
        assertEquals("The other person couldn't be reached", CallUiModelFactory.failureText(ended.outcome, ended.reason))
    }

    @Test fun hangingUpReleasesThePeerConnectionExactlyOnce() {
        val h = host()
        h.onWire(peer, invite())
        h.accept()
        turn.ready(callId)
        h.hangUp()
        assertTrue(ui.lastState() is CallState.Ended)
        assertEquals(listOf(callId), peerPort.closed)
        h.hangUp()
        assertEquals("a second hang-up must not release twice", listOf(callId), peerPort.closed)
    }

    @Test fun muteReachesTheTrackAndIsSentToThePeer() {
        val h = host()
        h.onWire(peer, invite())
        h.accept()
        turn.ready(callId)
        peerPort.peerState(callId, IceState.CONNECTED)
        h.setMuted(true)
        assertEquals(true, peerPort.micMuted)
        assertEquals(CallOp.MUTE.wire, signal.sent.last().op)
    }

    @Test fun aCallBodyIsRecognisedAndA_PlainMessageIsNot() {
        assertTrue(CallHostCore.isCallBody(invite()))
        assertFalse(CallHostCore.isCallBody("hello"))
        assertFalse(CallHostCore.isCallBody("TYPE:FILE|J:e30="))
    }

    @Test fun startCallIsAcceptedFromEndedAndGetsAFreshCallId() {
        val h = host()
        h.startCall(peer)
        val first = (ui.lastState() as CallState.RingingOut).call.id
        h.hangUp()
        assertTrue(h.state is CallState.Ended)
        h.startCall(peer)
        val second = h.state
        assertTrue("expected a new outgoing call, was $second", second is CallState.RingingOut)
        assertTrue((second as CallState.RingingOut).call.id != first)
        assertTrue(ui.rejected.isEmpty())
    }

    @Test fun dialingDuringALiveCallIsRejectedAndLeavesTheCallRunning() {
        val h = host()
        h.startCall(peer)
        val sentBefore = signal.sent.size
        h.startCall(stranger)
        assertEquals(listOf(com.relay.app.call.CallReasons.BUSY_SELF), ui.rejected)
        assertEquals(sentBefore, signal.sent.size)
        assertTrue(h.state is CallState.RingingOut)
    }
}
