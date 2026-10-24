package com.relay.app.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** Output routing order: a connected headset is never silenced by the speaker toggle. */
class CallAudioTest {

    @Test fun bluetoothWinsOverEverything() {
        assertEquals(CallRoute.BLUETOOTH, CallAudio.routeFor(speakerOn = true, bluetooth = true, wired = true))
        assertEquals(CallRoute.BLUETOOTH, CallAudio.routeFor(speakerOn = false, bluetooth = true, wired = false))
    }

    @Test fun theSpeakerToggleAppliesWhenNoHeadsetIsConnected() {
        assertEquals(CallRoute.EARPIECE, CallAudio.routeFor(speakerOn = false, bluetooth = false, wired = false))
        assertEquals(CallRoute.SPEAKER, CallAudio.routeFor(speakerOn = true, bluetooth = false, wired = false))
    }

    @Test fun aWiredHeadsetIsOnlyOverriddenByExplicitlyChoosingTheSpeaker() {
        assertEquals(CallRoute.WIRED, CallAudio.routeFor(speakerOn = false, bluetooth = false, wired = true))
        assertEquals(CallRoute.SPEAKER, CallAudio.routeFor(speakerOn = true, bluetooth = false, wired = true))
    }
}
