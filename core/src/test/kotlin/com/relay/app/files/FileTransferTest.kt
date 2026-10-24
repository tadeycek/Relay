package com.relay.app.files

import com.relay.app.transport.RelayPayload
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.MessageDigest

class FileTransferTest {
    private val base = "https://relay.example/v1/media/"
    private fun fixture(bytes: ByteArray): Pair<FileBody, List<ByteArray>> {
        val blobs = mutableListOf<ByteArray>()
        val body = FileSender().prepare(ByteArrayInputStream(bytes), bytes.size.toLong(), "test-file-id", "file.txt", "text/plain", base) { _, _, source ->
            blobs += source.readBytes()
        }
        assertEquals(fileHex(MessageDigest.getInstance("SHA-256").digest(bytes)), body.sha256Hex)
        return body to blobs
    }
    private fun bind(body: FileBody, id: String = body.fileId) = FileManifest.fromAuthenticatedPayload(RelayPayload(id, 1, FileBody.encode(body))) { it == base }

    @Test fun senderAndAssemblyVerifyDigestBeforePublication() {
        for (bytes in listOf(ByteArray(0), "hello file".toByteArray(), ByteArray(100_003) { it.toByte() })) {
            val (body, blobs) = fixture(bytes)
            val sink = ByteArrayOutputStream()
            assertTrue(FileAssembler().assemble(bind(body)!!, body.fileId, { index, _ -> ByteArrayInputStream(blobs[index]) }, sink))
            assertArrayEquals(bytes, sink.toByteArray())
        }
    }
    @Test fun authenticatedPartsWithWrongWholeDigestPublishNothing() {
        val (body, blobs) = fixture("valid GCM tags but wrong full digest".toByteArray())
        val sink = ByteArrayOutputStream()
        assertFalse(FileAssembler().assemble(bind(body.copy(sha256Hex = "00".repeat(32)))!!, body.fileId, { index, _ -> ByteArrayInputStream(blobs[index]) }, sink))
        assertEquals(0, sink.size())
    }
    @Test fun swappedManifestAndPayloadIdAreRejected() {
        val (body, _) = fixture("hello".toByteArray())
        assertNull(bind(body, "another-payload-id"))
        assertNotNull(bind(body))
    }
    @Test fun resumeCannotSubstituteAnotherAuthenticatedFileId() {
        val (body, _) = fixture("hello".toByteArray())
        var fetched = false
        val sink = ByteArrayOutputStream()
        assertFalse(FileAssembler().assemble(bind(body)!!, "another-resume-id", { _, _ -> fetched = true; ByteArrayInputStream(ByteArray(0)) }, sink))
        assertFalse(fetched); assertEquals(0, sink.size())
    }
    @Test fun differentFilesGetNewKeysAndCiphertexts() {
        val (a, blobsA) = fixture("same".toByteArray()); val (b, blobsB) = fixture("same".toByteArray())
        assertNotEquals(a.keyBase64, b.keyBase64); assertFalse(blobsA[0].contentEquals(blobsB[0]))
    }
    @Test fun sentCopyKeepsOriginalPayloadIdNotTheSyncId() {
        val (body, _) = fixture("sent file".toByteArray())
        val original = RelayPayload(body.fileId, 123, FileBody.encode(body))
        val encoded = java.util.Base64.getEncoder().encodeToString(com.relay.app.transport.PayloadCodec.encode(original).toByteArray())
        val sent = com.relay.app.linking.SyncBody.Sent("primary", "different-sync-id", 1, "ab".repeat(32), encoded)
        val wire = com.relay.app.linking.SyncCodec.encode(sent)
        val decoded = (com.relay.app.linking.SyncCodec.decode(wire) as com.relay.app.linking.SyncResult.Ok).body as com.relay.app.linking.SyncBody.Sent
        val payload = com.relay.app.transport.PayloadCodec.decode(String(java.util.Base64.getDecoder().decode(decoded.payloadBase64)))!!
        assertEquals(body.fileId, FileManifest.fromAuthenticatedPayload(payload) { it == base }!!.fileId)
        assertNull(FileManifest.fromAuthenticatedPayload(payload.copy(id = decoded.syncId)) { it == base })
    }
    @Test fun multipartDigestIsVerifiedAcrossTheOrderedPlaintext() {
        val bytes = ByteArray(FilePartPlan.PART_PLAIN_BYTES + 1) { it.toByte() }
        val (body, blobs) = fixture(bytes)
        assertEquals(2, blobs.size)
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() {
            override fun write(b: Int) { digest.update(b.toByte()) }
            override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len) }
        }
        assertTrue(FileAssembler().assemble(bind(body)!!, body.fileId, { index, _ -> ByteArrayInputStream(blobs[index]) }, sink))
        assertEquals(body.sha256Hex, fileHex(digest.digest()))
        val noOutput = ByteArrayOutputStream()
        assertFalse(FileAssembler().assemble(bind(body.copy(sha256Hex = "00".repeat(32)))!!, body.fileId, { index, _ -> ByteArrayInputStream(blobs[index]) }, noOutput))
        assertEquals(0, noOutput.size())
    }
    @Test fun senderRejectsSourceLengthMismatch() {
        for (source in listOf(ByteArray(2), ByteArray(4))) assertThrows(IOException::class.java) {
            FileSender().prepare(ByteArrayInputStream(source), 3, "id", "file", "text/plain", base) { _, _, _ -> }
        }
    }
}
