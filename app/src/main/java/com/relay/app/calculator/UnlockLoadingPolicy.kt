package com.relay.app.calculator

import kotlin.random.Random

/** Integer tickets keep the requested 60/20/13/5.5/1.5 percentages exact. */
object UnlockLoadingPolicy {
    const val DURATION_MS = 3_000L
    const val TICKETS = 10_000
    fun pick(): Int = slotFor(Random.Default.nextInt(TICKETS))
    fun slotFor(ticket: Int): Int {
        require(ticket in 0 until TICKETS)
        return when {
            ticket < 6_000 -> 0
            ticket < 8_000 -> 1
            ticket < 9_300 -> 2
            ticket < 9_850 -> 3
            else -> 4
        }
    }
}
