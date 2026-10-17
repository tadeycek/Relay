package com.relay.app.calculator

import org.junit.Assert.*
import org.junit.Test

class AccessPolicyTest {
    @Test fun firstCodeEnrollsAndSuccessfulUnlockResetsFailures() {
        val (enrolled, result) = AccessPolicy.submit(AccessState(), false)
        assertEquals(AccessResult.ENROLLED, result)
        assertTrue(enrolled.configured)
        val (reset, unlocked) = AccessPolicy.submit(AccessState(true, 2), true)
        assertEquals(0, reset.failures)
        assertEquals(AccessResult.UNLOCKED, unlocked)
    }

    @Test fun thirdFailureLocksAndCorrectCodeCannotBypassIt() {
        var state = AccessState(true)
        repeat(2) { state = AccessPolicy.submit(state, false).first; assertFalse(state.locked) }
        val (locked, result) = AccessPolicy.submit(state, false)
        assertTrue(locked.locked)
        assertEquals(AccessResult.LOCKED, result)
        assertEquals(locked to AccessResult.LOCKED, AccessPolicy.submit(locked, true))
    }

    @Test fun oldOwnerUnlockCannotClearANewerLockout() {
        assertFalse(AccessPolicy.canApplyOwnerUnlock(12, 11))
        assertFalse(AccessPolicy.canApplyOwnerUnlock(0, 100))
        assertTrue(AccessPolicy.canApplyOwnerUnlock(12, 12))
        assertTrue(AccessPolicy.canApplyOwnerUnlock(12, 15))
    }
}
