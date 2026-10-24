package com.relay.app.files

import com.relay.app.media.MediaContainer
import org.junit.Assert.*
import org.junit.Test

class FilePartPlanTest {
    private val MIB = 1024L * 1024

    @Test fun emptyFileHasOneAuthenticatedEmptyPart() {
        assertEquals(listOf(FilePartPlan.Part(0, 0, 0, 33)), FilePartPlan.plan(0))
    }

    @Test fun constantsAreTheDecidedOnes() {
        assertEquals(26_212_814, FilePartPlan.PART_PLAIN_BYTES)
        assertEquals(5, FilePartPlan.MAX_PARTS)
        assertEquals(100 * MIB, FilePartPlan.MAX_SIZE)
        assertEquals(25 * MIB + 64, FilePartPlan.SERVER_BLOB_CAP)
    }

    @Test fun fullPartUsesRawFramingWithinServerCapWithoutChangingPlanning() {
        val full = FilePartPlan.storedSize(FilePartPlan.PART_PLAIN_BYTES)
        assertEquals(FilePartPlan.PART_PLAIN_BYTES + 33, full)
        assertTrue(full.toLong() <= FilePartPlan.SERVER_BLOB_CAP)
    }

    @Test fun fiveFullPartsCoverTheMaximumButFourDoNot() {
        assertTrue(4L * FilePartPlan.PART_PLAIN_BYTES < FilePartPlan.MAX_SIZE)
        assertTrue(5L * FilePartPlan.PART_PLAIN_BYTES >= FilePartPlan.MAX_SIZE)
        assertTrue(FilePartPlan.MAX_PARTS <= 8) // media digest cap on POST /v1/send
    }

    @Test fun plainSizeInvertsStoredSizeAtEverySegmentBoundary() {
        for (n in listOf(1, 2, 65_504, 65_505, 65_533, 131_000, 1_000_000, FilePartPlan.PART_PLAIN_BYTES - 1, FilePartPlan.PART_PLAIN_BYTES)) {
            assertEquals(n, FilePartPlan.plainSize(FilePartPlan.storedSize(n)))
        }
        assertNull(FilePartPlan.plainSize(0)); assertNull(FilePartPlan.plainSize(10))
        assertEquals(6, FilePartPlan.plainSize(FilePartPlan.storedSize(5) + 1))
        assertNull(FilePartPlan.plainSize(FilePartPlan.storedSize(FilePartPlan.PART_PLAIN_BYTES) + 1))
        assertNull(FilePartPlan.plainSize(Int.MAX_VALUE)); assertNull(FilePartPlan.plainSize(-1))
    }

    @Test fun oneByte() {
        val p = FilePartPlan.plan(1)!!
        assertEquals(listOf(FilePartPlan.Part(0, 0, 1, FilePartPlan.storedSize(1))), p)
    }

    @Test fun exactlyOnePart() {
        val p = FilePartPlan.plan(FilePartPlan.PART_PLAIN_BYTES.toLong())!!
        assertEquals(1, p.size); assertEquals(FilePartPlan.PART_PLAIN_BYTES, p[0].length)
    }

    @Test fun onePartPlusOneByte() {
        val p = FilePartPlan.plan(FilePartPlan.PART_PLAIN_BYTES + 1L)!!
        assertEquals(2, p.size)
        assertEquals(FilePartPlan.PART_PLAIN_BYTES, p[0].length)
        assertEquals(1, p[1].length); assertEquals(FilePartPlan.PART_PLAIN_BYTES.toLong(), p[1].offset)
    }

    @Test fun maximumAndOneOver() {
        val p = FilePartPlan.plan(FilePartPlan.MAX_SIZE)!!
        assertEquals(5, p.size)
        assertEquals(FilePartPlan.MAX_SIZE, p.sumOf { it.length.toLong() })
        assertEquals(FilePartPlan.MAX_SIZE - 4L * FilePartPlan.PART_PLAIN_BYTES, p[4].length.toLong())
        assertNull(FilePartPlan.plan(FilePartPlan.MAX_SIZE + 1))
        assertEquals(0, FilePartPlan.partCount(FilePartPlan.MAX_SIZE + 1))
    }

    @Test fun invalidSizesAreRejected() {
        assertNull(FilePartPlan.plan(-1)); assertNull(FilePartPlan.plan(Long.MAX_VALUE))
    }

    @Test fun everyPlanIsContiguousAndEveryBlobFitsTheServer() {
        for (size in listOf(1L, 2L, 65_533L, FilePartPlan.PART_PLAIN_BYTES - 1L, FilePartPlan.PART_PLAIN_BYTES.toLong(),
            FilePartPlan.PART_PLAIN_BYTES + 1L, 2L * FilePartPlan.PART_PLAIN_BYTES, 2L * FilePartPlan.PART_PLAIN_BYTES + 1,
            4L * FilePartPlan.PART_PLAIN_BYTES, 4L * FilePartPlan.PART_PLAIN_BYTES + 1, 60 * MIB, FilePartPlan.MAX_SIZE)) {
            val p = FilePartPlan.plan(size)!!
            var off = 0L
            p.forEachIndexed { i, part ->
                assertEquals(i, part.index); assertEquals(off, part.offset)
                assertTrue(part.length in 1..FilePartPlan.PART_PLAIN_BYTES)
                if (i < p.size - 1) assertEquals(FilePartPlan.PART_PLAIN_BYTES, part.length)
                assertTrue(part.storedBytes <= FilePartPlan.SERVER_BLOB_CAP)
                off += part.length
            }
            assertEquals(size, off); assertEquals(p.size, FilePartPlan.partCount(size))
        }
    }
}
