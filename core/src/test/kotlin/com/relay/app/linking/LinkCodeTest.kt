package com.relay.app.linking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Random

/**
 * WP05 link code: a 256-bit one-time secret rendered for humans and QR, and the two derived values
 * the server sees — the slot id (`sha256Hex(code)` over the code **text**, matching the server
 * slice) and the proof header value (the code text itself).
 *
 * These vectors are pure and fixed: a desktop/Rust implementation must reproduce them exactly.
 */
class LinkCodeTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun fixedVectorForBytesZeroToThirtyOne() {
        val code = LinkCode.fromBytes(ByteArray(32) { it.toByte() })
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", code)
        assertEquals("00010203-04050607-08090a0b-0c0d0e0f-10111213-14151617-18191a1b-1c1d1e1f", LinkCode.human(code))
        assertEquals("RELAYLINK:1:000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", LinkCode.qr(code))
        assertEquals("6c86c6aac5fb24bcf5d9939cb7d7d5645ce39418f449e03b262dd4fa14b4b92b", LinkCode.slotId(code))
        assertEquals(code, LinkCode.proofValue(code))
    }

    @Test
    fun allOnesAndRepeatedByteVectors() {
        assertEquals(
            "df0790f236013511e91fa4532fb7761f62320a51a3868dabf4a13fe5f53e3263",
            LinkCode.slotId(LinkCode.fromBytes(ByteArray(32) { 0xff.toByte() })),
        )
        val repeated = LinkCode.fromBytes(ByteArray(32) { 0x11 })
        assertEquals("1".repeat(64), repeated)
        assertEquals("3138bb9bc78df27c473ecfd1410f7bd45ebac1f59cf3ff9cfe4db77aab7aedd3", LinkCode.slotId(repeated))
        // The proof is the canonical lowercase text, never the raw bytes' uppercase spelling.
        assertEquals("1".repeat(64), LinkCode.proofValue(repeated.uppercase()))
    }

    @Test
    fun generatedCodesAreHighEntropyAndUnique() {
        val random = java.security.SecureRandom()
        // The generator is injectable so a caller can use its own DRBG; 256 draws must not repeat.
        val codes = HashSet<String>()
        repeat(256) { codes += LinkCode.generate(random) }
        assertEquals(256, codes.size)
        for (code in codes) {
            assertEquals(64, code.length)
            assertTrue(LinkCode.isValid(code))
            val bytes = LinkCode.rawBytes(code)
            assertNotNull(bytes)
            assertEquals(32, bytes!!.size)
            assertTrue(bytes.any { it.toInt() != 0 })
        }
        assertTrue(codes.all { c -> c.all { it in "0123456789abcdef" } })
    }

    @Test
    fun humanAndQrFormsNormalizeBackToTheCanonicalCode() {
        val code = LinkCode.fromBytes(ByteArray(32) { (it * 37 + 5).toByte() })
        val human = LinkCode.human(code)
        assertEquals(code, LinkCode.canonical(human))
        assertEquals(code, LinkCode.canonical(" $human "))
        assertEquals(code, LinkCode.canonical(code.uppercase()))
        assertEquals(code, LinkCode.canonical(code.replace("-", " ")))
        assertEquals(code, LinkCode.fromQr(LinkCode.qr(code)))
        assertEquals(code, LinkCode.fromQr("  " + LinkCode.qr(code) + "\n"))
        // Grouped by colons is also tolerated (some QR renderers use it).
        assertEquals(code, LinkCode.canonical(code.chunked(8).joinToString(":")))
    }

    @Test
    fun invalidCodesAreRejected() {
        val good = LinkCode.fromBytes(ByteArray(32) { 7 })
        assertNull(LinkCode.canonical(""))
        assertNull(LinkCode.canonical(good.drop(1)))
        assertNull(LinkCode.canonical(good + "0"))
        assertNull(LinkCode.canonical(good.dropLast(1) + "!"))
        assertNull(LinkCode.canonical("g" + good.drop(1)))
        assertFalse(LinkCode.isValid("not-hex"))
        assertNull(LinkCode.rawBytes("nope"))
        assertNull(LinkCode.fromQr("https://example.com/" + good))
        assertNull(LinkCode.fromQr("RELAYLINK:2:" + good))
    }

    @Test
    fun proofHeaderIsStableUrlSafeAndHashesToTheSlot() {
        // Must match the server slice: lowercase header name, sha256Hex(code), proof = code.
        assertEquals("x-relay-link-proof", LinkCode.PROOF_HEADER)
        val code = LinkCode.fromBytes(ByteArray(32) { (it + 9).toByte() })
        val proof = LinkCode.proofValue(code)
        assertEquals(64, proof.length)
        assertTrue(proof.matches(Regex("[0-9a-f]{64}")))
        val sha = MessageDigest.getInstance("SHA-256").digest(proof.toByteArray(Charsets.UTF_8))
        assertEquals(LinkCode.slotId(code), hex(sha))
        assertFalse(LinkCode.slotId(code) == proof)
        assertEquals(LinkCode.slotId(code), LinkCode.slotId(LinkCode.human(code)))
        assertEquals(LinkCode.slotId(code), LinkCode.slotId(code.uppercase()))
    }

    @Test
    fun generateWithoutInjectedRandomStillUsesASecureSource() {
        val a = LinkCode.generate()
        val b = LinkCode.generate()
        assertTrue(LinkCode.isValid(a))
        assertTrue(LinkCode.isValid(b))
        assertFalse(a == b)
        // A seeded source is usable for deterministic harnesses.
        val deterministic = LinkCode.generate(Random(1))
        assertTrue(LinkCode.isValid(deterministic))
        assertEquals(deterministic, LinkCode.generate(Random(1)))
    }
}
