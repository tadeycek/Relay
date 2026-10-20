package com.relay.app.data

import com.relay.app.data.repository.HistoryWindow
import org.junit.Assert.*
import org.junit.Test

class RetainedHistoryQueryTest {
    @Test fun retentionFiltersTheRowsBeforeApplyingTheHistoryLimit() {
        for (group in listOf(false, true)) {
            val query = HistoryWindow.query(group, retainedOnly = true)
            assertTrue(query.contains("AND timestamp>? ORDER BY timestamp DESC,_id DESC LIMIT ?"))
            assertEquals(3, query.count { it == '?' })
            assertTrue(query.endsWith("ORDER BY timestamp ASC,_id ASC"))
        }
    }

    @Test fun existingUnfilteredQueryKeepsItsArguments() {
        for (group in listOf(false, true)) {
            assertEquals(2, HistoryWindow.query(group).count { it == '?' })
        }
    }
}
