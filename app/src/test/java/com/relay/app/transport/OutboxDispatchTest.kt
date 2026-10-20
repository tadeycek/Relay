package com.relay.app.transport

import com.relay.app.transport.outbox.OutboxDispatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxDispatchTest {
    @Test fun slowRecipientDoesNotDelayWorkQueuedLaterForOthersAndKeepsItsOwnOrder() = runTest {
        val completed = mutableListOf<String>()
        var lanesDone = 0
        val dispatch = OutboxDispatch(this)
        val deliver: suspend (String) -> Boolean = {
            if (it.startsWith("slow")) delay(5000) else delay(1)
            completed += it
            true
        }
        dispatch.offer(listOf("slow:1", "slow:2", "fast:1"), { it.substringBefore(':') }, deliver) { lanesDone++ }
        advanceTimeBy(10); runCurrent()
        assertEquals(listOf("fast:1"), completed)
        assertEquals(setOf("slow"), dispatch.busy())

        // Queued while the slow lane is still sending: it starts at once, and the busy lane isn't doubled.
        assertEquals(1, dispatch.offer(listOf("slow:1", "other:1"), { it.substringBefore(':') }, deliver) { lanesDone++ })
        advanceTimeBy(10); runCurrent()
        assertEquals(listOf("fast:1", "other:1"), completed)

        advanceUntilIdle()
        assertEquals(listOf("fast:1", "other:1", "slow:1", "slow:2"), completed)
        assertEquals(3, lanesDone)
        assertTrue(dispatch.busy().isEmpty())
    }

    @Test fun concurrencyIsBoundedAcrossLanes() = runTest {
        var running = 0
        var peak = 0
        OutboxDispatch(this).offer((0 until 20).toList(), { it.toString() }, {
            running++; peak = maxOf(peak, running); delay(100); running--
            true
        }) {}
        advanceUntilIdle()
        assertEquals(4, peak)
    }

    @Test fun retryableFailureOrExceptionEndsOnlyThatLane() = runTest {
        val attempted = mutableListOf<String>()
        OutboxDispatch(this).offer(listOf("a:1", "a:2", "b:1", "b:2", "c:1"), { it.substringBefore(':') }, {
            attempted += it
            if (it == "b:1") error("broken entry")
            it != "a:1"
        }) {}
        advanceUntilIdle()
        assertEquals(listOf("a:1", "b:1", "c:1"), attempted)
    }
}
