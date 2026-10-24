package com.relay.app.files

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class FileStagingTest {
    private fun fixture(): Pair<StoredPart, ByteArray> {
        val bytes = ByteArrayOutputStream()
        val part = FilePartEncryptor(ByteArray(32)).encryptPart(ByteArrayInputStream(ByteArray(100)), 100, bytes)
        return part to bytes.toByteArray()
    }
    @Test fun privateStageIsRemovedBeforeItsReaderIsReturned() {
        val stage = FileStaging.DEFAULT.create()
        stage.output().use { it.write(byteArrayOf(1, 2, 3)) }
        stage.releaseForRead().use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
        assertThrows(FileNotFoundException::class.java) { stage.input() }
        stage.close()
    }
    @Test fun cleanupFailurePreventsFinalPublication() {
        val (part, bytes) = fixture()
        var closed = 0
        val staging = FileStaging {
            val real = FileStaging.DEFAULT.create()
            object : FileStage by real {
                override fun close() { real.close(); closed++; throw IOException("injected cleanup failure") }
            }
        }
        val sink = ByteArrayOutputStream()
        val error = assertThrows(IOException::class.java) {
            FilePartDecryptor(ByteArray(32), staging).decryptPart(ByteArrayInputStream(bytes), FilePart(part.sha256Hex, part.storedBytes), 100, sink)
        }
        assertEquals("injected cleanup failure", error.message)
        assertTrue(closed >= 2); assertEquals(0, sink.size())
    }
    @Test fun diskFailureDiscardsPrivateStagesAndPublishesNothing() {
        val (part, bytes) = fixture()
        var closed = 0
        val staging = FileStaging {
            val real = FileStaging.DEFAULT.create()
            object : FileStage by real {
                override fun output(): OutputStream = object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } }
                override fun close() { real.close(); closed++ }
            }
        }
        val sink = ByteArrayOutputStream()
        val result = FilePartDecryptor(ByteArray(32), staging).decryptPart(ByteArrayInputStream(bytes), FilePart(part.sha256Hex, part.storedBytes), 100, sink)
        assertEquals(PartDecryptResult.Failed(PartFailure.IO_ERROR), result)
        assertEquals(1, closed); assertEquals(0, sink.size())
    }
    @Test fun tagFailureStillCleansEveryStage() {
        val (part, bytes) = fixture()
        var created = 0; var closed = 0
        val staging = FileStaging {
            created++
            val real = FileStaging.DEFAULT.create()
            object : FileStage by real { override fun close() { real.close(); closed++ } }
        }
        val sink = ByteArrayOutputStream()
        val result = FilePartDecryptor(ByteArray(32) { 1 }, staging).decryptPart(ByteArrayInputStream(bytes), FilePart(part.sha256Hex, part.storedBytes), 100, sink)
        assertEquals(PartDecryptResult.Failed(PartFailure.AUTH_FAILED), result)
        assertEquals(created, closed); assertEquals(0, sink.size())
    }
}
