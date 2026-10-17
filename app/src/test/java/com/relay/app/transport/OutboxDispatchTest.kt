package com.relay.app.transport

import com.relay.app.transport.outbox.OutboxDispatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OutboxDispatchTest {
    @Test fun slowRecipientDoesNotBlockOtherChatsAndEachRecipientsOrderSurvives() = runTest {
        val completed = mutableListOf<String>()
        val entries = listOf("slow:1", "slow:2", "fast:1", "fast:2")
        val count = OutboxDispatch.run(entries, { it.substringBefore(':') }) {
            if (it.startsWith("slow")) delay(5000) else delay(1)
            completed += it
            1
        }
        assertEquals(4, count)
        assertEquals(listOf("fast:1", "fast:2", "slow:1", "slow:2"), completed)
    }
    @Test fun concurrencyIsBoundedAndFailedEntriesDoNotInflateDeliveredCount() = runTest {
        var running = 0
        var peak = 0
        val delivered = OutboxDispatch.run((0 until 20).toList(), { it.toString() }) {
            running++; peak = maxOf(peak, running); delay(100); running--
            if (it % 2 == 0) 1 else 0
        }
        assertEquals(4, peak)
        assertEquals(10, delivered)
    }
}
