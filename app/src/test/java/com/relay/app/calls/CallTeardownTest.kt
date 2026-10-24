package com.relay.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teardown must release the microphone and the peer connection exactly once, whatever ends the call. */
class CallTeardownTest {

    @Test fun everyReleaseRunsOnceEvenWhenEndedIsDeliveredTwice() {
        val done = mutableListOf<String>()
        val teardown = CallTeardown(
            "ringtone" to { done += "ringtone" },
            "peer" to { done += "peer" },
            "microphone" to { done += "microphone" },
        )
        assertFalse(teardown.released)
        teardown.release()
        teardown.release()
        teardown.release()
        assertEquals(listOf("ringtone", "peer", "microphone"), done)
        assertTrue(teardown.released)
    }

    @Test fun aFailingReleaseDoesNotStrandTheMicrophone() {
        val done = mutableListOf<String>()
        val teardown = CallTeardown(
            "ringtone" to { throw IllegalStateException("no ringtone") },
            "peer" to { done += "peer" },
            "microphone" to { done += "microphone" },
        )
        val failures = teardown.release()
        assertEquals(listOf("peer", "microphone"), done)
        assertEquals(listOf("ringtone"), failures)
        assertTrue(teardown.released)
    }

    @Test fun aNewCallGetsAFreshTeardownBecauseEndedIsSticky() {
        val first = CallTeardown("peer" to {})
        first.release()
        val second = CallTeardown("peer" to {})
        assertFalse(second.released)
        second.release()
        assertTrue(second.released)
    }
}
