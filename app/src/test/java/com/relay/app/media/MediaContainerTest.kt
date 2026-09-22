package com.relay.app.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class MediaContainerTest {

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    @Test
    fun emptyPayloadRoundTrips() {
        val wrapped = MediaContainer.wrap(ByteArray(0))
        assertArrayEquals(ByteArray(0), MediaContainer.unwrap(wrapped))
    }

    @Test
    fun typicalPayloadSizesRoundTripExactly() {
        for (size in listOf(1, 27, 4096, 65533, 65534, 65535, 200_000, 3_000_000)) {
            val payload = randomBytes(size)
            val wrapped = MediaContainer.wrap(payload)
            assertArrayEquals("size $size", payload, MediaContainer.unwrap(wrapped))
        }
    }

    @Test
    fun wrappedSizeMatchesWhatWrapActuallyProduces() {
        for (size in listOf(0, 1, 65533, 65534, 130_000, 3_000_000, 12_000_000)) {
            assertEquals("size $size", MediaContainer.wrappedSize(size), MediaContainer.wrap(randomBytes(size)).size)
        }
    }

    @Test
    fun theWrapperStartsWithRealJpegMagicBytes() {
        val wrapped = MediaContainer.wrap(randomBytes(10))
        assertEquals(0xFF, wrapped[0].toInt() and 0xFF)
        assertEquals(0xD8, wrapped[1].toInt() and 0xFF)
        // APP0/JFIF right after SOI, exactly what a real camera JPEG starts with.
        assertEquals(0xFF, wrapped[2].toInt() and 0xFF)
        assertEquals(0xE0, wrapped[3].toInt() and 0xFF)
        assertEquals('J'.code.toByte(), wrapped[6])
        assertEquals('F'.code.toByte(), wrapped[7])
        assertEquals('I'.code.toByte(), wrapped[8])
        assertEquals('F'.code.toByte(), wrapped[9])
    }

    @Test
    fun theWrapperEndsWithARealEoiMarker() {
        val wrapped = MediaContainer.wrap(randomBytes(500))
        assertEquals(0xFF, wrapped[wrapped.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, wrapped[wrapped.size - 1].toInt() and 0xFF)
    }

    @Test
    fun aPayloadCrossingASegmentBoundaryStillRoundTripsAndUsesMultipleSegments() {
        // A little over two full segments: must split into three COM segments and reassemble in order.
        val payload = randomBytes(2 * 65533 + 10)
        val wrapped = MediaContainer.wrap(payload)
        assertArrayEquals(payload, MediaContainer.unwrap(wrapped))
        assertTrue(wrapped.size > payload.size) // real framing overhead was actually added
    }

    // ---- robustness: unwrap must never throw, and must reject anything it did not itself produce ------

    @Test
    fun garbageAndTruncatedInputIsRejectedNotCrashed() {
        val wrapped = MediaContainer.wrap(randomBytes(1000))
        assertNull(MediaContainer.unwrap(ByteArray(0)))
        assertNull(MediaContainer.unwrap(byteArrayOf(1, 2, 3)))
        assertNull(MediaContainer.unwrap(randomBytes(500))) // random bytes, not a real wrapper
        for (cut in 0 until wrapped.size) {
            assertNull("truncated at $cut", MediaContainer.unwrap(wrapped.copyOf(cut)))
        }
    }

    @Test
    fun trailingGarbageAfterAWellFormedWrapperIsRejected() {
        val wrapped = MediaContainer.wrap(randomBytes(50))
        assertNull(MediaContainer.unwrap(wrapped + byteArrayOf(0, 1, 2)))
    }

    @Test
    fun aRealCameraJpegIsNotMistakenForOurWrapper() {
        // A real photo has SOF/SOS/DQT markers, not just APP0 then COM segments -- must not silently
        // "succeed" by returning some other app's actual photo bytes as if they were decrypted ciphertext.
        val real = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), // SOI
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, // APP0, len 16
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
            1, 1, 0, 0, 1, 0, 1,
            0xFF.toByte(), 0xDB.toByte(), 0x00, 0x03, 0x00, // DQT (a real quantization table marker)
            0xFF.toByte(), 0xD9.toByte(), // EOI
        )
        assertNull(MediaContainer.unwrap(real))
    }
}
