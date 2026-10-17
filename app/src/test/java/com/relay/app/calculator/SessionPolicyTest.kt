package com.relay.app.calculator

import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    @Test fun fixedTenMinuteSessionExpiresAtItsDeadline() {
        val start = 123_000L
        val deadline = start + SessionPolicy.DURATION_MS
        assertEquals(600_000L, SessionPolicy.remainingMs(deadline, start))
        assertEquals(1L, SessionPolicy.remainingMs(deadline, deadline - 1))
        assertEquals(0L, SessionPolicy.remainingMs(deadline, deadline))
        assertEquals(0L, SessionPolicy.remainingMs(deadline, deadline + 1))
    }

    @Test fun photoPickerRetainsChatOnlyInsideTheOriginalSession() {
        val deadline = 800000L
        assertTrue(SessionPolicy.retainChatForPicker(true, deadline, 700000L))
        assertFalse(SessionPolicy.retainChatForPicker(false, deadline, 700000L))
        assertFalse(SessionPolicy.retainChatForPicker(true, deadline, deadline))
        assertFalse(SessionPolicy.retainChatForPicker(true, 0, 700000L))
        assertEquals(100000L, SessionPolicy.remainingMs(deadline, 700000L))
    }

    @Test fun checkingOrReturningToASessionDoesNotExtendIt() {
        val deadline = 800_000L
        assertEquals(300_000L, SessionPolicy.remainingMs(deadline, 500_000L))
        assertEquals(100_000L, SessionPolicy.remainingMs(deadline, 700_000L))
        assertEquals(0L, SessionPolicy.remainingMs(0, 700_000L))
        assertEquals(0L, SessionPolicy.remainingMs(deadline, 199_999L))
    }
}
