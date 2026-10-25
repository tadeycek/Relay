package com.relay.app.calls

import com.relay.app.call.CallInfo
import com.relay.app.call.CallOutcome
import com.relay.app.call.CallState
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP13 leftovers: audio focus rule, peer name in every live state, mic request flow, FGS types, call eligibility. */
class CallWp13LeftoversTest {

    private val out = CallInfo(id = "0123456789abcdef0123456789abcdef", peer = "a1".repeat(32), incoming = false)
    private val inc = out.copy(incoming = true)
    private val dev = "22".repeat(16)

    // ---- (1) audio focus -----------------------------------------------------------------------

    @Test fun platformFocusConstantsMapToEvents() {
        assertEquals(FocusEvent.GAIN, CallFocusPolicy.eventFor(1))
        assertEquals(FocusEvent.LOSS, CallFocusPolicy.eventFor(-1))
        assertEquals(FocusEvent.LOSS_TRANSIENT, CallFocusPolicy.eventFor(-2))
        assertEquals(FocusEvent.LOSS_TRANSIENT_CAN_DUCK, CallFocusPolicy.eventFor(-3))
        assertNull(CallFocusPolicy.eventFor(0))
    }

    @Test fun transientLossDuringALiveCallMutesUntilFocusReturns() {
        assertEquals(FocusAction.MUTE_UNTIL_REGAINED, CallFocusPolicy.decide(FocusEvent.LOSS_TRANSIENT, callLive = true, autoMuted = false))
    }

    @Test fun permanentLossDuringALiveCallMutesButNeverEndsTheCall() {
        val action = CallFocusPolicy.decide(FocusEvent.LOSS, callLive = true, autoMuted = false)
        assertEquals(FocusAction.MUTE_UNTIL_USER_UNMUTES, action)
    }

    @Test fun duckingIsIgnoredBecauseAVoiceCallStaysAudible() {
        assertEquals(FocusAction.NONE, CallFocusPolicy.decide(FocusEvent.LOSS_TRANSIENT_CAN_DUCK, callLive = true, autoMuted = false))
    }

    @Test fun lossBeforeMediaStartsDoesNothing() {
        assertEquals(FocusAction.NONE, CallFocusPolicy.decide(FocusEvent.LOSS, callLive = false, autoMuted = false))
        assertEquals(FocusAction.NONE, CallFocusPolicy.decide(FocusEvent.LOSS_TRANSIENT, callLive = false, autoMuted = false))
    }

    @Test fun gainUnmutesOnlyWhatTheFocusRuleMuted() {
        assertEquals(FocusAction.UNMUTE, CallFocusPolicy.decide(FocusEvent.GAIN, callLive = true, autoMuted = true))
        assertEquals(FocusAction.NONE, CallFocusPolicy.decide(FocusEvent.GAIN, callLive = true, autoMuted = false))
    }

    // ---- (2) peer name in connecting/active ----------------------------------------------------

    @Test fun peerIsKnownInEveryLiveState() {
        val peer = out.peer
        assertEquals(peer, CallPeer.of(CallState.RingingIn(inc, dev)))
        assertEquals(peer, CallPeer.of(CallState.RingingOut(out, false)))
        assertEquals(peer, CallPeer.of(CallState.Connecting(out, dev)))
        assertEquals(peer, CallPeer.of(CallState.Active(out, dev, false, false, 0L)))
        assertNull(CallPeer.of(CallState.Idle))
        assertNull(CallPeer.of(CallState.Ended(out, CallOutcome.COMPLETED, null)))
    }

    @Test fun connectingAndActiveKeepTheNameUnlessPreviewsAreHidden() {
        val connecting = CallState.Connecting(out, dev)
        val active = CallState.Active(out, dev, false, false, 1_000L)
        fun title(s: CallState, hidden: Boolean) =
            CallUiModelFactory.from(s, muted = false, speakerOn = false, nowMs = 2_000L, peerName = "Alice", previewsHidden = hidden)!!.title
        assertEquals("Alice", title(connecting, false))
        assertEquals("Alice", title(active, false))
        assertEquals("Call in progress", title(connecting, true))
        assertEquals("Call in progress", title(active, true))
    }

    @Test fun outgoingRingingAlsoHidesTheNameWhenPreviewsAreHidden() {
        val m = CallUiModelFactory.from(CallState.RingingOut(out, false), muted = false, speakerOn = false, nowMs = 0, peerName = "Alice", previewsHidden = true)!!
        assertFalse(m.title.contains("Alice"))
    }

    // ---- (3) outgoing mic permission flow -------------------------------------------------------

    private class Recorder(var mic: Boolean) {
        val log = mutableListOf<String>()
        val starter = OutgoingCallStarter(
            hasMic = { mic },
            requestMic = { log += "request" },
            place = { pubkey, name -> log += "place:$pubkey:$name" },
            denied = { log += "denied" },
        )
    }

