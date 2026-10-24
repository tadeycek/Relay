package com.relay.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    @Test
    fun doublesFromTwoSecondsAndCapsAtSixtySeconds() {
        assertEquals(2_000L, Backoff.reconnectDelayMs(1))
        assertEquals(4_000L, Backoff.reconnectDelayMs(2))
        assertEquals(8_000L, Backoff.reconnectDelayMs(3))
        assertEquals(60_000L, Backoff.reconnectDelayMs(6))
        assertEquals(60_000L, Backoff.reconnectDelayMs(1000))
    }

    @Test
    fun zeroOrNegativeFailuresStillYieldTheMinimumDelay() {
        assertEquals(2_000L, Backoff.reconnectDelayMs(0))
        assertEquals(2_000L, Backoff.reconnectDelayMs(-5))
    }

    @Test
    fun neverDecreasesAndNeverExceedsCap() {
        var prev = 0L
        for (n in 0..30) {
            val d = Backoff.reconnectDelayMs(n)
            assertTrue(d >= prev)
            assertTrue(d <= 60_000L)
            prev = d
        }
    }
}
