package com.relay.app.transport

import com.relay.app.transport.outbox.OutboxEntry
import com.relay.app.transport.outbox.OutboxPolicy
import com.relay.app.transport.outbox.OutboxState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxPolicyTest {

    @Test
    fun backoffDoublesThenCaps() {
        assertEquals(0L, OutboxPolicy.nextDelayMs(0))
        assertEquals(5_000L, OutboxPolicy.nextDelayMs(1))
        assertEquals(10_000L, OutboxPolicy.nextDelayMs(2))
        assertEquals(20_000L, OutboxPolicy.nextDelayMs(3))
        assertEquals(OutboxPolicy.MAX_DELAY_MS, OutboxPolicy.nextDelayMs(50))
        assertEquals(OutboxPolicy.MAX_DELAY_MS, OutboxPolicy.nextDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun backoffNeverDecreases() {
        var prev = 0L
        for (n in 0..40) {
            val d = OutboxPolicy.nextDelayMs(n)
            assertTrue("attempt $n", d >= prev)
            prev = d
        }
    }

    @Test
    fun expiresAfterSevenDays() {
        val created = 1_000L
        assertFalse(OutboxPolicy.isExpired(created, created + OutboxPolicy.GIVE_UP_AFTER_MS - 1))
        assertTrue(OutboxPolicy.isExpired(created, created + OutboxPolicy.GIVE_UP_AFTER_MS))
    }

    @Test
    fun dueOnlyWhenQueuedAndTimeReached() {
        val e = OutboxEntry(contactId = 1, recipientPubkeyHex = "k", payloadId = "p", payloadJson = "{}", createdAt = 0, nextAttemptAt = 100)
        assertFalse(OutboxPolicy.isDue(e, 99))
        assertTrue(OutboxPolicy.isDue(e, 100))
        assertFalse(OutboxPolicy.isDue(e.copy(state = OutboxState.SENT), 1000))
        assertFalse(OutboxPolicy.isDue(e.copy(state = OutboxState.FAILED), 1000))
    }

    @Test
    fun stateRoundTripsAndUnknownDegradesToQueued() {
        for (s in OutboxState.entries) assertEquals(s, OutboxState.fromDb(s.dbValue))
        assertEquals(OutboxState.QUEUED, OutboxState.fromDb(99))
    }
}
