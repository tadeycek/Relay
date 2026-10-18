package com.relay.app.calculator

import org.junit.Assert.*
import org.junit.Test

class AccessPolicyTest {
    @Test fun firstCodeEnrollsAndSuccessfulUnlockResetsFailures() {
        val (enrolled, result) = AccessPolicy.submit(AccessState(), false)
        assertEquals(AccessResult.ENROLLED, result)
        assertTrue(enrolled.configured)
        val (reset, unlocked) = AccessPolicy.submit(AccessState(true, 9), true)
        assertEquals(0, reset.failures)
        assertEquals(AccessResult.UNLOCKED, unlocked)
    }

    @Test fun tenthWrongCodeInARowWipes() {
        var state = AccessState(true)
        repeat(AccessPolicy.WIPE_AFTER - 1) {
            val (next, result) = AccessPolicy.submit(state, false)
            assertEquals(AccessResult.REJECTED, result)
            state = next
        }
        assertEquals(AccessResult.WIPE, AccessPolicy.submit(state, false).second)
    }

    @Test fun aCorrectCodeStartsTheCountAgain() {
        var state = AccessState(true)
        repeat(AccessPolicy.WIPE_AFTER - 1) { state = AccessPolicy.submit(state, false).first }
        state = AccessPolicy.submit(state, true).first
        repeat(AccessPolicy.WIPE_AFTER - 1) { assertEquals(AccessResult.REJECTED, AccessPolicy.submit(state, false).also { state = it.first }.second) }
    }

    @Test fun anInterruptedWipeHappensOnTheNextWrongCode() {
        // The count is saved before wiping; if the wipe didn't run, the next wrong code wipes again.
        assertEquals(AccessResult.WIPE, AccessPolicy.submit(AccessState(true, AccessPolicy.WIPE_AFTER), false).second)
    }
}
