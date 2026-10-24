package com.relay.app.messaging

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DeletionBodyTest {
    @Test fun stableMessageIdsRoundTripAndRejectInvalidVersionsAndPaths() {
        val id = UUID.randomUUID().toString()
        assertEquals(id, DeletionBody.parse(DeletionBody.format(id)))
        assertNull(DeletionBody.parse(DeletionBody.format(id).replace("\"v\":1", "\"v\":2")))
        for (bad in listOf("", "../media", "1-1-1-1-1", "id\n", "a".repeat(500))) {
            assertFalse(DeletionBody.validId(bad))
            assertTrue(runCatching { DeletionBody.format(bad) }.isFailure)
        }
        assertNull(DeletionBody.parse(DeletionBody.PREFIX + "{}"))
    }
    @Test fun historyPurgeHasVersionedPositiveCutoff() {
        assertEquals(123L, PurgeBody.parse(PurgeBody.format(123)))
        assertNull(PurgeBody.parse(PurgeBody.PREFIX + "{\"v\":1,\"before\":0}"))
        assertNull(PurgeBody.parse(PurgeBody.format(123).replace("\"v\":1", "\"v\":2")))
        assertTrue(runCatching { PurgeBody.format(-1) }.isFailure)
    }

}
