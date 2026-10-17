package com.relay.app.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingPoliciesTest {

    private val now = 1_800_000_000_000L

    @Test
    fun plausibleSenderTimestampIsKept() {
        assertEquals(now - 1_000, TimestampPolicy.resolve(now - 1_000, now))
        assertEquals(now - TimestampPolicy.MAX_AGE_MS, TimestampPolicy.resolve(now - TimestampPolicy.MAX_AGE_MS, now))
        assertEquals(now + TimestampPolicy.MAX_FUTURE_SKEW_MS, TimestampPolicy.resolve(now + TimestampPolicy.MAX_FUTURE_SKEW_MS, now))
    }

    @Test
    fun implausibleSenderTimestampFallsBackToNow() {
        assertEquals(now, TimestampPolicy.resolve(now + TimestampPolicy.MAX_FUTURE_SKEW_MS + 1, now))
        assertEquals(now, TimestampPolicy.resolve(now - TimestampPolicy.MAX_AGE_MS - 1, now))
        assertEquals(now, TimestampPolicy.resolve(Long.MAX_VALUE, now))
        assertEquals(now, TimestampPolicy.resolve(Long.MIN_VALUE, now))
        assertEquals(now, TimestampPolicy.resolve(0L, now))
    }

    @Test
    fun staleRequestBoundary() {
        assertFalse(TimestampPolicy.isStaleRequest(now - TimestampPolicy.STALE_REQUEST_MS, now))
        assertTrue(TimestampPolicy.isStaleRequest(now - TimestampPolicy.STALE_REQUEST_MS - 1, now))
        assertFalse(TimestampPolicy.isStaleRequest(now + 1_000, now))
    }

    @Test
    fun cooldownIsPerKeyAndExpires() {
        val l = CooldownLimiter(30_000)
        assertTrue(l.allow(1, 0))
        assertFalse(l.allow(1, 29_999))
        assertTrue("other key unaffected", l.allow(2, 100))
        assertTrue(l.allow(1, 30_000))
        assertFalse(l.allow(1, 30_001))
    }

    @Test
    fun slidingWindowLimitsBurstsThenRecovers() {
        val l = SlidingWindowLimiter(maxEvents = 3, windowMs = 1_000)
        assertTrue(l.tryAcquire(0))
        assertTrue(l.tryAcquire(100))
        assertTrue(l.tryAcquire(200))
        assertFalse(l.tryAcquire(300))
        assertFalse(l.tryAcquire(999))
        assertTrue("first event slid out of the window", l.tryAcquire(1_000))
        assertFalse(l.tryAcquire(1_050))
    }
}
