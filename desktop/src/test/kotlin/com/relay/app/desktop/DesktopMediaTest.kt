package com.relay.app.desktop

import com.relay.app.media.MediaBody
import com.relay.app.media.MediaCrypto
import com.relay.app.media.MediaRef
import com.relay.app.transport.SendResult
import com.relay.app.transport.mailbox.MailboxMedia
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.imageio.ImageIO

/** WP08 controller behaviour against an in-memory server that enforces what the real one enforces. */
class DesktopMediaTest {
    @get:Rule val tmp = TemporaryFolder()
    private val own = "a".repeat(64)
    private val peer = "b".repeat(64)
    private val base = "https://media.example/v1/media/"

    private class Killed : Error("simulated process kill")

    private inner class Server(val blobs: MutableMap<String, ByteArray> = mutableMapOf()) : DesktopMediaTransport {
        override val urlBase = base
        var uploads = 0; var downloads = 0; var confirms = 0; var confirmOk = true
        var lastRecipients: List<String> = emptyList()
        var beforeUpload: (() -> Unit)? = null
        var afterFirstChunk: (() -> Unit)? = null
        var beforeConfirm: (() -> Unit)? = null
        var failDownload: Throwable? = null
        override fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation, onBytes: (Long) -> Unit) {
            beforeUpload?.invoke()
            val bytes = file.readBytes()
            if (MediaCrypto.sha256Hex(bytes) != hash) throw IOException("Ciphertext size or SHA-256 mismatch")
            uploads++; lastRecipients = recipients; blobs[hash] = bytes; onBytes(bytes.size.toLong())
        }
        override fun download(file: File, hash: String, maxBytes: Long, cancellation: MailboxMedia.Cancellation) {
            downloads++
            failDownload?.let { throw it }
            val bytes = blobs[hash] ?: throw MailboxMedia.HttpFailure(404, "media_not_found", false)
            Files.createFile(file.toPath(), java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            val half = bytes.size / 2
            file.outputStream().use { it.write(bytes, 0, half); it.flush(); afterFirstChunk?.invoke(); it.write(bytes, half, bytes.size - half) }
            if (MediaCrypto.sha256Hex(bytes) != hash) { file.delete(); throw IOException("Ciphertext size or SHA-256 mismatch") }
        }
        override fun confirm(hash: String, cancellation: MailboxMedia.Cancellation): Boolean { confirms++; beforeConfirm?.invoke(); return confirmOk }
    }

