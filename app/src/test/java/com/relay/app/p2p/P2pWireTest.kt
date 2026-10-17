package com.relay.app.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class P2pWireTest {

    private val nonce = "0123456789abcdef0123456789abcdef"

    @Test
    fun headerRoundTrips() {
        val out = ByteArrayOutputStream()
        P2pWire.writeHeader(out, P2pWire.Header(nonce))
        assertEquals(P2pWire.Header(nonce), P2pWire.readHeader(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun aTruncatedStreamYieldsNullNotAnException() {
        val out = ByteArrayOutputStream()
        P2pWire.writeHeader(out, P2pWire.Header(nonce))
        val full = out.toByteArray()
        for (cut in 0 until full.size) {
            assertNull("cut at $cut", P2pWire.readHeader(ByteArrayInputStream(full.copyOf(cut))))
        }
    }

    @Test
    fun aBogusLengthPrefixIsRejected() {
        // A huge or negative claimed length must not make readHeader try to allocate/read it.
        val huge = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(100_000_000) }.toByteArray()
        assertNull(P2pWire.readHeader(ByteArrayInputStream(huge)))

        val negative = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(-1) }.toByteArray()
        assertNull(P2pWire.readHeader(ByteArrayInputStream(negative)))
    }

    @Test
    fun garbageJsonInsideAValidLengthPrefixIsRejected() {
        val body = "not json".toByteArray()
        val framed = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(body.size); write(body) }
        }.toByteArray()
        assertNull(P2pWire.readHeader(ByteArrayInputStream(framed)))
    }

    @Test
    fun aHeaderWithAnInvalidNonceIsRejected() {
        fun frame(json: String): ByteArrayInputStream {
            val bytes = json.toByteArray()
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply { writeInt(bytes.size); write(bytes) }
            return ByteArrayInputStream(out.toByteArray())
        }
        assertNull(P2pWire.readHeader(frame("""{"nonce":"short"}""")))
        assertNull(P2pWire.readHeader(frame("""{"nonce":"${nonce.uppercase()}"}""")))
        assertNull(P2pWire.readHeader(frame("""{}""")))
    }

    // ---- blob ----------------------------------------------------------------------------------

    @Test
    fun blobRoundTrips() {
        val data = ByteArray(50_000) { it.toByte() }
        val read = P2pWire.readBlob(ByteArrayInputStream(data), data.size.toLong(), maxBytes = 1_000_000)
        assertArrayEquals(data, read)
    }

    @Test
    fun aBlobShorterThanClaimedYieldsNull() {
        val data = ByteArray(100)
        assertNull(P2pWire.readBlob(ByteArrayInputStream(data), 200L, maxBytes = 1_000_000))
    }

    @Test
    fun aClaimedSizeOverTheCapIsRejectedWithoutReadingAnything() {
        assertNull(P2pWire.readBlob(ByteArrayInputStream(ByteArray(10)), 10L, maxBytes = 5L))
    }

    @Test
    fun aNonPositiveSizeIsRejected() {
        assertNull(P2pWire.readBlob(ByteArrayInputStream(ByteArray(0)), 0L, maxBytes = 100L))
        assertNull(P2pWire.readBlob(ByteArrayInputStream(ByteArray(0)), -1L, maxBytes = 100L))
    }
}
