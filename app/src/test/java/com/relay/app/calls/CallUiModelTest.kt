package com.relay.app.calls

import com.relay.app.call.CallInfo
import com.relay.app.call.CallOutcome
import com.relay.app.call.CallState
import com.relay.app.call.CallReasons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen state for the call UI: pure, so the buttons and the honest failure text are testable. */
class CallUiModelTest {

    private val info = CallInfo(id = "0123456789abcdef0123456789abcdef", peer = "a1".repeat(32), incoming = true)
    private fun model(state: CallState) = CallUiModelFactory.from(state, muted = false, speakerOn = false, nowMs = 1_800_000_030_000, peerName = "Alice", previewsHidden = false)

    @Test fun incomingShowsOnlyAnswerAndDecline() {
        val m = model(CallState.RingingIn(info, "22222222222222222222222222222222"))!!
        assertEquals(CallStage.INCOMING, m.stage)
        assertEquals("Alice", m.title)
        assertEquals("Incoming call", m.subtitle)
        assertTrue(m.canAccept)
        assertTrue(m.canDecline)
        assertFalse(m.canCancel)
        assertFalse(m.canMute)
    }

    @Test fun hiddenPreviewsKeepTheCallerOutOfTheLockScreen() {
        val m = CallUiModelFactory.from(CallState.RingingIn(info, "22".repeat(16)), muted = false, speakerOn = false, nowMs = 0, peerName = "Alice", previewsHidden = true)!!
        assertEquals("Incoming call", m.title)
        assertFalse(m.title.contains("Alice"))
    }

    @Test fun outgoingShowsCallingThenRingingWithCancelOnly() {
        val calling = model(CallState.RingingOut(info.copy(incoming = false), calleeRinging = false))!!
        assertEquals(CallStage.OUTGOING, calling.stage)
        assertEquals("Calling…", calling.subtitle)
        assertTrue(calling.canCancel)
        assertFalse(calling.canAccept)
        val ringing = model(CallState.RingingOut(info.copy(incoming = false), calleeRinging = true))!!
        assertEquals("Ringing…", ringing.subtitle)
    }

    @Test fun connectingThenActiveShowsADurationAndTheInCallControls() {
        val connecting = model(CallState.Connecting(info, "22".repeat(16)))!!
        assertEquals(CallStage.CONNECTING, connecting.stage)
        assertEquals("Connecting…", connecting.subtitle)
        assertTrue(connecting.canHangUp)

        val active = model(CallState.Active(info, "22".repeat(16), restarting = false, remoteMuted = false, connectedAtMs = 1_800_000_000_000))!!
        assertEquals(CallStage.ACTIVE, active.stage)
        assertEquals("0:30", active.subtitle)
        assertTrue(active.canMute)
        assertTrue(active.canHangUp)
        assertFalse(active.canAccept)
    }

    @Test fun durationsReadAsMinutesAndHours() {
        assertEquals("0:00", CallUiModelFactory.duration(0))
        assertEquals("0:07", CallUiModelFactory.duration(7_400))
        assertEquals("12:34", CallUiModelFactory.duration(754_000))
        assertEquals("1:02:03", CallUiModelFactory.duration(3_723_000))
    }

    @Test fun aRestartingCallSaysItIsReconnecting() {
        val m = model(CallState.Active(info, "22".repeat(16), restarting = true, remoteMuted = false, connectedAtMs = 1_800_000_000_000))!!
        assertEquals("Reconnecting…", m.subtitle)
        assertEquals(CallStage.ACTIVE, m.stage)
    }

    @Test fun failureStatesSayWhatWentWrongInsteadOfHanging() {
        assertEquals("Couldn't reach the call service", CallUiModelFactory.failureText(CallOutcome.FAILED, CallReasons.TURN_FAILED))
        assertEquals("The other person couldn't be reached", CallUiModelFactory.failureText(CallOutcome.FAILED, CallReasons.CONNECT_TIMEOUT))
        assertEquals("The other person couldn't be reached", CallUiModelFactory.failureText(CallOutcome.FAILED, CallReasons.ICE_FAILED))
        assertEquals("The connection was lost", CallUiModelFactory.failureText(CallOutcome.FAILED, CallReasons.RESTART_TIMEOUT))
        assertEquals("The call failed on this device", CallUiModelFactory.failureText(CallOutcome.FAILED, CallReasons.PEER_ERROR))
        assertEquals("Call failed", CallUiModelFactory.failureText(CallOutcome.FAILED, "something_new"))
        assertNull(CallUiModelFactory.failureText(CallOutcome.COMPLETED, CallReasons.HANGUP))
        assertNull(CallUiModelFactory.failureText(CallOutcome.MISSED, null))
    }

    @Test fun anEndedCallKeepsTheLastPeerSoTheScreenCanStillNameThem() {
        val m = model(CallState.Ended(info, CallOutcome.NO_ANSWER, CallReasons.NO_ANSWER))!!
        assertEquals(CallStage.ENDED, m.stage)
        assertEquals("a1".repeat(32), m.peerKey)
        assertEquals("No answer", m.title)
        assertFalse(m.canHangUp)
    }

    @Test fun idleHasNoScreen() {
        assertNull(CallUiModelFactory.from(CallState.Idle, muted = false, speakerOn = false, nowMs = 0, peerName = null, previewsHidden = false))
    }

    @Test fun mutedAndSpeakerAreCarriedThroughToTheActiveScreen() {
        val m = CallUiModelFactory.from(CallState.Active(info, "22".repeat(16), false, false, 1_800_000_000_000), muted = true, speakerOn = true, nowMs = 1_800_000_001_000, peerName = null, previewsHidden = false)!!
        assertTrue(m.muted)
        assertTrue(m.speakerOn)
        assertEquals("Call in progress", m.title)
    }
}
