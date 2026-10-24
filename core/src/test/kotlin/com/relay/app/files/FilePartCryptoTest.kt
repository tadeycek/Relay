package com.relay.app.files

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class FilePartCryptoTest {
    private val key = ByteArray(32) { (it * 5 + 3).toByte() }
    private val keyB64 = Base64.getEncoder().encodeToString(key)

    private fun data(n: Int, seed: Long = 1): ByteArray = ByteArray(n).also { java.util.Random(seed).nextBytes(it) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun enc(plain: ByteArray, k: ByteArray = key, e: FilePartEncryptor = FilePartEncryptor(k)): Pair<StoredPart, ByteArray> {
        val out = ByteArrayOutputStream()
        val sp = e.encryptPart(ByteArrayInputStream(plain), plain.size, out)
        return sp to out.toByteArray()
    }

    private fun dec(stored: ByteArray, ref: FilePart, plainLen: Int, k: ByteArray = key): Pair<PartDecryptResult, ByteArray> {
        val out = ByteArrayOutputStream()
        val r = FilePartDecryptor(k).decryptPart(ByteArrayInputStream(stored), ref, plainLen, out)
        return r to out.toByteArray()
    }

    private fun ref(sp: StoredPart) = FilePart(sp.sha256Hex, sp.storedBytes)

    @Test fun roundTripAtBoundarySizes() {
        for (n in listOf(0, 1, 2, 15, 16, 17, 65_504, 65_505, 65_533, 65_534, 131_066, 1_000_003)) {
            val p = data(n, n.toLong())
            val (sp, stored) = enc(p)
            assertEquals(n.toString(), FilePartPlan.storedSize(n), stored.size)
            assertEquals(stored.size, sp.storedBytes); assertEquals(sha(stored), sp.sha256Hex)
            val (r, out) = dec(stored, ref(sp), n)
            assertEquals(PartDecryptResult.Ok(n), r); assertArrayEquals(p, out)
        }
    }

    @Test fun roundTripAtFullPartSize() {
        val p = data(FilePartPlan.PART_PLAIN_BYTES, 9)
        val (sp, stored) = enc(p)
        assertTrue(stored.size.toLong() <= FilePartPlan.SERVER_BLOB_CAP)
        val (r, out) = dec(stored, ref(sp), p.size)
        assertEquals(PartDecryptResult.Ok(p.size), r); assertArrayEquals(p, out)
    }

    @Test fun storedBlobUsesVersionedRawFraming() {
        val p = data(200_000, 4)
        val (_, stored) = enc(p)
        assertArrayEquals(byteArrayOf(82, 76, 89, 70, 1), stored.copyOfRange(0, 5))
        assertEquals(p.size + 33, stored.size)
    }

    @Test fun unknownRawVersionIsRejectedWithNoPublication() {
        val n = 100
        val (_, stored) = enc(data(n))
        stored[4] = 2
        val (result, bytes) = dec(stored, FilePart(sha(stored), stored.size), n)
        assertEquals(PartDecryptResult.Failed(PartFailure.BAD_CONTAINER), result)
        assertEquals(0, bytes.size)
    }

    @Test fun wrongKeyFailsClosedWithNoOutput() {
        val (sp, stored) = enc(data(5000))
        val (r, out) = dec(stored, ref(sp), 5000, ByteArray(32) { 9 })
        assertEquals(PartDecryptResult.Failed(PartFailure.AUTH_FAILED), r); assertEquals(0, out.size)
    }

    @Test fun keyMustBe32Bytes() {
        assertThrows(IllegalArgumentException::class.java) { FilePartEncryptor(ByteArray(16)) }
        assertThrows(IllegalArgumentException::class.java) { FilePartDecryptor(ByteArray(33)) }
    }

    @Test fun tamperedByteAnywhereInTheCiphertextIsRejected() {
        val n = 300_000
        val (sp, stored) = enc(data(n, 5))
        // The container bytes between segments are structural; every payload byte (iv, ciphertext, tag) is authenticated.
        // Flip a byte in the iv (start), middle of ciphertext, and tag (end).
        val payloadStart = 5
        val payloadEnd = stored.size
        for (pos in listOf(payloadStart, payloadStart + 11, payloadStart + 12, stored.size / 2, payloadEnd - 17, payloadEnd - 16, payloadEnd - 1)) {
            val t = stored.copyOf(); t[pos] = (t[pos].toInt() xor 1).toByte()
            // 1) with the honest hash from the body, the hash check catches it first
            val (r1, o1) = dec(t, ref(sp), n)
            assertEquals("pos $pos", PartDecryptResult.Failed(PartFailure.HASH_MISMATCH), r1); assertEquals(0, o1.size)
            // 2) even if the hash is recomputed by an attacker, the tag fails
            val (r2, o2) = dec(t, FilePart(sha(t), t.size), n)
            assertTrue("pos $pos got $r2", r2 == PartDecryptResult.Failed(PartFailure.AUTH_FAILED) || r2 == PartDecryptResult.Failed(PartFailure.BAD_CONTAINER))
            assertEquals(0, o2.size)
        }
    }

    @Test fun everyByteOfASmallPartIsProtected() {
        val n = 40
        val (sp, stored) = enc(data(n, 6))
        for (pos in stored.indices) {
            val t = stored.copyOf(); t[pos] = (t[pos].toInt() xor 0x80).toByte()
            val (r, o) = dec(t, FilePart(sha(t), t.size), n)
            assertTrue("pos $pos got $r", r is PartDecryptResult.Failed); assertEquals(0, o.size)
        }
        assertTrue(dec(stored, ref(sp), n).first is PartDecryptResult.Ok)
    }

    @Test fun truncatedAndExtendedPartsAreRejected() {
        val n = 10_000
        val (sp, stored) = enc(data(n, 7))
        for (cut in listOf(1, 2, 16, 100, stored.size - 1)) {
            val t = stored.copyOf(stored.size - cut)
            val (r, o) = dec(t, ref(sp), n)
            assertEquals(PartDecryptResult.Failed(PartFailure.BAD_SIZE), r); assertEquals(0, o.size)
            // a lying body that declares the shorter length still fails (hash / container / tag)
            val (r2, o2) = dec(t, FilePart(sha(t), t.size), n)
            assertTrue(r2 is PartDecryptResult.Failed); assertEquals(0, o2.size)
        }
        assertEquals(PartDecryptResult.Failed(PartFailure.BAD_SIZE), dec(stored + byteArrayOf(0), ref(sp), n).first)
        assertEquals(PartDecryptResult.Failed(PartFailure.BAD_SIZE), dec(ByteArray(0), ref(sp), n).first)
    }

    @Test fun wrongDeclaredShaIsRejectedBeforeAnyDecryption() {
        val (sp, stored) = enc(data(5000, 8))
        val (r, o) = dec(stored, FilePart("0".repeat(64), sp.storedBytes), 5000)
        assertEquals(PartDecryptResult.Failed(PartFailure.HASH_MISMATCH), r); assertEquals(0, o.size)
        // a wrong key with a wrong hash still reports the hash first (verified BEFORE trusting the bytes)
        val (r2, _) = dec(stored, FilePart("0".repeat(64), sp.storedBytes), 5000, ByteArray(32))
        assertEquals(PartDecryptResult.Failed(PartFailure.HASH_MISMATCH), r2)
    }

    @Test fun inconsistentExpectationsAreRejected() {
        val (sp, stored) = enc(data(5000, 8))
        assertEquals(PartDecryptResult.Failed(PartFailure.BAD_SIZE), dec(stored, ref(sp), 4999).first)
        assertEquals(PartDecryptResult.Failed(PartFailure.BAD_SIZE), dec(stored, ref(sp), 0).first)
        assertEquals(PartDecryptResult.Failed(PartFailure.BAD_SIZE), dec(stored, ref(sp), FilePartPlan.PART_PLAIN_BYTES + 1).first)
        assertEquals(PartDecryptResult.Failed(PartFailure.BAD_SIZE), dec(stored, FilePart(sp.sha256Hex, Int.MAX_VALUE), 5000).first)
    }

    @Test fun reorderedPartsAreDetectedThroughTheBodyHashes() {
        val a = data(70_000, 11); val b = data(70_000, 12)
        val e = FilePartEncryptor(key)
        val (spA, stA) = enc(a, e = e); val (spB, stB) = enc(b, e = e)
        assertEquals(stA.size, stB.size)
        // part 1 slot holds B's blob and part 2 slot holds A's: both fail on the hash.
        assertEquals(PartDecryptResult.Failed(PartFailure.HASH_MISMATCH), dec(stB, ref(spA), 70_000).first)
        assertEquals(PartDecryptResult.Failed(PartFailure.HASH_MISMATCH), dec(stA, ref(spB), 70_000).first)
        // identical plaintext parts still give different blobs, so a body cannot legitimately repeat a hash
        val (s1, _) = enc(a, e = e); val (s2, _) = enc(a, e = e)
        assertNotEquals(s1.sha256Hex, s2.sha256Hex)
    }

    @Test fun ivIsUniqueAcrossOneThousandEncryptionsAndIsPartOfTheBlob() {
        val e = FilePartEncryptor(key); val ivs = HashSet<String>()
        val p = data(64, 3)
        repeat(1000) {
            val (_, stored) = enc(p, e = e)
            val payload = stored.copyOfRange(5, stored.size)
            assertTrue("duplicate IV", ivs.add(hex(payload.copyOfRange(0, 12))))
        }
        assertEquals(1000, ivs.size)
    }

    @Test fun repeatedIvFromTheSourceIsNeverUsedTwiceWithTheSameEncryptor() {
        val fixed = ByteArray(12) { 7 }
        val e = FilePartEncryptor(key, ivSource = { it.indices.forEach { i -> it[i] = fixed[i] } })
        enc(data(10), e = e)
        assertThrows(IllegalStateException::class.java) { enc(data(10), e = e) }
    }

    @Test fun ivSourceThatLiesAboutLengthIsNotUsed() {
        val e = FilePartEncryptor(key, ivSource = { /* leaves all zeros */ })
        enc(data(10), e = e)
        assertThrows(IllegalStateException::class.java) { enc(data(10), e = e) }
    }

    @Test fun shortInputStreamIsAnError() {
        val e = FilePartEncryptor(key)
        assertThrows(java.io.EOFException::class.java) { e.encryptPart(ByteArrayInputStream(ByteArray(10)), 11, ByteArrayOutputStream()) }
        assertThrows(IllegalArgumentException::class.java) { e.encryptPart(ByteArrayInputStream(ByteArray(10)), -1, ByteArrayOutputStream()) }
        assertThrows(IllegalArgumentException::class.java) { e.encryptPart(ByteArrayInputStream(ByteArray(10)), FilePartPlan.PART_PLAIN_BYTES + 1, ByteArrayOutputStream()) }
    }

    @Test fun encryptorReadsExactlyPlainLengthFromTheStream() {
        val src = ByteArrayInputStream(data(1000))
        FilePartEncryptor(key).encryptPart(src, 600, ByteArrayOutputStream())
        assertEquals(400, src.available())
    }

    @Test fun decryptorFailsOnSourceIoErrorWithoutOutput() {
        val (sp, stored) = enc(data(3000))
        val boom = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < 100) stored[i++].toInt() and 0xff else throw java.io.IOException("net")
        }
        val out = ByteArrayOutputStream()
        val r = FilePartDecryptor(key).decryptPart(boom, ref(sp), 3000, out)
        assertEquals(PartDecryptResult.Failed(PartFailure.IO_ERROR), r); assertEquals(0, out.size())
    }

    @Test fun aCorruptedPartIsRetriedWholeWithoutAffectingOthers() {
        // a file of three parts: part 2 is corrupted on download, only part 2 needs fetching again
        val parts = (0..2).map { data(50_000, 20L + it) }
        val e = FilePartEncryptor(key)
        val enc = parts.map { enc(it, e = e) }
        val bad = enc[1].second.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val results = listOf(enc[0].second, bad, enc[2].second).mapIndexed { i, s -> dec(s, ref(enc[i].first), 50_000) }
        assertTrue(results[0].first is PartDecryptResult.Ok); assertTrue(results[2].first is PartDecryptResult.Ok)
        assertTrue(results[1].first is PartDecryptResult.Failed); assertEquals(0, results[1].second.size)
        assertArrayEquals(parts[1], dec(enc[1].second, ref(enc[1].first), 50_000).second) // the retry
    }

    // ---- memory model ----

    private class CountingSink : OutputStream() {
        var total = 0L; var maxWrite = 0
        override fun write(b: Int) { total++; maxWrite = maxOf(maxWrite, 1) }
        override fun write(b: ByteArray, off: Int, len: Int) { total += len; maxWrite = maxOf(maxWrite, len) }
    }

    /** Serves a full part of deterministic bytes without ever materialising it, recording the largest read requested. */
    private class GeneratedInput(private var left: Int) : InputStream() {
        var maxRequest = 0
        override fun read(): Int = if (left-- > 0) 0x5a else { left = 0; -1 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            maxRequest = maxOf(maxRequest, len)
            val n = minOf(len, left); java.util.Arrays.fill(b, off, off + n, 0x5a.toByte()); left -= n; return n
        }
    }

    private fun threadAllocated(): Long =
        (java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).getThreadAllocatedBytes(Thread.currentThread().id)

    @Test fun encryptorAllocationMeasurementIsNotAPeakHeapClaim() {
        fun allocFor(n: Int): Pair<Long, CountingSink> {
            val sink = CountingSink(); val inp = GeneratedInput(n); val e = FilePartEncryptor(key)
            val t0 = threadAllocated()
            e.encryptPart(inp, n, sink)
            val used = threadAllocated() - t0
            assertTrue("read request ${inp.maxRequest}", inp.maxRequest <= FilePartEncryptor.CHUNK_BYTES)
            assertTrue("write ${sink.maxWrite}", sink.maxWrite <= FilePartEncryptor.CHUNK_BYTES + 64)
            assertEquals(FilePartPlan.storedSize(n).toLong(), sink.total)
            return used to sink
        }
        allocFor(1_000_000) // warm up classes and JIT-independent one-off allocations
        val small = allocFor(1_000_000).first
        val full = allocFor(FilePartPlan.PART_PLAIN_BYTES).first
        println("PROVIDER SunJCE encrypt threadAllocated small=$small full=$full part=${FilePartPlan.PART_PLAIN_BYTES}; NOT peak heap/native")

    }

    @Test fun decryptorAllocationMeasurementIsNotAPeakHeapClaim() {
        val n = FilePartPlan.PART_PLAIN_BYTES
        val p = data(n, 33)
        val (sp, stored) = enc(p)
        val d = FilePartDecryptor(key); val sink = CountingSink()
        d.decryptPart(ByteArrayInputStream(stored), ref(sp), n, CountingSink()) // warm up
        val t0 = threadAllocated()
        val r = d.decryptPart(ByteArrayInputStream(stored), ref(sp), n, sink)
        val used = threadAllocated() - t0
        println("DECRYPTOR ALLOC used=$used stored=${stored.size} plain=$n NOT peak heap/native")
        assertEquals(PartDecryptResult.Ok(n), r); assertEquals(n.toLong(), sink.total)
        assertTrue(sink.maxWrite <= FilePartDecryptor.WRITE_CHUNK_BYTES)
    }
}
