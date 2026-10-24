package com.relay.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What may start or answer a call on this device, decided without Android. */
class CallGuardTest {

    @Test fun aCallStartsWhenTheMicrophoneIsAllowedAndNothingElseIsHappening() {
        assertEquals(CallStartBlock.NONE, CallGuard.startBlock(micGranted = true, otherCallLive = false, online = true, callsEnabled = true))
        assertNull(CallGuard.startMessage(CallStartBlock.NONE))
    }

    @Test fun aRefusedMicrophoneBlocksTheCallInsteadOfOpeningASilentOne() {
        assertEquals(CallStartBlock.MIC_DENIED, CallGuard.startBlock(micGranted = false, otherCallLive = false, online = true, callsEnabled = true))
        assertEquals(CallStartBlock.MIC_DENIED, CallGuard.answerBlock(micGranted = false, callsEnabled = true))
        assertEquals("Microphone access is needed for calls. Allow it in Settings.", CallGuard.startMessage(CallStartBlock.MIC_DENIED))
    }

    @Test fun onlyOneCallAtATimeAndNeverWithoutANetwork() {
        assertEquals(CallStartBlock.BUSY, CallGuard.startBlock(micGranted = true, otherCallLive = true, online = true, callsEnabled = true))
        assertEquals(CallStartBlock.OFFLINE, CallGuard.startBlock(micGranted = true, otherCallLive = false, online = false, callsEnabled = true))
        assertEquals(CallStartBlock.PROFILE_LOCKED, CallGuard.startBlock(micGranted = true, otherCallLive = false, online = true, callsEnabled = false))
    }

    @Test fun answeringIsRefusedWhileCalculatorOnlySoTheDisguiseNeverLeaksACall() {
        assertEquals(CallStartBlock.PROFILE_LOCKED, CallGuard.answerBlock(micGranted = true, callsEnabled = false))
        assertEquals(CallStartBlock.NONE, CallGuard.answerBlock(micGranted = true, callsEnabled = true))
    }
}
