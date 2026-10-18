package com.relay.app.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SeenGateTest {

    private val seen = mutableSetOf<String>()
    private var processed = 0

    private fun deliver(id: String, process: () -> Unit = { processed++ }) =
        SeenGate.run(isSeen = { id in seen }, markSeen = { seen += id }, process = process)

    @Test
    fun handledOnceThenReplaysAreSkipped() {
        deliver("a")
        deliver("a")
        deliver("a")
        assertEquals(1, processed)
        assertTrue("a" in seen)
    }

    @Test
    fun failureLeavesPayloadUnseenSoTheReplayRetriesIt() {
        try {
            deliver("a") { error("database write failed") }
            fail("the failure must reach the caller")
        } catch (e: IllegalStateException) {
            assertEquals("database write failed", e.message)
        }
        assertTrue("a" !in seen)

        deliver("a")
        assertEquals(1, processed)
        assertTrue("a" in seen)
    }

    @Test
    fun markedOnlyAfterProcessingReturns() {
        deliver("a") { assertTrue("must not be marked while still processing", "a" !in seen) }
        assertTrue("a" in seen)
    }
}
