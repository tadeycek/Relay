package com.relay.app.messaging

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshQueueTest {
    @Test fun updatesDuringAnInitialLoadProduceOneFollowupWithoutCancellingIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        var started = 0
        var completed = 0
        val queue = RefreshQueue(backgroundScope, { throw it }) {
            started += 1
            gate.await()
            completed += 1
        }
        queue.request()
        runCurrent()
        repeat(10) { queue.request() }
        runCurrent()
        assertEquals(1, started)
        assertEquals(0, completed)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, started)
        assertEquals(2, completed)
    }

    @Test fun aFailedLoadCanBeRetried() = runTest {
        var attempts = 0
        var failures = 0
        val queue = RefreshQueue(backgroundScope, { failures += 1 }) {
            attempts += 1
            if (attempts == 1) error("First load failed")
        }
        queue.request()
        runCurrent()
        queue.request()
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, failures)
    }
}
