package com.relay.app.messaging

import org.junit.Assert.*
import org.junit.Test

class PreviewHistoryTest {
    @Test fun messagesRetainTheLastFiveInOrderWithOriginalTimestamps() {
        var history = emptyList<PreviewLine>()
        for (i in 1..7) history = PreviewHistory.update(history, "Message $i", false, i.toLong())
        assertEquals((3..7).map { "Message $it" }, history.map { it.text })
        assertEquals((3L..7L).toList(), history.map { it.timestamp })
    }
    @Test fun profileRefreshDoesNotAppendRepeatOrRetimestampMessages() {
        val previous = listOf(PreviewLine("Keep this", 12))
        assertEquals(previous, PreviewHistory.update(previous, null, true, 100))
        assertTrue(PreviewHistory.update(emptyList(), null, true, 100).isEmpty())
    }
    @Test fun separateSnapshotsStayIsolatedAndNewPreviewTextIsBounded() {
        val first = PreviewHistory.update(emptyList(), "First", false, 1)
        val second = PreviewHistory.update(emptyList(), "Second", false, 2)
        assertEquals(listOf(PreviewLine("First", 1)), first)
        assertEquals(listOf(PreviewLine("Second", 2)), second)
        assertEquals(240, PreviewHistory.update(first, "x".repeat(1000), false, 3).last().text.length)
    }
}
