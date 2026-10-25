package com.relay.app.desktop

import com.relay.app.media.MediaBody
import com.relay.app.media.MediaContainer
import com.relay.app.media.MediaCrypto
import com.relay.app.messaging.InnerEnvelope
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.mailbox.MailboxMedia
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WP08 wire compatibility with the Android receiver/sender, and the engine routing around the
 * controller (receive route, ack-after-durable, signed media digest, SENT copy digest).
 */
class DesktopMediaWireTest {
    @get:Rule val tmp = TemporaryFolder()
    private val base = "https://media.example/v1/media/"

    /** Produced independently of this code base: python `cryptography` AES-256-GCM, key 00..1f, iv a0..ab. */
    private object Golden {
        const val KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        const val BLOB_B64 = "oKGio6Slpqeoqaqrb0gyakjBGLViZYfeTjKEjHCsWRGSt0JtlAgmhn+0YMVbdkf/omsXfAvk3qttGnumSBtEz2NQ8Tn7zAtepHDM9fr4Ky1QJ/H1rcRkGFfL35yblXrWpfs="
        const val BLOB_SHA = "584fbca1076b4724ac679acf8d73b21f75b14206de34ec07cffd46d93632c564"
        const val PNG_B64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    }

    private class MemoryServer(override val urlBase: String) : DesktopMediaTransport {
        val blobs = HashMap<String, ByteArray>(); var confirms = 0
        override fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation, onBytes: (Long) -> Unit) { blobs[hash] = file.readBytes(); onBytes(file.length()) }
        override fun download(file: File, hash: String, maxBytes: Long, cancellation: MailboxMedia.Cancellation) {
            val b = blobs[hash] ?: throw MailboxMedia.HttpFailure(404, "media_not_found", false)
            Files.createFile(file.toPath(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            file.writeBytes(b)
        }
        override fun confirm(hash: String, cancellation: MailboxMedia.Cancellation): Boolean { confirms++; return true }
    }

    private fun store(name: String): DesktopMessageStore {
        val p = DesktopPaths(tmp.root.toPath().resolve("$name-config"), tmp.root.toPath().resolve("$name-data"))
        return DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile))
    }

    private fun goldenMessage(wrapped: Boolean, server: MemoryServer): DesktopMessage {
        val blob = Base64.getDecoder().decode(Golden.BLOB_B64)
        val stored = if (wrapped) MediaContainer.wrap(blob) else blob
        val sha = MediaCrypto.sha256Hex(stored)
        if (!wrapped) assertEquals(Golden.BLOB_SHA, sha)
        server.blobs[sha] = stored
        // Exactly the field order and spelling MediaBody.format has always produced for the Android sender.
        val body = "TYPE:MEDIA|URL:$base$sha|SHA:$sha|KEY:${Golden.KEY}|MIME:image/png|SIZE:70|CAP:from my phone"
        return DesktopMessage(PayloadCodec.newId(), "b".repeat(64), "b".repeat(64), body, System.currentTimeMillis(), false)
    }

    @Test fun `a blob produced independently of this code base by the Android layout is received and classified`() = runBlocking<Unit> {
        store("golden").use { s -> val server = MemoryServer(base); val c = DesktopMedia(tmp.newFolder().toPath(), s, server, "a".repeat(64))
            val m = goldenMessage(false, server); assertTrue(c.accept(m)); c.run(m.id, { _, _ -> error("no send") })
            val r = s.mediaRecord(m.id)!!
            assertEquals("COMPLETE", r.state); assertEquals("IMAGE", r.verdict); assertEquals(1L, r.width); assertEquals(1L, r.height)
            assertArrayEquals(Base64.getDecoder().decode(Golden.PNG_B64), c.plaintext(m.id))
            assertEquals("from my phone", MediaBody.parse(s.messages("b".repeat(64)).single().body)!!.caption)
        }
    }

    @Test fun `legacy JPEG wrapped container from older phones is unwrapped with identical plaintext`() = runBlocking<Unit> {
        store("wrapped").use { s -> val server = MemoryServer(base); val c = DesktopMedia(tmp.newFolder().toPath(), s, server, "a".repeat(64))
            val m = goldenMessage(true, server)
            val stored = server.blobs.values.single()
            assertEquals(0xFF, stored[0].toInt() and 0xFF); assertEquals(0xD8, stored[1].toInt() and 0xFF)   // looks like a JPEG to a media server
            c.accept(m); c.run(m.id, { _, _ -> error("no send") })
            assertEquals("COMPLETE", s.mediaRecord(m.id)!!.state); assertArrayEquals(Base64.getDecoder().decode(Golden.PNG_B64), c.plaintext(m.id))
        }
    }

    @Test fun `outgoing wire body and blob layout match what the Android receiver parses`() = runBlocking<Unit> {
        store("out").use { s -> val server = MemoryServer(base); val root = tmp.newFolder().toPath(); val c = DesktopMedia(root, s, server, "a".repeat(64), sendEnabled = true)
            val png = Base64.getDecoder().decode(Golden.PNG_B64)
            val src = Files.createTempFile(tmp.root.toPath(), "src", ".png").also { Files.write(it, png) }
            val r = c.prepare("b".repeat(64), src, "hello")
            assertTrue(r.body, Regex("^TYPE:MEDIA\\|URL:https://media\\.example/v1/media/[0-9a-f]{64}\\|SHA:[0-9a-f]{64}\\|KEY:[A-Za-z0-9+/]{43}=\\|MIME:image/png\\|SIZE:70\\|CAP:hello$").matches(r.body))
            val ref = MediaBody.parse(r.body)!!
            val blob = Files.walk(root).use { w -> w.filter { Files.isRegularFile(it) }.toList() }.single().let { Files.readAllBytes(it) }
            assertEquals("raw upload, the SHA addresses the exact stored bytes", ref.sha256Hex, MediaCrypto.sha256Hex(blob))
            assertEquals("iv(12) || ciphertext || tag(16)", 70 + 12 + 16, blob.size)
            assertNull("not JPEG-wrapped", MediaContainer.unwrap(blob))
            // The Android algorithm, spelled out with plain JCA rather than our MediaCrypto.
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(ref.keyBase64), "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            assertArrayEquals(png, cipher.doFinal(blob, 12, blob.size - 12))
        }
    }

    // ---- engine routing ------------------------------------------------------------------------

    private class FakeTransport : Transport {
        private val _status = MutableStateFlow(TransportStatus.ONLINE)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()
        private val inbox = Channel<IncomingEnvelope>(Channel.UNLIMITED)
        override val incoming: Flow<IncomingEnvelope> = inbox.receiveAsFlow()
        val media = mutableListOf<Triple<String, RelayPayload, List<String>>>()
        override suspend fun start() {}
        override suspend fun stop() {}
        override suspend fun reconnect() {}
        override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean): SendResult = SendResult.Sent(1)
        override suspend fun sendWithMedia(recipientPubkeyHex: String, payload: RelayPayload, mediaDigests: List<String>, relayHints: List<String>): SendResult {
            media += Triple(recipientPubkeyHex, payload, mediaDigests); return SendResult.Sent(1)
        }
        suspend fun deliver(env: IncomingEnvelope) = inbox.send(env)
    }

    private class Rig(val engine: DesktopEngine, val store: DesktopMessageStore, val transport: FakeTransport, val server: MemoryServer, val keys: DesktopKeyMaterial, val peerKeys: DesktopKeyMaterial, val own: String)

    private fun rig(send: Boolean): Rig {
        val paths = DesktopPaths(tmp.newFolder().toPath(), tmp.newFolder().toPath()); val secrets = FileSecretStore(paths.secretFile)
        val identity = DesktopIdentity(paths, secrets); val keys = DesktopKeyMaterial(paths, secrets); val s = DesktopMessageStore(paths.databaseFile, secrets)
        val pp = DesktopPaths(tmp.newFolder().toPath(), tmp.newFolder().toPath()); val peerKeys = DesktopKeyMaterial(pp, FileSecretStore(pp.secretFile))
        val server = MemoryServer(base); val transport = FakeTransport(); val peer = "b".repeat(64)
        val engine = DesktopEngine(identity, keys, s, transport, peerInnerKeys = mapOf(peer to peerKeys.publicKeyBase64()),
            media = DesktopMedia(tmp.newFolder().toPath(), s, server, identity.publicKeyHex(), sendEnabled = send))
        return Rig(engine, s, transport, server, keys, peerKeys, identity.publicKeyHex())
    }

    @Test fun `sealed incoming TYPE MEDIA is stored durably before the mailbox may drop it then downloaded and confirmed`() = runBlocking<Unit> {
        val r = rig(false); r.engine.start()
        val m = goldenMessage(false, r.server)
        val handledWhen = java.util.concurrent.atomic.AtomicReference<String?>()
        r.transport.deliver(IncomingEnvelope("b".repeat(64), RelayPayload(m.id, m.ts, InnerEnvelope.seal(r.peerKeys, r.keys.publicKeyBase64(), m.body)), m.ts) {
            handledWhen.set(r.store.mediaRecord(m.id)?.state)
        })
        withTimeout(10_000) { while (r.store.mediaRecord(m.id)?.state != "COMPLETE") kotlinx.coroutines.delay(50) }
        assertNotNull("ack only after the journal row exists", handledWhen.get())
        assertEquals(1, r.server.confirms); assertEquals(1, r.store.messages("b".repeat(64)).size)
        r.engine.close(); r.store.close()
    }

    @Test fun `engine send puts the digest in the signed media list and the SENT copy carries it`() = runBlocking<Unit> {
        val r = rig(true); val peer = "b".repeat(64)
        val img = java.awt.image.BufferedImage(30, 20, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        val src = Files.createTempFile(tmp.root.toPath(), "e", ".png").also { Files.write(it, bytes) }
        val id = r.engine.sendMedia(peer, src, "cap")
        val rec = r.store.mediaRecord(id)!!
        val ref = MediaBody.parse(rec.body)!!
        val (to, payload, digests) = r.transport.media.single()
        assertEquals(peer, to); assertEquals(id, payload.id); assertEquals(listOf(ref.sha256Hex), digests)
        assertTrue("the contact copy is sealed, never plain", payload.body.startsWith("TYPE:ENC|CT:"))
        assertEquals(rec.body, InnerEnvelope.open(r.peerKeys, payload.body))
        assertEquals("unlinked desktop with no SENT sync confirms once the contact send succeeded", "COMPLETE", r.store.mediaRecord(id)!!.state)
        assertEquals(listOf(ref.sha256Hex), r.server.blobs.keys.toList())
        r.engine.close(); r.store.close()
    }

    @Test fun `engine refuses to send when the contact encryption key is unknown`() = runBlocking<Unit> {
        val r = rig(true)
        val src = Files.createTempFile(tmp.root.toPath(), "e", ".png").also { Files.write(it, Base64.getDecoder().decode(Golden.PNG_B64)) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { r.engine.sendMedia("c".repeat(64), src) } }
        assertEquals(0, r.store.mediaRecords().size); assertTrue(r.server.blobs.isEmpty())
        r.engine.close(); r.store.close()
    }

    @Test fun `deleting media bumps the engine revision and the bubble leaves the thread as plain text`() = runBlocking<Unit> {
        val r = rig(false); val peer = "b".repeat(64)
        val m = goldenMessage(false, r.server)
        assertTrue(r.engine.media!!.accept(m)); r.engine.retryMedia(m.id)
        assertEquals("COMPLETE", r.store.mediaRecord(m.id)!!.state)
        val before = r.engine.revision.value
        r.engine.media!!.cancel(m.id, delete = true)
        assertTrue("UI revision must move so the thread rebuilds", r.engine.revision.value > before)
        val rows = DesktopUi.buildThread(r.engine.messages(peer), r.own, { "peer" }, { null }, System.currentTimeMillis())
        assertFalse(rows.single().media); assertEquals(DesktopMedia.DELETED_TEXT, rows.single().text)
        r.engine.close(); r.store.close()
    }
}
