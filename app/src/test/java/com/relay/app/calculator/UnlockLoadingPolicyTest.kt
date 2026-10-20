package com.relay.app.calculator

import org.junit.Assert.*
import org.junit.Test

class UnlockLoadingPolicyTest {
    @Test fun allTicketsHaveTheExactRequestedDistribution() {
        val counts = IntArray(5)
        repeat(UnlockLoadingPolicy.TICKETS) { counts[UnlockLoadingPolicy.slotFor(it)]++ }
        assertArrayEquals(intArrayOf(6000, 2000, 1300, 550, 150), counts)
        assertEquals(3_000L, UnlockLoadingPolicy.DURATION_MS)
    }

    @Test fun boundaryTicketsDoNotLeakBetweenScenes() {
        val boundaries = listOf(0 to 0, 5999 to 0, 6000 to 1, 7999 to 1, 8000 to 2,
            9299 to 2, 9300 to 3, 9849 to 3, 9850 to 4, 9999 to 4)
        boundaries.forEach { (ticket, expected) -> assertEquals(expected, UnlockLoadingPolicy.slotFor(ticket)) }
    }
}
