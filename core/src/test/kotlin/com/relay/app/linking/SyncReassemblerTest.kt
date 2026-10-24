package com.relay.app.linking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * G3: bounded part reassembly. Duplicate parts are tolerated, order is irrelevant, a different
 * revision for the same sync id is a conflict, and every resource (parts, bytes, concurrent
 * assemblies, age) has a hard limit.
 */
class SyncReassemblerTest {

    private val origin = "phone-primary"
    private val sid = "0123456789abcdef0123456789abcdef"

    private fun sha(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun part(
        index: Int,
        count: Int,
        data: ByteArray,
        revision: Long = 1,
        total: Long = -1,
        hash: String = sha(data),
        syncId: String = sid,
    ) = SyncBody.Part(
        origin = origin,
        syncId = syncId,
        revision = revision,
        kind = "GROUP",
        index = index,
        count = count,
        totalBytes = if (total >= 0) total else data.size.toLong(),
        sha256Hex = hash,
        dataBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(data),
    )

    /** Builds [count] parts whose concatenation is [whole]; each part's hash is the whole hash. */
    private fun parts(whole: ByteArray, count: Int, revision: Long = 1): List<SyncBody.Part> {
        val h = sha(whole)
        val size = (whole.size + count - 1) / count
        return (0 until count).map { i ->
            val chunk = whole.copyOfRange(i * size, minOf(whole.size, (i + 1) * size))
            SyncBody.Part(origin, sid, revision, "GROUP", i + 1, count, whole.size.toLong(), h,
                Base64.getUrlEncoder().withoutPadding().encodeToString(chunk))
        }
    }

    private fun assertRejected(expected: ReassemblyError, result: ReassemblyResult) {
        assertTrue("expected Rejected($expected), got $result", result is ReassemblyResult.Rejected)
        assertEquals(expected, (result as ReassemblyResult.Rejected).reason)
    }

    @Test
    fun inOrderPartsComplete() {
        val whole = "the whole logical group roster".toByteArray()
        val reassembler = SyncReassembler()
        val ps = parts(whole, 3)
        assertEquals(ReassemblyResult.Pending(1, 3), reassembler.offer(ps[0], now = 0))
        assertEquals(ReassemblyResult.Pending(2, 3), reassembler.offer(ps[1], now = 0))
        val done = reassembler.offer(ps[2], now = 0)
        assertTrue(done is ReassemblyResult.Complete)
        assertEquals(String(whole), (done as ReassemblyResult.Complete).body)
        assertEquals(0, reassembler.activeCount)
    }

    @Test
    fun outOfOrderPartsAreAccepted() {
        val whole = ByteArray(1000) { ('a'.code + it % 26).toByte() }
        val reassembler = SyncReassembler()
        val ps = parts(whole, 4)
        assertEquals(ReassemblyResult.Pending(1, 4), reassembler.offer(ps[3], now = 0))
        assertEquals(ReassemblyResult.Pending(2, 4), reassembler.offer(ps[1], now = 0))
        assertEquals(ReassemblyResult.Pending(3, 4), reassembler.offer(ps[0], now = 0))
        val done = reassembler.offer(ps[2], now = 0)
        assertTrue(done is ReassemblyResult.Complete)
        assertEquals(String(whole), (done as ReassemblyResult.Complete).body)
    }

    @Test
    fun duplicatePartsAreTolerated() {
        val whole = "duplicate tolerant".toByteArray()
        val reassembler = SyncReassembler()
        val ps = parts(whole, 2)
        assertEquals(ReassemblyResult.Pending(1, 2), reassembler.offer(ps[0], now = 0))
        assertEquals(ReassemblyResult.Duplicate(1, 2), reassembler.offer(ps[0], now = 0))
        assertEquals(ReassemblyResult.Duplicate(1, 2), reassembler.offer(ps[0], now = 0))
        assertEquals(ReassemblyResult.Complete("duplicate tolerant"), reassembler.offer(ps[1], now = 0))
        assertEquals(0, reassembler.activeCount)
    }

    @Test
    fun aDifferentPayloadForTheSameIndexIsAConflict() {
        val reassembler = SyncReassembler()
        val ps = parts("abcdefgh".toByteArray(), 2)
        reassembler.offer(ps[0], now = 0)
        val evil = ps[0].copy(dataBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString("EVIL".toByteArray()))
        assertRejected(ReassemblyError.CONFLICTING_PART, reassembler.offer(evil, now = 0))
        assertEquals(1, reassembler.activeCount)
    }

    @Test
    fun aDifferentRevisionForTheSameSyncIdIsAConflict() {
        val reassembler = SyncReassembler()
        val first = parts("abcdefgh".toByteArray(), 2, revision = 1)
        val second = parts("abcdefgh".toByteArray(), 2, revision = 2)
        reassembler.offer(first[0], now = 0)
        assertRejected(ReassemblyError.CONFLICTING_REVISION, reassembler.offer(second[1], now = 0))
    }

    @Test
    fun tooManyPartsIsRejected() {
        val reassembler = SyncReassembler()
        val data = "x".toByteArray()
        assertRejected(ReassemblyError.TOO_MANY_PARTS, reassembler.offer(
            part(1, SyncReassembler.MAX_PARTS + 1, data), now = 0))
        assertRejected(ReassemblyError.TOO_MANY_PARTS, reassembler.offer(part(1, 0, data), now = 0))
        assertRejected(ReassemblyError.TOO_MANY_PARTS, reassembler.offer(part(0, 2, data), now = 0))
        assertRejected(ReassemblyError.TOO_MANY_PARTS, reassembler.offer(part(3, 2, data), now = 0))
    }

    @Test
    fun oversizeDeclaredTotalIsRejectedUpFront() {
        val reassembler = SyncReassembler()
        assertRejected(ReassemblyError.OVERSIZE_TOTAL, reassembler.offer(
            part(1, 2, "x".toByteArray(), total = SyncReassembler.MAX_TOTAL_BYTES + 1), now = 0))
        assertRejected(ReassemblyError.OVERSIZE_TOTAL, reassembler.offer(
            part(1, 2, "x".toByteArray(), total = 0), now = 0))
        assertEquals(0, reassembler.activeCount)
    }

    @Test
    fun aPartLargerThanThePerPartBoundIsRejected() {
        val reassembler = SyncReassembler()
        val big = ByteArray(SyncReassembler.MAX_PART_BYTES + 1)
        assertRejected(ReassemblyError.BAD_PART, reassembler.offer(part(1, 2, big), now = 0))
    }

    @Test
    fun badOrMismatchedHashIsRejectedAtCompletion() {
        val whole = "hash me".toByteArray()
        val ps = parts(whole, 2)
        val reassembler = SyncReassembler()
        reassembler.offer(ps[0].copy(sha256Hex = "0".repeat(64)), now = 0)
        assertRejected(ReassemblyError.BAD_HASH, reassembler.offer(ps[1].copy(sha256Hex = "0".repeat(64)), now = 0))

        // Total-size mismatch is also a hash/integrity failure, never a truncated success.
        val reassembler2 = SyncReassembler()
        val h = sha(whole)
        reassembler2.offer(ps[0].copy(sha256Hex = h, totalBytes = whole.size + 1L), now = 0)
        assertRejected(ReassemblyError.BAD_HASH, reassembler2.offer(ps[1].copy(sha256Hex = h, totalBytes = whole.size + 1L), now = 0))
    }

    @Test
    fun expiredAssembliesAreDroppedAndReported() {
        val whole = "expire me".toByteArray()
        val ps = parts(whole, 2)
        val reassembler = SyncReassembler(ttlMillis = 10_000)
        assertEquals(ReassemblyResult.Pending(1, 2), reassembler.offer(ps[0], now = 1_000))
        assertRejected(ReassemblyError.EXPIRED, reassembler.offer(ps[1], now = 12_000))
        assertEquals(0, reassembler.activeCount)
        // A fresh start after expiry is allowed (the retry path after a delayed delivery).
        assertEquals(ReassemblyResult.Pending(1, 2), reassembler.offer(ps[0], now = 12_001))
    }

    @Test
    fun concurrentAssemblyLimitIsEnforced() {
        val reassembler = SyncReassembler(maxConcurrent = 2)
        val data = "x".toByteArray()
        assertEquals(ReassemblyResult.Pending(1, 2), reassembler.offer(part(1, 2, data, syncId = "a".repeat(32)), now = 0))
        assertEquals(ReassemblyResult.Pending(1, 2), reassembler.offer(part(1, 2, data, syncId = "b".repeat(32)), now = 0))
        assertRejected(ReassemblyError.TOO_MANY_ASSEMBLIES,
            reassembler.offer(part(1, 2, data, syncId = "c".repeat(32)), now = 0))
        // Completing one frees a slot.
        reassembler.offer(part(2, 2, data, syncId = "a".repeat(32)), now = 0)
        assertEquals(1, reassembler.activeCount)
        assertEquals(ReassemblyResult.Pending(1, 2), reassembler.offer(part(1, 2, data, syncId = "c".repeat(32)), now = 0))
    }

    @Test
    fun singlePartAndUnsolicitedIndexAreHandled() {
        val reassembler = SyncReassembler()
        val data = "one".toByteArray()
        val done = reassembler.offer(part(1, 1, data), now = 0)
        assertEquals(ReassemblyResult.Complete("one"), done)
        assertRejected(ReassemblyError.TOO_MANY_PARTS, reassembler.offer(part(2, 1, data), now = 0))
    }
}