    private fun store(name: String): DesktopMessageStore {
        val p = DesktopPaths(tmp.root.toPath().resolve("$name-config"), tmp.root.toPath().resolve("$name-data"))
        return DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile))
    }
    private fun jpeg(w: Int = 40, h: Int = 30): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, x * 6 + y)
        val out = ByteArrayOutputStream(); ImageIO.write(img, "jpg", out); return out.toByteArray()
    }
    private fun png(w: Int = 40, h: Int = 30): ByteArray = ByteArrayOutputStream().also { out -> ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out) }.toByteArray()
    private fun pngHeader(w: Int, h: Int): ByteArray = ByteBuffer.allocate(33).put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        .putInt(13).put("IHDR".toByteArray()).putInt(w).putInt(h).put(byteArrayOf(8, 2, 0, 0, 0)).putInt(0).array()
    private fun mp4(extra: Int = 4000): ByteArray = ByteBuffer.allocate(32 + extra).putInt(24).put("ftyp".toByteArray()).put("isom".toByteArray()).putInt(0).put("isomiso2".toByteArray()).array()
    private fun file(bytes: ByteArray): Path = Files.createTempFile(tmp.root.toPath(), "pick", ".bin").also { Files.write(it, bytes) }

    private class Incoming(val message: DesktopMessage, val blob: ByteArray, val ref: MediaRef)
    private fun incoming(server: Server, plain: ByteArray, mime: String = "image/jpeg", ts: Long = System.currentTimeMillis(), key: String? = null, sizeClaim: Long = plain.size.toLong(), wrap: Boolean = false, urlBase: String = base): Incoming {
        val enc = MediaCrypto.encrypt(plain)
        val stored = if (wrap) com.relay.app.media.MediaContainer.wrap(enc.blob) else enc.blob
        val sha = MediaCrypto.sha256Hex(stored)
        server.blobs[sha] = stored
        val ref = MediaRef(urlBase + sha, sha, key ?: enc.keyBase64, mime, sizeClaim, "hi")
        val id = com.relay.app.transport.PayloadCodec.newId()
        return Incoming(DesktopMessage(id, peer, peer, MediaBody.format(ref), ts, false), stored, ref)
    }
    private fun controller(s: DesktopMessageStore, server: Server, root: Path = tmp.newFolder().toPath(), send: Boolean = false, clock: () -> Long = System::currentTimeMillis) =
        DesktopMedia(root, s, server, own, sendEnabled = send, clock = clock)
    private suspend fun runOut(c: DesktopMedia, s: DesktopMessageStore, id: String, sent: MutableList<List<String>> = mutableListOf()) =
        c.run(id, send = { _, refs -> sent += refs; SendResult.Sent(1) }, sentCopy = { s.saveMedia(s.mediaRecord(it.id)!!.copy(syncPublished = true)) })
    private suspend fun runIn(c: DesktopMedia, id: String) = c.run(id, send = { _, _ -> error("receiver must not send") })
    private fun contains(hay: ByteArray, needle: ByteArray): Boolean = (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
    private fun folderOf(root: Path): List<Path> = Files.newDirectoryStream(root).use { it.toList() }
    private fun allFiles(root: Path): List<Path> = Files.walk(root).use { w -> w.filter { Files.isRegularFile(it) }.toList() }

    // ---- sending -------------------------------------------------------------------------------

    @Test fun `sending is off by default and refuses before touching the source or the network`() {
        store("off").use { s -> val server = Server(); val c = controller(s, server)
            assertThrows(IllegalStateException::class.java) { c.prepare(peer, Path.of("/nonexistent")) }
            assertEquals(0, server.uploads); assertEquals(0, s.countMessages())
        }
    }

    @Test fun `prepare makes a durable bubble and private ciphertext before any network call`() = runBlocking<Unit> {
        store("prep").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root, send = true)
            val plain = jpeg()
            var seenAtUpload: DesktopMediaRecord? = null
            server.beforeUpload = { seenAtUpload = s.mediaRecord(s.messages(peer).single().id); assertTrue("bubble must exist before upload", s.messages(peer).size == 1) }
            val r = c.prepare(peer, file(plain), "  look | here ")
            assertEquals("QUEUED", r.state); assertTrue(r.localSource); assertTrue(r.outgoing)
            val ref = MediaBody.parse(s.messages(peer).single().body)!!
            assertEquals("look  here", ref.caption)
            assertEquals("image/jpeg", ref.mime); assertEquals(plain.size.toLong(), ref.size); assertEquals(base + ref.sha256Hex, ref.url)
            // Ciphertext on disk is the exact blob that will be uploaded; no plaintext file exists anywhere.
            val blobs = allFiles(root); assertEquals(1, blobs.size)
            assertEquals(ref.sha256Hex, MediaCrypto.sha256Hex(Files.readAllBytes(blobs.single())))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(blobs.single())))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(blobs.single().parent)))
            assertFalse("no plaintext at rest", contains(Files.readAllBytes(blobs.single()), plain.copyOfRange(0, 16)))
            val sent = mutableListOf<List<String>>()
            runOut(c, s, r.id, sent)
            assertNotNull(seenAtUpload)
            assertEquals(listOf(peer, own), server.lastRecipients); assertEquals(listOf(listOf(ref.sha256Hex)), sent)
            assertArrayEquals(plain, c.plaintext(r.id))
        }
    }

    @Test fun `outgoing wrong magic oversize and bomb are refused with nothing stored or sent`() {
        store("refuse").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root, send = true)
            assertThrows(IllegalArgumentException::class.java) { c.prepare(peer, file("MZ not media".toByteArray())) }
            assertThrows(IllegalArgumentException::class.java) { c.prepare(peer, file(mp4(0).copyOf((MediaSafety.MAX_PLAIN_BYTES + 1).toInt()))) }
            assertThrows(IllegalArgumentException::class.java) { c.prepare(peer, file(pngHeader(40_000, 40_000))) }
            assertThrows(IllegalArgumentException::class.java) { c.prepare("group:x:1", file(jpeg())) }
            assertEquals(0, s.countMessages()); assertEquals(0, s.mediaRecords().size); assertEquals(0, allFiles(root).size); assertEquals(0, server.uploads)
        }
    }

    @Test fun `symlinked source is refused`() {
        store("link").use { s -> val c = controller(s, Server(), send = true)
            val real = file(jpeg()); val link = tmp.root.toPath().resolve("link.jpg"); Files.createSymbolicLink(link, real)
            assertThrows(IllegalArgumentException::class.java) { c.prepare(peer, link) }
        }
    }

    @Test fun `originator confirms only after the SENT grants are published`() = runBlocking<Unit> {
        store("grant").use { s -> val server = Server(); val c = controller(s, server, send = true)
            val r = c.prepare(peer, file(jpeg()))
            c.run(r.id, send = { _, _ -> SendResult.Sent(1) }, sentCopy = {})   // copy queued but not yet published
            assertEquals(0, server.confirms); assertEquals("FAILED", s.mediaRecord(r.id)!!.state); assertFalse(s.mediaRecord(r.id)!!.permanent)
            c.run(r.id, send = { _, _ -> error("already sent") }, sentCopy = { s.saveMedia(s.mediaRecord(it.id)!!.copy(syncPublished = true)) })
            assertEquals(1, server.confirms); assertEquals("COMPLETE", s.mediaRecord(r.id)!!.state)
            assertEquals(1, server.uploads)
        }
    }

    @Test fun `failed send keeps the exact ciphertext and retry uploads nothing twice`() = runBlocking<Unit> {
        store("retry").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root, send = true)
            val r = c.prepare(peer, file(jpeg()))
            val blobBefore = Files.readAllBytes(allFiles(root).single())
            c.run(r.id, send = { _, _ -> SendResult.Failed("offline", retryable = true) })
            assertEquals("FAILED", s.mediaRecord(r.id)!!.state); assertEquals(1, server.uploads)
            assertArrayEquals(blobBefore, Files.readAllBytes(allFiles(root).single()))
            runOut(c, s, r.id)
            assertEquals(1, server.uploads); assertEquals("COMPLETE", s.mediaRecord(r.id)!!.state)
            assertEquals(1, s.messages(peer).size)
        }
    }

    // ---- receiving -----------------------------------------------------------------------------

    @Test fun `incoming photo is stored encrypted then confirmed and classified as an image`() = runBlocking<Unit> {
        store("in").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root)
            val plain = jpeg(64, 48); val inc = incoming(server, plain)
            assertTrue(c.accept(inc.message))
            server.beforeConfirm = {
                val during = s.mediaRecord(inc.message.id)!!
                assertEquals("durable save must precede confirm", "STORED", during.state)
                val blob = allFiles(root).single(); assertEquals(inc.ref.sha256Hex, MediaCrypto.sha256Hex(Files.readAllBytes(blob)))
            }
            runIn(c, inc.message.id)
            val r = s.mediaRecord(inc.message.id)!!
            assertEquals("COMPLETE", r.state); assertEquals("IMAGE", r.verdict); assertEquals(64L, r.width); assertEquals(48L, r.height); assertTrue(r.confirmed)
            assertEquals(1, server.confirms); assertEquals(1, server.downloads)
            assertArrayEquals(plain, c.plaintext(inc.message.id))
            assertFalse("no plaintext at rest", allFiles(root).any { contains(Files.readAllBytes(it), plain.copyOfRange(0, 16)) })
            assertEquals(null, c.progress(inc.message.id))
        }
    }

    @Test fun `legacy JPEG wrapped uploads from older phones are accepted`() = runBlocking<Unit> {
        store("legacy").use { s -> val server = Server(); val c = controller(s, server)
            val plain = png(); val inc = incoming(server, plain, "image/png", wrap = true)
            assertTrue(c.accept(inc.message)); runIn(c, inc.message.id)
            assertEquals("COMPLETE", s.mediaRecord(inc.message.id)!!.state); assertArrayEquals(plain, c.plaintext(inc.message.id))
        }
    }

    @Test fun `tampered ciphertext is refused shown as failed and never confirmed`() = runBlocking<Unit> {
        store("tamper").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root)
            val inc = incoming(server, jpeg()); server.blobs[inc.ref.sha256Hex] = inc.blob.copyOf().also { it[20] = (it[20] + 1).toByte() }
            c.accept(inc.message); runIn(c, inc.message.id)
            val r = s.mediaRecord(inc.message.id)!!
            assertEquals("FAILED", r.state); assertTrue(r.permanent); assertTrue(r.error!!, r.error!!.contains("integrity", ignoreCase = true))
            assertEquals(0, server.confirms); assertEquals(0, allFiles(root).size)
            assertThrows(IllegalStateException::class.java) { c.plaintext(inc.message.id) }
        }
    }

    @Test fun `authentic hash but forged GCM tag or wrong key is refused`() = runBlocking<Unit> {
        store("forge").use { s -> val server = Server(); val c = controller(s, server)
            val other = MediaCrypto.encrypt(ByteArray(4)).keyBase64
            val inc = incoming(server, jpeg(), key = other)
            c.accept(inc.message); runIn(c, inc.message.id)
            assertEquals("FAILED", s.mediaRecord(inc.message.id)!!.state); assertTrue(s.mediaRecord(inc.message.id)!!.permanent); assertEquals(0, server.confirms)
        }
    }

    @Test fun `declared size that disagrees with the authenticated plaintext is refused`() = runBlocking<Unit> {
        store("size").use { s -> val server = Server(); val c = controller(s, server)
            val plain = jpeg(); val inc = incoming(server, plain, sizeClaim = plain.size + 5L)
            c.accept(inc.message); runIn(c, inc.message.id)
            assertEquals("FAILED", s.mediaRecord(inc.message.id)!!.state); assertTrue(s.mediaRecord(inc.message.id)!!.permanent); assertEquals(0, server.confirms)
        }
    }

    @Test fun `a PNG that is really a JPEG is saved but only as a downloadable attachment`() = runBlocking<Unit> {
        store("confuse").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, jpeg(), "image/png"); c.accept(inc.message); runIn(c, inc.message.id)
            val r = s.mediaRecord(inc.message.id)!!
            assertEquals("COMPLETE", r.state); assertEquals("ATTACHMENT", r.verdict); assertTrue(r.note!!.contains("do not match"))
            assertEquals(1, server.confirms)
            assertNull("must not be offered to the decoder", c.previewBytes(inc.message.id))
        }
    }

    @Test fun `an image bomb header is stored but never handed to a decoder`() = runBlocking<Unit> {
        store("bomb").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, pngHeader(50_000, 50_000) + ByteArray(100), "image/png"); c.accept(inc.message); runIn(c, inc.message.id)
            val r = s.mediaRecord(inc.message.id)!!
            assertEquals("ATTACHMENT", r.verdict); assertTrue(r.note!!.contains("dimensions")); assertNull(c.previewBytes(inc.message.id))
        }
    }

    @Test fun `a video is never previewed in process and opens only through an explicit private export`() = runBlocking<Unit> {
        store("video").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root)
            val plain = mp4(); val inc = incoming(server, plain, "video/mp4"); c.accept(inc.message); runIn(c, inc.message.id)
            assertEquals("VIDEO", s.mediaRecord(inc.message.id)!!.verdict); assertNull(c.previewBytes(inc.message.id))
            assertEquals("nothing exported until the user asks", 1, allFiles(root).size)
            val exported = c.exportForOpen(inc.message.id)
            assertArrayEquals(plain, Files.readAllBytes(exported))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(exported)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(exported.parent)))
            assertTrue(exported.toString().endsWith(".mp4")); assertTrue(exported.startsWith(root))
            c.cancel(inc.message.id, delete = true)
            assertFalse("export must vanish with the media", Files.exists(exported)); assertEquals(0, allFiles(root).size)
        }
    }

    @Test fun `hostile URL outside the configured media origin is never fetched`() = runBlocking<Unit> {
        store("host").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, jpeg(), urlBase = "https://evil.example/v1/media/")
            c.accept(inc.message); runIn(c, inc.message.id)
            assertEquals("UNSUPPORTED", s.mediaRecord(inc.message.id)!!.state); assertEquals(0, server.downloads); assertEquals(0, server.confirms)
        }
    }

    @Test fun `plaintext size above the client bound is refused before any download`() = runBlocking<Unit> {
        store("big").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, jpeg(), sizeClaim = MediaSafety.MAX_PLAIN_BYTES + 1)
            c.accept(inc.message); runIn(c, inc.message.id)
            assertEquals("UNSUPPORTED", s.mediaRecord(inc.message.id)!!.state); assertEquals(0, server.downloads)
        }
    }

    @Test fun `expired and group deliveries are not stored`() {
        store("old").use { s -> val server = Server(); val c = controller(s, server)
            val old = incoming(server, jpeg(), ts = System.currentTimeMillis() - 8L * 24 * 3600 * 1000)
            assertTrue("handled, so the mailbox can ack it", c.accept(old.message)); assertEquals(0, s.countMessages())
            val fresh = incoming(server, jpeg())
            assertThrows(IllegalArgumentException::class.java) { c.accept(fresh.message.copy(conversationId = "group:$peer:1")) }
        }
    }

    @Test fun `offline download is a retryable failure and succeeds later without a second bubble`() = runBlocking<Unit> {
        store("net").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, jpeg()); c.accept(inc.message)
            server.failDownload = IOException("connection refused")
            runIn(c, inc.message.id)
            var r = s.mediaRecord(inc.message.id)!!
            assertEquals("FAILED", r.state); assertFalse(r.permanent); assertTrue(r.error!!.contains("connection", ignoreCase = true) || r.error!!.contains("network", ignoreCase = true)); assertEquals(0, server.confirms)
            server.failDownload = null; runIn(c, inc.message.id)
            r = s.mediaRecord(inc.message.id)!!; assertEquals("COMPLETE", r.state); assertNull(r.error)
            assertEquals(1, s.messages(peer).size)
        }
    }

    @Test fun `server gone and not-a-recipient are permanent honest errors`() = runBlocking<Unit> {
        store("gone").use { s -> val server = Server(); var now = System.currentTimeMillis(); val c = controller(s, server, clock = { now })
            val inc = incoming(server, jpeg()); server.blobs.clear(); c.accept(inc.message); runIn(c, inc.message.id)
            assertFalse("initial 404 can be a pending grant", s.mediaRecord(inc.message.id)!!.permanent)
            now += 61_000; runIn(c, inc.message.id)
            val r = s.mediaRecord(inc.message.id)!!
            assertEquals("FAILED", r.state); assertTrue(r.permanent); assertTrue(r.error!!, r.error!!.contains("no longer available"))
        }
    }

    @Test fun `confirmation failure is durable and retried without downloading again`() = runBlocking<Unit> {
        store("conf").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, jpeg()); c.accept(inc.message); server.confirmOk = false
            runIn(c, inc.message.id)
            assertEquals("CONFIRM_PENDING", s.mediaRecord(inc.message.id)!!.state); assertEquals(1, server.downloads)
            server.confirmOk = true; runIn(c, inc.message.id)
            assertEquals("COMPLETE", s.mediaRecord(inc.message.id)!!.state); assertEquals(1, server.downloads)
        }
    }

    @Test fun `first missing GET after SENT publication retries then verifies bytes`() = runBlocking<Unit> {
        store("pending-grant").use { s -> val server = Server(); var now = System.currentTimeMillis(); val c = controller(s, server, clock = { now })
            val inc = incoming(server, jpeg(), ts = now); c.accept(inc.message)
            server.failDownload = MailboxMedia.HttpFailure(404, "media_not_found", false)
            runIn(c, inc.message.id)
            assertEquals("FAILED", s.mediaRecord(inc.message.id)!!.state)
            assertFalse(s.mediaRecord(inc.message.id)!!.permanent)
            assertEquals(0, server.confirms)
            now += 1000; server.failDownload = null
            runIn(c, inc.message.id)
            assertEquals("COMPLETE", s.mediaRecord(inc.message.id)!!.state)
            assertEquals(1, server.confirms)
        }
    }

    @Test fun `tampering stored ciphertext while confirm pending prevents confirmation after restart`() = runBlocking<Unit> {
        store("pending-tamper").use { s -> val server = Server(); val root = tmp.newFolder().toPath()
            val first = controller(s, server, root); val inc = incoming(server, jpeg()); first.accept(inc.message)
            server.confirmOk = false; runIn(first, inc.message.id)
            assertEquals("CONFIRM_PENDING", s.mediaRecord(inc.message.id)!!.state)
            val blob = allFiles(root).single(); Files.write(blob, Files.readAllBytes(blob).also { it[20] = (it[20] + 1).toByte() })
            server.confirmOk = true
            runIn(controller(s, server, root), inc.message.id)
            assertEquals("FAILED", s.mediaRecord(inc.message.id)!!.state)
            assertTrue(s.mediaRecord(inc.message.id)!!.permanent)
            assertEquals(1, server.confirms)
        }
    }

    @Test fun `confirmation authorization refusal stops retry with explicit error`() = runBlocking<Unit> {
        store("confirm-auth").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, jpeg()); c.accept(inc.message)
            server.beforeConfirm = { throw MailboxMedia.HttpFailure(403, "auth_revoked", false) }
            runIn(c, inc.message.id)
            val row = s.mediaRecord(inc.message.id)!!
            assertEquals("FAILED", row.state); assertTrue(row.permanent)
            assertTrue(row.error!!, row.error!!.contains("server refused this device", ignoreCase = true))
            server.beforeConfirm = null; runIn(c, inc.message.id)
            assertEquals(1, server.confirms)
        }
    }

    @Test fun `real media client maps DELETE forbidden to permanent refusal and rate limit to retry`() {
        var calls = 0
        val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val code = if (++calls == 1) 403 else 429
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(code)
                .message("fixture").body("""{"error":{"code":"denied","retryable":${code == 429}}}"""
                    .toByteArray().toResponseBody("application/json".toMediaType())).build()
        }.build()
        val keys = rust.nostr.sdk.Keys.generate()
        val client = MailboxMedia.Client(MailboxMedia.RequestContext(DesktopNostrSigner(keys)), "https://media.example/", http)
        val transport = DesktopMediaClient(client)
        val digest = "e".repeat(64)
        assertEquals(MediaConfirmation.Refused, transport.confirmOutcome(digest, MailboxMedia.Cancellation()))
        assertEquals(MediaConfirmation.Retry, transport.confirmOutcome(digest, MailboxMedia.Cancellation()))
        assertEquals(2, calls)
    }

    @Test fun `SENT grant gone is a permanent failure that never confirms or re-posts`() = runBlocking<Unit> {
        store("grant-gone").use { s -> val server = Server(); val c = controller(s, server, send = true)
            val r = c.prepare(peer, file(jpeg())); val sha = MediaBody.parse(r.body)!!.sha256Hex
            val http = object : SyncHttp {
                var posts = 0
                override suspend fun post(envelopeJson: String): SyncHttpResult<Boolean> { posts++; return SyncHttpResult.Rejected("media grant gone", final = true) }
                override suspend fun list(after: Long, limit: Int): SyncHttpResult<SyncPage> = error("unused")
                override suspend fun ack(through: Long): SyncHttpResult<Long> = error("unused")
            }
            val pub = SentSyncPublisher("d".repeat(32), s, http)
            val sentCopy: suspend (DesktopMediaRecord) -> Unit = { m ->
                s.queueMediaSync(m.id, pub.buildWires(m.peer, com.relay.app.transport.RelayPayload(m.id, m.ts, m.body)), listOf(sha)); pub.flush()
            }
            c.run(r.id, { _, _ -> SendResult.Sent(1) }, sentCopy)
            val row = s.mediaRecord(r.id)!!
            assertEquals(1, http.posts); assertEquals(0, server.confirms)
            assertEquals("FAILED", row.state); assertTrue(row.permanent); assertFalse(row.syncPublished)
            assertTrue(row.error!!, row.error!!.contains("other devices"))
            c.run(r.id, { _, _ -> error("already sent") }, sentCopy)
            assertEquals(1, http.posts); assertEquals(0, server.confirms)
        }
    }

    @Test fun `pending grant retries are throttled and bounded by a total window`() = runBlocking<Unit> {
        store("grant-window").use { s -> val server = Server(); var now = System.currentTimeMillis(); val c = controller(s, server, clock = { now })
            val inc = incoming(server, jpeg(), ts = now); c.accept(inc.message)
            server.failDownload = MailboxMedia.HttpFailure(404, "media_not_found", false)
            runIn(c, inc.message.id); assertEquals(1, server.downloads)
            runIn(c, inc.message.id); assertEquals("a retry inside the backoff must not hit the server", 1, server.downloads)
            now += 20_000; runIn(c, inc.message.id)
            assertEquals(2, server.downloads); assertFalse(s.mediaRecord(inc.message.id)!!.permanent)
            now += 41_000; runIn(c, inc.message.id)
            assertTrue("still 404 after the window: expired, not hanging", s.mediaRecord(inc.message.id)!!.permanent)
            assertEquals(0, server.confirms)
        }
    }

    @Test fun `download progress is reported while bytes arrive`() = runBlocking<Unit> {
        store("prog").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, jpeg(200, 200)); c.accept(inc.message)
            var seen: Pair<Long, Long>? = null
            server.afterFirstChunk = { seen = c.progress(inc.message.id) }
            runIn(c, inc.message.id)
            val p = seen!!; assertTrue("${p}", p.first in 1 until p.second); assertEquals(inc.blob.size.toLong(), p.second)
        }
    }

    // ---- cancel, delete, retention, recovery ------------------------------------------------------

    @Test fun `cancel during download leaves no partial file and is never resurrected`() = runBlocking<Unit> {
        store("cancel").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root)
            val inc = incoming(server, jpeg(200, 200)); c.accept(inc.message)
            server.afterFirstChunk = { c.cancel(inc.message.id) }
            runIn(c, inc.message.id)
            assertEquals("CANCELLED", s.mediaRecord(inc.message.id)!!.state); assertEquals(0, server.confirms); assertEquals(0, allFiles(root).size)
            server.afterFirstChunk = null; runIn(c, inc.message.id)
            assertEquals("CANCELLED", s.mediaRecord(inc.message.id)!!.state); assertEquals("a cancelled bubble is never downloaded again", 1, server.downloads)
        }
    }

    @Test fun `delete removes ciphertext and plaintext export wipes the key and survives replay`() = runBlocking<Unit> {
        store("del").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root)
            val inc = incoming(server, mp4(), "video/mp4"); c.accept(inc.message); runIn(c, inc.message.id)
            val exported = c.exportForOpen(inc.message.id); assertTrue(Files.exists(exported))
            c.cancel(inc.message.id, delete = true)
            assertEquals(0, allFiles(root).size); assertFalse(Files.exists(exported))
            val r = s.mediaRecord(inc.message.id)!!; assertEquals("DELETED", r.state); assertFalse(r.body.contains("KEY:")); assertFalse(s.messages(peer).single().body.contains("KEY:"))
            assertTrue("replayed delivery is a no-op", c.accept(inc.message)); runIn(c, inc.message.id)
            assertEquals(1, server.downloads); assertEquals(0, allFiles(root).size)
            assertThrows(IllegalStateException::class.java) { c.plaintext(inc.message.id) }
        }
    }

    @Test fun `retention erases expired media bytes record and bubble but keeps fresh ones`() = runBlocking<Unit> {
        store("ret").use { s -> val server = Server(); val root = tmp.newFolder().toPath()
            var now = System.currentTimeMillis(); val c = controller(s, server, root, clock = { now })
            val a = incoming(server, jpeg(), ts = now - 6L * 24 * 3600 * 1000); val b = incoming(server, jpeg(50, 50), ts = now)
            c.accept(a.message); c.accept(b.message); runIn(c, a.message.id); runIn(c, b.message.id)
            val exp = c.exportForOpen(a.message.id)
            assertEquals(2, allFiles(root).size - 1)
            now += 2L * 24 * 3600 * 1000   // a is now 8 days old
            assertEquals(1, c.purgeExpired())
            assertFalse(Files.exists(exp)); assertNull(s.mediaRecord(a.message.id)); assertNotNull(s.mediaRecord(b.message.id))
            assertEquals(listOf(b.message.id), s.messages(peer).map { it.id }); assertEquals(1, allFiles(root).size)
            assertEquals(0, c.purgeExpired())
        }
    }

    @Test fun `process killed mid download recovers on restart with one clean download`() = runBlocking<Unit> {
        store("kill").use { s -> val server = Server(); val root = tmp.newFolder().toPath()
            val inc = incoming(server, jpeg(200, 200))
            val first = controller(s, server, root); first.accept(inc.message)
            server.afterFirstChunk = { throw Killed() }
            try { runIn(first, inc.message.id); fail("kill must propagate") } catch (e: Killed) {}
            assertEquals("TRANSFERRING", s.mediaRecord(inc.message.id)!!.state)
            assertTrue("a partial file is what a kill leaves behind", allFiles(root).any { Files.size(it) > 0 })
            server.afterFirstChunk = null
            val second = controller(s, server, root); second.sweep()
            assertEquals("sweep removes the partial", 0, allFiles(root).size)
            runIn(second, inc.message.id)
            assertEquals("COMPLETE", s.mediaRecord(inc.message.id)!!.state); assertEquals(2, server.downloads); assertEquals(1, server.confirms)
            assertEquals(1, allFiles(root).size)
        }
    }

    @Test fun `process killed after upload but before send resumes without re-encrypting or re-uploading`() = runBlocking<Unit> {
        store("killup").use { s -> val server = Server(); val root = tmp.newFolder().toPath()
            val first = controller(s, server, root, send = true); val r = first.prepare(peer, file(jpeg()))
            val blob = Files.readAllBytes(allFiles(root).single())
            try { first.run(r.id, send = { _, _ -> throw Killed() }); fail("kill must propagate") } catch (e: Killed) {}
            assertTrue(s.mediaRecord(r.id)!!.uploaded); assertFalse(s.mediaRecord(r.id)!!.sent)
            val second = controller(s, server, root, send = true); second.sweep()
            val sent = mutableListOf<List<String>>(); runOut(second, s, r.id, sent)
            assertEquals(1, server.uploads); assertEquals(1, sent.size); assertArrayEquals(blob, Files.readAllBytes(allFiles(root).single()))
            assertEquals("COMPLETE", s.mediaRecord(r.id)!!.state)
        }
    }

    @Test fun `startup sweep removes orphans stale staging and exports of dead records but keeps live ones`() = runBlocking<Unit> {
        store("sweep").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root)
            val inc = incoming(server, jpeg()); c.accept(inc.message); runIn(c, inc.message.id)
            val orphan = root.resolve("0".repeat(64)); Files.createDirectory(orphan); Files.write(orphan.resolve("blob"), byteArrayOf(1))
            val stale = root.resolve(".stage-123"); Files.write(stale, byteArrayOf(1))
            c.sweep()
            assertFalse(Files.exists(orphan)); assertFalse(Files.exists(stale)); assertEquals(1, allFiles(root).size)
            assertArrayEquals(c.plaintext(inc.message.id), c.plaintext(inc.message.id))
        }
    }

    @Test fun `local ciphertext that was altered on disk is detected before viewing saving or exporting`() = runBlocking<Unit> {
        store("rot").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root)
            val inc = incoming(server, mp4(), "video/mp4"); c.accept(inc.message); runIn(c, inc.message.id)
            val blob = allFiles(root).single(); val bytes = Files.readAllBytes(blob); bytes[30] = (bytes[30] + 1).toByte(); Files.write(blob, bytes)
            assertThrows(IllegalStateException::class.java) { c.plaintext(inc.message.id) }
            assertThrows(IllegalStateException::class.java) { c.exportForOpen(inc.message.id) }
            assertThrows(IllegalStateException::class.java) { c.saveAs(inc.message.id, tmp.root.toPath().resolve("never.mp4")) }
            assertFalse(Files.exists(tmp.root.toPath().resolve("never.mp4")))
        }
    }

    @Test fun `save as writes verified bytes once and refuses overwrite and symlinks`() = runBlocking<Unit> {
        store("save").use { s -> val server = Server(); val c = controller(s, server)
            val plain = jpeg(); val inc = incoming(server, plain); c.accept(inc.message); runIn(c, inc.message.id)
            val target = tmp.root.toPath().resolve("out.jpg")
            c.saveAs(inc.message.id, target); assertArrayEquals(plain, Files.readAllBytes(target))
            assertThrows(IllegalArgumentException::class.java) { c.saveAs(inc.message.id, target) }
            val real = tmp.newFolder().toPath(); val link = tmp.root.toPath().resolve("dirlink"); Files.createSymbolicLink(link, real)
            assertThrows(IllegalArgumentException::class.java) { c.saveAs(inc.message.id, link.resolve("x.jpg")) }
        }
    }

    @Test fun `private quota stops a download before any byte is fetched`() = runBlocking<Unit> {
        store("quota").use { s -> val server = Server(); val c = DesktopMedia(tmp.newFolder().toPath(), s, server, own, quotaBytes = 1000)
            val inc = incoming(server, jpeg(200, 200)); c.accept(inc.message); runIn(c, inc.message.id)
            assertEquals("FAILED", s.mediaRecord(inc.message.id)!!.state); assertEquals(0, server.downloads)
        }
    }

    // ---- second-guard tests added after the mutation survivors (see evidence) ------------------------

    @Test fun `cancel that lands while the send callback runs is not resurrected by the following state update`() = runBlocking<Unit> {
        store("cancelsend").use { s -> val server = Server(); val root = tmp.newFolder().toPath(); val c = controller(s, server, root, send = true)
            val r = c.prepare(peer, file(jpeg()))
            c.run(r.id, send = { rec, _ -> c.cancel(rec.id); SendResult.Sent(1) })
            assertEquals("CANCELLED", s.mediaRecord(r.id)!!.state); assertFalse("sent must not be recorded over a tombstone", s.mediaRecord(r.id)!!.sent)
            assertEquals(0, server.confirms); assertEquals(0, allFiles(root).size)
        }
    }

    @Test fun `a source reached through a symlinked directory is refused`() {
        store("linkdir").use { s -> val c = controller(s, Server(), send = true)
            val realDir = tmp.newFolder().toPath(); val real = realDir.resolve("a.jpg"); Files.write(real, jpeg())
            val link = tmp.root.toPath().resolve("viadir"); Files.createSymbolicLink(link, realDir)
            val e = assertThrows(IllegalArgumentException::class.java) { c.prepare(peer, link.resolve("a.jpg")) }
            assertTrue(e.message!!, e.message!!.contains("Symlink"))
        }
    }

    @Test fun `outgoing bomb is refused for its dimensions not by a decoder crash`() {
        store("bombmsg").use { s -> val c = controller(s, Server(), send = true)
            val e = assertThrows(IllegalArgumentException::class.java) { c.prepare(peer, file(pngHeader(40_000, 40_000))) }
            assertTrue(e.message!!, e.message!!.contains("dimensions"))
        }
    }

    @Test fun `preview is re-verified even when the journal claims an image`() = runBlocking<Unit> {
        store("reverify").use { s -> val server = Server(); val c = controller(s, server)
            val inc = incoming(server, pngHeader(50_000, 50_000) + ByteArray(100), "image/png"); c.accept(inc.message); runIn(c, inc.message.id)
            s.saveMedia(s.mediaRecord(inc.message.id)!!.copy(verdict = "IMAGE"))   // a corrupted/hostile journal row
            assertNull(c.previewBytes(inc.message.id))
        }
    }
}
