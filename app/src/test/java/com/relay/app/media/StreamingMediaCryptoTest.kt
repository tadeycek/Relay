package com.relay.app.media

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamingMediaCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val encoded = Base64.getEncoder().encodeToString(key)
    private val plain = ByteArray(3 * MediaCrypto.PART_BYTES + 177) { (it % 251).toByte() }
    private fun encrypt(bytes: ByteArray = plain): ByteArray = ByteArrayOutputStream().also {
        MediaCrypto.encryptStream(ByteArrayInputStream(bytes),it,key)
    }.toByteArray()
    private fun decrypt(bytes: ByteArray): ByteArray = MediaCrypto.decryptingStream(ByteArrayInputStream(bytes),encoded).use { it.readBytes() }

    @Test fun streamingRoundTripAndRandomizedFiles() {
        val first = encrypt()
        assertArrayEquals(plain,decrypt(first))
        assertFalse(first.contentEquals(encrypt()))
        assertArrayEquals(ByteArray(0),decrypt(encrypt(ByteArray(0))))
    }

    @Test fun changedHeaderSegmentsFinalTagAndWrongKeyAreRejected() {
        val cipher = encrypt()
        for(index in listOf(0,20,MediaCrypto.PART_BYTES,2*MediaCrypto.PART_BYTES,cipher.lastIndex)) {
            val changed = cipher.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertThrows(Exception::class.java) { decrypt(changed) }
        }
        assertThrows(Exception::class.java) {
            MediaCrypto.decryptingStream(ByteArrayInputStream(cipher),Base64.getEncoder().encodeToString(ByteArray(32) { 99 })).use { it.readBytes() }
        }
    }

    @Test fun truncationAppendingReorderingAndCrossFileSubstitutionAreRejected() {
        val cipher = encrypt()
        for(size in listOf(0,15,MediaCrypto.PART_BYTES,cipher.size-1)) {
            assertThrows(Exception::class.java) { decrypt(cipher.copyOf(size)) }
        }
        assertThrows(Exception::class.java) { decrypt(cipher + byteArrayOf(0)) }
        val swapped = cipher.copyOf()
        System.arraycopy(cipher,2*MediaCrypto.PART_BYTES,swapped,MediaCrypto.PART_BYTES,MediaCrypto.PART_BYTES)
        System.arraycopy(cipher,MediaCrypto.PART_BYTES,swapped,2*MediaCrypto.PART_BYTES,MediaCrypto.PART_BYTES)
        assertThrows(Exception::class.java) { decrypt(swapped) }
        val other = encrypt()
        val substituted = cipher.copyOf()
        System.arraycopy(other,MediaCrypto.PART_BYTES,substituted,MediaCrypto.PART_BYTES,MediaCrypto.PART_BYTES)
        assertThrows(Exception::class.java) { decrypt(substituted) }
    }

    @Test fun associatedDataIsAuthenticatedAndThreeGiBSizeUsesLongArithmetic() {
        val cipher = ByteArrayOutputStream().also { MediaCrypto.encryptStream(ByteArrayInputStream(plain),it,key,"different context".toByteArray()) }.toByteArray()
        assertThrows(Exception::class.java) { decrypt(cipher) }
        val size = MediaBody.MAX_MEDIA_BYTES
        assertTrue(size > Int.MAX_VALUE)
        val expected = AesGcmHkdfStreaming(key,"HmacSha256",32,MediaCrypto.PART_BYTES,0).expectedCiphertextSize(size)
        assertTrue(expected > size && expected <= MediaCrypto.MAX_CIPHER_BYTES)
    }

    @Test fun nonClosingWrapperKeepsBulkWritesAndLeavesUnderlyingSinkOpen() {
        var scalarWrites = 0
        var bulkWrites = 0
        var closed = false
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) { scalarWrites++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { bulkWrites++ }
            override fun close() { closed = true }
        }
        MediaCrypto.encryptStream(ByteArrayInputStream(plain),MediaCrypto.nonClosing(sink),key)
        assertEquals(0,scalarWrites)
        assertTrue(bulkWrites in 1..10)
        assertFalse(closed)
    }

    /** Exercises every byte at the actual maximum, while keeping only several bounded buffers. */
    @Test(timeout = 240_000) fun fullThreeGiBRoundTripUsesBoundedBuffers() {
        val size = MediaBody.MAX_MEDIA_BYTES
        val pipe = PipedInputStream(2*MediaCrypto.PART_BYTES)
        val output = PipedOutputStream(pipe)
        val worker = Executors.newSingleThreadExecutor()
        val encrypted = worker.submit<Long> {
            val input = object : InputStream() {
                var remaining = size
                override fun read(): Int = if(remaining-- > 0) 0x5a else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if(remaining == 0L) return -1
                    val count = minOf(remaining,length.toLong()).toInt()
                    java.util.Arrays.fill(buffer,offset,offset+count,0x5a.toByte())
                    remaining -= count
                    return count
                }
            }
            try { MediaCrypto.encryptStream(input,output,key) } finally { output.close() }
        }
        try {
            var received = 0L
            MediaCrypto.decryptingStream(pipe,encoded).use { input ->
                val buffer = ByteArray(64*1024)
                while(true) {
                    val n = input.read(buffer)
                    if(n < 0) break
                    assertEquals(0x5a.toByte(),buffer[0])
                    assertEquals(0x5a.toByte(),buffer[n-1])
                    received += n
                }
            }
            assertEquals(size,received)
            assertEquals(size,encrypted.get(10,TimeUnit.SECONDS).toLong())
        } finally { pipe.close(); output.close(); worker.shutdownNow() }
    }
}
