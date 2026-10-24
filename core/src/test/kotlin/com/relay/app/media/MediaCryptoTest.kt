package com.relay.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class MediaCryptoTest {

    private val sample = "the quick brown fox jumps over the lazy dog".toByteArray()

    @Test
    fun roundTrips() {
        val enc = MediaCrypto.encrypt(sample)
        assertArrayEquals(sample, MediaCrypto.decrypt(enc.blob, enc.keyBase64))
    }

    @Test
    fun roundTripsEmptyAndLargeInputs() {
        val empty = MediaCrypto.encrypt(ByteArray(0))
        assertArrayEquals(ByteArray(0), MediaCrypto.decrypt(empty.blob, empty.keyBase64))
        val big = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        val enc = MediaCrypto.encrypt(big)
        assertArrayEquals(big, MediaCrypto.decrypt(enc.blob, enc.keyBase64))
    }

    @Test
    fun blobIsLargerByIvAndTagAndDoesNotContainPlaintext() {
        val enc = MediaCrypto.encrypt(sample)
        assertEquals(sample.size + 12 + 16, enc.blob.size)
        assertEquals(false, String(enc.blob, Charsets.ISO_8859_1).contains("quick brown fox"))
    }

    @Test
    fun keyAndIvAreFreshPerCall() {
        val a = MediaCrypto.encrypt(sample)
        val b = MediaCrypto.encrypt(sample)
        assertNotEquals(a.keyBase64, b.keyBase64)
        assertNotEquals(a.sha256Hex, b.sha256Hex)
    }

    @Test
    fun keyIsThirtyTwoBytes() {
        assertEquals(32, Base64.getDecoder().decode(MediaCrypto.encrypt(sample).keyBase64).size)
    }

    @Test
    fun wrongKeyIsRejected() {
        val a = MediaCrypto.encrypt(sample)
        val b = MediaCrypto.encrypt(sample)
        assertNull(MediaCrypto.decrypt(a.blob, b.keyBase64))
    }

    @Test
    fun tamperingAnywhereIsDetected() {
        val enc = MediaCrypto.encrypt(sample)
        for (i in listOf(0, 11, 12, enc.blob.size / 2, enc.blob.size - 1)) {
            val bad = enc.blob.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull("flip at $i", MediaCrypto.decrypt(bad, enc.keyBase64))
        }
    }

    @Test
    fun truncatedOrGarbageInputIsRejectedNotThrown() {
        val enc = MediaCrypto.encrypt(sample)
        assertNull(MediaCrypto.decrypt(ByteArray(0), enc.keyBase64))
        assertNull(MediaCrypto.decrypt(ByteArray(27), enc.keyBase64))
        assertNull(MediaCrypto.decrypt(enc.blob.copyOf(enc.blob.size - 1), enc.keyBase64))
        assertNull(MediaCrypto.decrypt(enc.blob, "not base64 !!"))
        assertNull(MediaCrypto.decrypt(enc.blob, Base64.getEncoder().encodeToString(ByteArray(16))))
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            MediaCrypto.sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            MediaCrypto.sha256Hex("abc".toByteArray()),
        )
    }
}