    @Test fun grantedMicPlacesTheCallWithoutAsking() {
        val r = Recorder(mic = true)
        r.starter.start("pk", "Alice")
        assertEquals(listOf("place:pk:Alice"), r.log)
    }

    @Test fun missingMicAsksFirstAndPlacesOnlyAfterTheGrant() {
        val r = Recorder(mic = false)
        r.starter.start("pk", "Alice")
        assertEquals(listOf("request"), r.log)
        r.mic = true
        r.starter.onResult(granted = true)
        assertEquals(listOf("request", "place:pk:Alice"), r.log)
    }

    @Test fun refusedMicNeverPlacesTheCallAndReportsDenial() {
        val r = Recorder(mic = false)
        r.starter.start("pk", "Alice")
        r.starter.onResult(granted = false)
        assertEquals(listOf("request", "denied"), r.log)
    }

    @Test fun aStrayPermissionResultPlacesNothing() {
        val r = Recorder(mic = false)
        r.starter.onResult(granted = true)
        assertTrue(r.log.isEmpty())
    }

    @Test fun aGrantIsUsedOnceOnly() {
        val r = Recorder(mic = false)
        r.starter.start("pk", null)
        r.starter.onResult(granted = true)
        r.starter.onResult(granted = true)
        assertEquals(listOf("request", "place:pk:null"), r.log)
    }

    // ---- (4) foreground-service types ----------------------------------------------------------

    @Test fun ringingStartsWithPhoneCallOnlyEvenWhenTheMicIsGranted() {
        assertEquals(listOf(FgsType.PHONE_CALL), CallServicePolicy.typesFor(sdk = 34, stage = ServiceStage.RINGING, micGranted = true))
        assertEquals(listOf(FgsType.PHONE_CALL), CallServicePolicy.typesFor(sdk = 34, stage = ServiceStage.RINGING, micGranted = false))
    }

    @Test fun microphoneIsAddedOnlyAfterAcceptAndOnlyWithThePermission() {
        assertEquals(listOf(FgsType.PHONE_CALL, FgsType.MICROPHONE), CallServicePolicy.typesFor(34, ServiceStage.IN_CALL, true))
        assertEquals(listOf(FgsType.PHONE_CALL), CallServicePolicy.typesFor(34, ServiceStage.IN_CALL, false))
    }

    @Test fun olderAndroidGetsTheTypesItUnderstands() {
        assertEquals(emptyList<FgsType>(), CallServicePolicy.typesFor(28, ServiceStage.IN_CALL, true))
        assertEquals(listOf(FgsType.PHONE_CALL), CallServicePolicy.typesFor(29, ServiceStage.IN_CALL, true))
        assertEquals(listOf(FgsType.PHONE_CALL, FgsType.MICROPHONE), CallServicePolicy.typesFor(30, ServiceStage.IN_CALL, true))
    }

    @Test fun stageFollowsTheCallState() {
        assertEquals(ServiceStage.RINGING, CallServicePolicy.stageFor(CallState.RingingIn(inc, dev)))
        assertEquals(ServiceStage.RINGING, CallServicePolicy.stageFor(CallState.RingingOut(out, true)))
        assertEquals(ServiceStage.IN_CALL, CallServicePolicy.stageFor(CallState.Connecting(out, dev)))
        assertEquals(ServiceStage.IN_CALL, CallServicePolicy.stageFor(CallState.Active(out, dev, false, false, 0L)))
        assertNull(CallServicePolicy.stageFor(CallState.Idle))
    }

    // ---- (6) who may be called -----------------------------------------------------------------

    private fun contact(
        pubkey: String? = "a1".repeat(32),
        verified: Boolean = true,
        trust: ContactTrustLevel = ContactTrustLevel.TRUSTED,
        deleted: Boolean = false,
    ) = Contact(name = "Alice", phone = "x", nostrPubkey = pubkey, qrVerified = verified, trustLevel = trust, deletedAt = if (deleted) 1L else null)

    @Test fun aVerifiedUnblockedContactCanBeCalled() = assertTrue(CallEligibility.canCall(contact()))
    @Test fun anUnverifiedContactCannotBeCalled() = assertFalse(CallEligibility.canCall(contact(verified = false)))
    @Test fun aBlockedContactCannotBeCalled() = assertFalse(CallEligibility.canCall(contact(trust = ContactTrustLevel.BLOCKED)))
    @Test fun aDeletedContactCannotBeCalled() = assertFalse(CallEligibility.canCall(contact(deleted = true)))
    @Test fun aContactWithoutAKeyCannotBeCalled() = assertFalse(CallEligibility.canCall(contact(pubkey = null)))
    @Test fun noContactMeansNoButton() = assertFalse(CallEligibility.canCall(null))
}
