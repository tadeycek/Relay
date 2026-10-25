package com.relay.app.desktop

import com.relay.app.files.*
import com.relay.app.transport.*
import com.relay.app.transport.mailbox.MailboxMedia
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.io.*

class DesktopFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val own = "a".repeat(64)
    private val peer = "b".repeat(64)
    private class Media(val dir: Path) : DesktopFileMedia {
        override val urlBase = "https://files.example/v1/media/"
        var uploads = 0; var downloads = 0; var confirms = 0
        var failDownload = Int.MAX_VALUE
        var confirmOk = true
        var tamper = false
        var beforeUpload: (() -> Unit)? = null
        var onDownload: (() -> Unit)? = null
        override fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation) {
            beforeUpload?.invoke(); assertEquals(listOf("b".repeat(64), "a".repeat(64)), recipients); uploads++
            Files.copy(file.toPath(), dir.resolve(hash), StandardCopyOption.REPLACE_EXISTING)
        }
        override fun download(file: File, hash: String, size: Long, cancellation: MailboxMedia.Cancellation) {
            downloads++; if (downloads == failDownload) throw IOException("interrupted")
            Files.copy(dir.resolve(hash), file.toPath()); Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            if (tamper) RandomAccessFile(file, "rw").use { it.seek(20); it.write(17) }
            onDownload?.invoke()
        }
        override fun confirm(hash: String, cancellation: MailboxMedia.Cancellation): Boolean { confirms++; return confirmOk }
    }
    private fun store(name: String): DesktopMessageStore {
        val p = DesktopPaths(tmp.root.toPath().resolve("$name-config"), tmp.root.toPath().resolve("$name-data"))
        return DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile))
    }
    private fun source(size: Long): Path {
        val p = Files.createTempFile(tmp.root.toPath(), "source", ".bin")
        Files.newOutputStream(p).use { out -> var left = size; val b = ByteArray(65536) { (it % 251).toByte() }; while (left > 0) { val n = minOf(left, b.size.toLong()).toInt(); out.write(b, 0, n); left -= n } }
        return p
    }
    private fun media() = Media(tmp.newFolder().toPath())
    private suspend fun send(c: DesktopFiles, s: DesktopMessageStore, r: DesktopFileRecord) {
        c.run(r.id, { _, refs -> assertTrue(refs.size <= 8); SendResult.Sent(1) }, { s.saveFile(s.fileRecord(it.id)!!.copy(syncPublished = true)) })
    }
    private fun accept(c: DesktopFiles, r: DesktopFileRecord, body: String = r.body) {
        assertTrue(c.accept(DesktopMessage(r.id, own, own, body, r.ts, false)))
    }
    @Test fun `file controller exists rather than treating manifests as text`() { assertNotNull(Class.forName("com.relay.app.desktop.DesktopFiles")) }

    @Test fun `default off refuses before source or network access`() {
        store("off").use { s -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own)
            assertThrows(IllegalStateException::class.java) { c.prepare(peer, Path.of("/nonexistent")) }; assertEquals(0, m.uploads); assertEquals(0, s.countMessages())
        }
    }
    @Test fun `bubble manifest original id and flags are durable before upload`() = runBlocking<Unit> {
        val root = tmp.newFolder().toPath(); val m = media(); val s = store("durable")
        val c = DesktopFiles(root, s, m, own, true); val r = c.prepare(peer, source(10))
        m.beforeUpload = { assertEquals(r.id, s.messages(peer).single().id); assertEquals(r.body, s.fileRecord(r.id)!!.body); assertFalse(s.fileRecord(r.id)!!.parts.single()) }
        send(c, s, r); assertEquals("COMPLETE", s.fileRecord(r.id)!!.state); s.close()
        store("durable").use { reopened -> assertTrue(reopened.fileRecord(r.id)!!.confirmed.single()); assertEquals(r.id, FileManifest.fromAuthenticatedPayload(RelayPayload(r.id, r.ts, reopened.fileRecord(r.id)!!.body)) { true }!!.fileId) }
    }
    @Test fun `empty small and generated sixty MiB stream desktop to JVM peer and reverse`() = runBlocking<Unit> {
        for (size in listOf(0L, 123L, 60L * 1024 * 1024)) {
            val m = media(); val original = source(size)
            store("s$size").use { s -> store("r$size").use { receiver ->
                val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true)
                val r = c.prepare(peer, original); send(c, s, r)
                val manifest = FileManifest.fromAuthenticatedPayload(RelayPayload(r.id, r.ts, r.body)) { true }!!
                val peerOutput = Files.createTempFile(tmp.root.toPath(), "peer-", ".bin")
                Files.newOutputStream(peerOutput).use { out -> assertTrue(FileAssembler().assemble(manifest, r.id, { _, p -> Files.newInputStream(m.dir.resolve(p.sha256Hex)) }, out)) }
                assertEquals(DesktopFiles.digest(original), DesktopFiles.digest(peerOutput))
                // A JVM phone stand-in produces a fresh manifest and ciphertext, not a desktop loopback.
                val id = "phone-$size"
                val body = Files.newInputStream(original).use { input -> FileSender().prepare(input, size, id, "phone.bin", "application/octet-stream", m.urlBase) { _, p, bytes -> Files.newOutputStream(m.dir.resolve(p.sha256Hex)).use { bytes.copyTo(it, 65536) } } }
                val incoming = DesktopFileRecord(id, own, own, 1, false, FileBody.encode(body), "QUEUED", emptyList(), emptyList())
                val receive = DesktopFiles(tmp.newFolder().toPath(), receiver, m, peer) // send OFF, receive ON
                accept(receive, incoming); receive.run(id, { _, _ -> error("receive cannot send") })
                val saved = tmp.root.toPath().resolve("saved-$size"); receive.saveAs(id, saved)
                assertEquals(size, Files.size(saved)); assertEquals(DesktopFiles.digest(original), DesktopFiles.digest(saved)); assertEquals("COMPLETE", receiver.fileRecord(id)!!.state)
            } }
        }
    }
    @Test fun `interrupted download resumes completed verified parts after store restart`() = runBlocking<Unit> {
        val m = media(); val root = tmp.newFolder().toPath()
        val sender = store("resume-s"); val c = DesktopFiles(tmp.newFolder().toPath(), sender, m, own, true)
        val r = c.prepare(peer, source(27L * 1024 * 1024)); send(c, sender, r)
        var s = store("resume-r"); var receive = DesktopFiles(root, s, m, peer)
        accept(receive, r); m.failDownload = 2; receive.run(r.id, { _, _ -> error("no send") })
        assertEquals(listOf(true, false), s.fileRecord(r.id)!!.parts); assertEquals(0, m.confirms - r.parts.size)
        s.close(); s = store("resume-r"); receive = DesktopFiles(root, s, m, peer); m.failDownload = Int.MAX_VALUE
        receive.run(r.id, { _, _ -> error("no send") }); assertEquals(3, m.downloads); assertEquals("COMPLETE", s.fileRecord(r.id)!!.state)
        s.close(); sender.close()
    }
    @Test fun `retry reuses exact encrypted parts and no reencryption`() = runBlocking<Unit> {
        store("retry").use { s -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(99))
            c.run(r.id, { _, _ -> SendResult.Failed("lost response", true) }); val hashes = Files.list(m.dir).use { it.map { p -> DesktopFiles.digest(p) }.toList() }
            send(c, s, r); assertEquals(1, m.uploads); assertEquals(hashes, Files.list(m.dir).use { it.map { p -> DesktopFiles.digest(p) }.toList() }); assertTrue(s.fileRecord(r.id)!!.sent)
        }
    }
    @Test fun `tampered ciphertext never confirms or publishes`() = runBlocking<Unit> {
        store("tamper-s").use { s -> store("tamper-r").use { receiver -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(77)); send(c, s, r)
            val old = m.confirms; m.tamper = true; val receive = DesktopFiles(tmp.newFolder().toPath(), receiver, m, peer); accept(receive, r); receive.run(r.id, { _, _ -> error("no send") })
            assertEquals(old, m.confirms); val target = tmp.root.toPath().resolve("tampered"); assertThrows(Exception::class.java) { receive.saveAs(r.id, target) }; assertFalse(Files.exists(target))
        } }
    }
    @Test fun `wrong whole plaintext digest never publishes or confirms`() = runBlocking<Unit> {
        store("hash-s").use { s -> store("hash-r").use { receiver -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(7)); send(c, s, r)
            val parsed = FileManifest.fromAuthenticatedPayload(RelayPayload(r.id, r.ts, r.body)) { true }!!.body
            val wrong = FileBody.encode(parsed.copy(sha256Hex = "0".repeat(64))); val old = m.confirms
            val receive = DesktopFiles(tmp.newFolder().toPath(), receiver, m, peer); accept(receive, r, wrong); receive.run(r.id, { _, _ -> error("no send") }); assertEquals(old, m.confirms)
            val target = tmp.root.toPath().resolve("wronghash"); assertThrows(Exception::class.java) { receive.saveAs(r.id, target) }; assertFalse(Files.exists(target))
        } }
    }
    @Test fun `id binding refuses reassociated manifest`() {
        store("id-s").use { s -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(1)); assertFalse(c.accept(DesktopMessage("different-id", peer, peer, r.body, 1, false))) }
    }
    @Test fun `quota source symlink and group fail closed before upload`() {
        store("safety").use { s -> val m = media(); val root = tmp.newFolder().toPath(); val c = DesktopFiles(root, s, m, own, true, quotaBytes = 65540)
            val source = source(5); assertThrows(IllegalArgumentException::class.java) { c.prepare(peer, source) }
            assertThrows(IllegalArgumentException::class.java) { c.prepare("group:x", source) }
            val link = tmp.root.toPath().resolve("source-link"); Files.createSymbolicLink(link, source)
            assertThrows(IllegalArgumentException::class.java) { c.prepare(peer, link) }; assertEquals(0, m.uploads)
            val dirLink = tmp.root.toPath().resolve("dir-link"); Files.createSymbolicLink(dirLink, root)
            assertThrows(IllegalArgumentException::class.java) { DesktopFiles(dirLink, s, m, own, true) }
        }
    }
    @Test fun `save collision and symlink ancestors preserve existing bytes`() = runBlocking<Unit> {
        store("collision").use { s -> val m = media(); val root = tmp.newFolder().toPath(); val c = DesktopFiles(root, s, m, own, true); val r = c.prepare(peer, source(21)); send(c, s, r)
            val existing = tmp.root.toPath().resolve("existing"); Files.writeString(existing, "keep"); assertThrows(IllegalArgumentException::class.java) { c.saveAs(r.id, existing) }; assertEquals("keep", Files.readString(existing))
            val dir = tmp.newFolder().toPath(); val link = tmp.root.toPath().resolve("target-link"); Files.createSymbolicLink(link, dir); assertThrows(IllegalArgumentException::class.java) { c.saveAs(r.id, link.resolve("new")) }; assertFalse(Files.exists(dir.resolve("new")))
            val saved = c.saveAs(r.id, dir.resolve("safe")); assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(saved))); assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root)))
        }
    }
    @Test fun `cancel during downloaded part prevents confirmation and publication`() = runBlocking<Unit> {
        store("cancel-s").use { s -> store("cancel-r").use { receiver -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(17)); send(c, s, r)
            val receive = DesktopFiles(tmp.newFolder().toPath(), receiver, m, peer); accept(receive, r); val old = m.confirms; m.onDownload = { receive.cancel(r.id, true) }; receive.run(r.id, { _, _ -> error("no send") }); assertEquals("DELETED", receiver.fileRecord(r.id)!!.state); assertEquals(old, m.confirms)
            assertThrows(Exception::class.java) { receive.saveAs(r.id, tmp.root.toPath().resolve("cancelled")) }
        } }
    }
    @Test fun `confirmation failure is durable and retry does not redownload`() = runBlocking<Unit> {
        store("confirm-s").use { s -> var receiver = store("confirm-r"); val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(33)); send(c, s, r)
            val root = tmp.newFolder().toPath(); var receive = DesktopFiles(root, receiver, m, peer); accept(receive, r); m.confirmOk = false; receive.run(r.id, { _, _ -> error("no send") }); assertEquals("CONFIRM_PENDING", receiver.fileRecord(r.id)!!.state)
            receiver.close(); receiver = store("confirm-r"); receive = DesktopFiles(root, receiver, m, peer); m.confirmOk = true; receive.run(r.id, { _, _ -> error("no send") }); assertEquals(1, m.downloads); assertEquals("COMPLETE", receiver.fileRecord(r.id)!!.state); receiver.close()
        }
    }
    @Test fun `originator waits for durable SENT grant publication before confirming`() = runBlocking<Unit> {
        store("sent-wait").use { s -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(12))
            c.run(r.id, { _, _ -> SendResult.Sent(1) }); assertEquals(0, m.confirms); assertFalse(s.fileRecord(r.id)!!.syncPublished)
            send(c, s, r); assertEquals(1, m.confirms)
        }
    }
    @Test fun `SENT file commits original id creates real receive work not text only`() {
        store("sent-record").use { s -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(1))
            store("sent-other").use { other -> other.commitSync(DesktopMessage(r.id, peer, own, r.body, r.ts, true), 5); assertEquals(5L, other.syncCursor()); assertFalse(other.fileRecord(r.id)!!.localSource); assertEquals(r.id, other.fileRecords().single().id) }
        }
    }
    @Test fun `sync authenticated media references bounded never silently omitted`() {
        val refs = listOf("a".repeat(64), "b".repeat(64)); val j = org.json.JSONObject(SyncWire.envelope("wire", 1, 1, refs)); assertEquals(org.json.JSONArray(refs).toString(), j.getJSONArray("media").toString())
        assertThrows(IllegalArgumentException::class.java) { SyncWire.envelope("wire", 1, 1, List(9) { "%064x".format(it) }) }
    }
    @Test fun `media transport retry replays durable exact body refs and enrolled signature`() = runBlocking<Unit> {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}"))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("""{"v":1,"results":[{"status":"stored","seq":1}],"media":[{"hash":"${"e".repeat(64)}","status":"attached"}]}"""))
        server.start()
        try { store("wire").use { s ->
            val keys = rust.nostr.sdk.Keys.generate(); val auth = rust.nostr.sdk.Keys.generate(); val refs = listOf("e".repeat(64))
            val device = DesktopDeviceAuth("d".repeat(32), DesktopNostrSigner(auth))
            val recipient = rust.nostr.sdk.Keys.generate().publicKey().toHex()
            val t = DesktopMailboxTransport(server.url("/").toString(), keys, DesktopNostrSigner(keys), s, device)
            val payload = RelayPayload("file-retry-wire", 1, "already HPKE encrypted reference")
            assertTrue(t.sendWithMedia(recipient, payload, refs) is SendResult.Failed)
            val a = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("No request received"); assertTrue(t.sendWithMedia(recipient, payload, refs) is SendResult.Sent)
            val b = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("No retry request received"); val first = a.body.readUtf8(); assertEquals(first, b.body.readUtf8())
            assertEquals(org.json.JSONArray(refs).toString(), org.json.JSONObject(first).getJSONArray("media").toString())
            val authJson = org.json.JSONObject(String(java.util.Base64.getDecoder().decode(a.getHeader("Authorization")!!.removePrefix("Nostr "))))
            assertEquals(auth.publicKey().toHex(), authJson.getString("pubkey"))
            assertEquals("/v1/send?device=${device.deviceId}", a.path)
            val tags = authJson.getJSONArray("tags"); val payloadTag = (0 until tags.length()).map { tags.getJSONArray(it) }.single { it.getString(0) == "payload" }.getString(1)
            assertEquals(SyncWire.sha256Hex(first.toByteArray()), payloadTag)
        } } finally { server.shutdown() }
    }
    @Test fun `stored delivery with gone media fails permanently rather than replaying the id`() = runBlocking<Unit> {
        val server = okhttp3.mockwebserver.MockWebServer()
        val hash = "e".repeat(64)
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"v":1,"results":[{"status":"stored","seq":1}],"media":[{"hash":"$hash","status":"gone"}]}"""))
        server.start()
        try { store("gone-media-reply").use { s ->
            val keys = rust.nostr.sdk.Keys.generate(); val t = DesktopMailboxTransport(server.url("/").toString(), keys, DesktopNostrSigner(keys), s)
            val recipient = rust.nostr.sdk.Keys.generate().publicKey().toHex()
            val result = t.sendWithMedia(recipient, RelayPayload("gone-media-delivery", 1, "sealed"), listOf(hash))
            assertTrue(result is SendResult.Failed)
            assertFalse((result as SendResult.Failed).retryable)
            assertTrue(result.reason.contains("media", ignoreCase = true))
            assertEquals(1, server.requestCount)
        } } finally { server.shutdown() }
    }
    @Test fun `invalid media refs fail permanently without any network`() = runBlocking<Unit> {
        store("badrefs").use { s -> val keys = rust.nostr.sdk.Keys.generate(); val t = DesktopMailboxTransport("http://127.0.0.1:1", keys, DesktopNostrSigner(keys), s)
            val result = t.sendWithMedia(peer, RelayPayload("bad-ref", 1, "hi"), List(9) { "%064x".format(it) }) as SendResult.Failed
            assertFalse(result.retryable)
        }
    }
    @Test fun `filesystem failure during upload leaves retryable durable bubble`() {
        store("denied").use { s -> val m = media(); val root = tmp.newFolder().toPath(); val c = DesktopFiles(root, s, m, own, true)
            val src = source(99)
            // Deterministic injected FileSystemException at media-file access, not real disk exhaustion.
            val denied = object : DesktopFileMedia by m { override fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation) { throw java.nio.file.FileSystemException(file.toString(), null, "No space left on device (injected)") } }
            val c2 = DesktopFiles(root, s, denied, own, true); val r = c2.prepare(peer, src)
            runBlocking { c2.run(r.id, { _, _ -> error("must not send") }) }
            assertEquals("FAILED", s.fileRecord(r.id)!!.state); assertFalse(s.fileRecord(r.id)!!.parts.single()); assertEquals(0, m.uploads)
        }
    }
    @Test fun `default off also fences persisted outgoing retry`() = runBlocking<Unit> {
        store("retry-off").use { s -> val m = media(); val root = tmp.newFolder().toPath(); val enabled = DesktopFiles(root, s, m, own, true); val r = enabled.prepare(peer, source(3))
            val disabled = DesktopFiles(root, s, m, own)
            disabled.run(r.id, { _, _ -> error("default off must not send") })
            assertEquals(0, m.uploads); assertEquals("SEND_DISABLED", s.fileRecord(r.id)!!.state)
        }
    }
    @Test fun `file sync refs survive rejection retry and reopen with exact signed request bytes`() = runBlocking<Unit> {
        val m = media(); var s = store("sync-retry"); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(2))
        val refs = FileManifest.fromAuthenticatedPayload(RelayPayload(r.id, r.ts, r.body)) { true }!!.body.parts.map { it.sha256Hex }
        val http = object : SyncHttp {
            val bodies = mutableListOf<String>(); var ok = false
            override suspend fun post(envelopeJson: String): SyncHttpResult<Boolean> { bodies += envelopeJson; return if (ok) SyncHttpResult.Ok(false) else SyncHttpResult.Rejected("rejected") }
            override suspend fun list(after: Long, limit: Int): SyncHttpResult<SyncPage> = error("unused")
            override suspend fun ack(through: Long): SyncHttpResult<Long> = error("unused")
        }
        s.queueFileSync(r.id, listOf("sealed-SENT-wire"), refs)
        SentSyncPublisher("d".repeat(32), s, http).flush(); assertEquals(1, s.outbox(10).size); assertFalse(s.fileRecord(r.id)!!.syncPublished)
        s.close(); s = store("sync-retry"); assertEquals(refs, s.outbox(10).single().mediaDigests)
        http.ok = true; SentSyncPublisher("d".repeat(32), s, http).flush()
        // The wire body and media list must repeat exactly; `expires` is computed at post time (review B1):
        // signing it from createdAt made any retry after the server window a permanent 400. The signature
        // is on the Nostr auth header, which is fresh per POST, so byte identity buys nothing here.
        fun withoutExpiry(b: String) = org.json.JSONObject(b).apply { remove("expires") }.toString()
        assertEquals(withoutExpiry(http.bodies[0]), withoutExpiry(http.bodies[1]))
        for (b in http.bodies) assertTrue("expiry must be in the future", org.json.JSONObject(b).getLong("expires") > System.currentTimeMillis())
        for (b in http.bodies) assertEquals("every part and retry repeats the same media list", org.json.JSONArray(refs).toString(), org.json.JSONObject(b).getJSONArray("media").toString()); assertTrue(s.fileRecord(r.id)!!.syncPublished); assertTrue(s.outbox(10).isEmpty()); s.close()
    }
    @Test fun `local plaintext corruption is rechecked before save as`() = runBlocking<Unit> {
        store("local-corruption").use { s -> val m = media(); val root = tmp.newFolder().toPath(); val c = DesktopFiles(root, s, m, own, true); val r = c.prepare(peer, source(17)); send(c, s, r)
            val complete = Files.walk(root).use { it.filter { p -> p.fileName.toString() == "complete" }.findFirst().orElseThrow() }
            Files.write(complete, ByteArray(17) { 12 })
            val target = tmp.root.toPath().resolve("corrupt-save")
            assertThrows(IllegalArgumentException::class.java) { c.saveAs(r.id, target) }; assertFalse(Files.exists(target))
        }
    }
    @Test fun `executable warnings include desktop launchers and scripts`() { for (name in listOf("evil.desktop", "go.sh", "run.AppImage", "start.exe", "a.jar")) assertTrue(DesktopFiles.needsOpenWarning(name)); assertFalse(DesktopFiles.needsOpenWarning("readme.txt")) }

    @Test fun `cancel during upload prevents send publication and confirmation`() = runBlocking<Unit> {
        store("cancel-up").use { s -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(40))
            m.beforeUpload = { c.cancel(r.id) }
            c.run(r.id, { _, _ -> error("cancelled transfer must not send") }, { error("no SENT copy") })
            assertEquals("CANCELLED", s.fileRecord(r.id)!!.state); assertEquals(0, m.confirms); assertTrue(s.outbox(10).isEmpty()); assertFalse(s.fileRecord(r.id)!!.sent)
        }
    }
    @Test fun `deleted transfer is not resurrected by replayed delivery`() = runBlocking<Unit> {
        store("tomb-s").use { s -> store("tomb-r").use { receiver -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(9)); send(c, s, r)
            val receive = DesktopFiles(tmp.newFolder().toPath(), receiver, m, peer); accept(receive, r); receive.cancel(r.id, true)
            accept(receive, r); receiver.commitSync(DesktopMessage(r.id, own, own, r.body, r.ts, false), 3); val before = m.downloads
            receive.run(r.id, { _, _ -> error("no send") })
            assertEquals("DELETED", receiver.fileRecord(r.id)!!.state); assertEquals(before, m.downloads)
            assertThrows(Exception::class.java) { receive.saveAs(r.id, tmp.root.toPath().resolve("resurrected")) }
        } }
    }
    @Test fun `symlinked persisted part is refused before upload`() = runBlocking<Unit> {
        store("part-link").use { s -> val m = media(); val root = tmp.newFolder().toPath(); val c = DesktopFiles(root, s, m, own, true); val r = c.prepare(peer, source(30))
            val part = Files.walk(root).use { it.filter { p -> p.fileName.toString() == "part-0" }.findFirst().orElseThrow() }
            val outside = tmp.root.toPath().resolve("outside"); Files.copy(part, outside); Files.delete(part); Files.createSymbolicLink(part, outside)
            c.run(r.id, { _, _ -> error("must not send") }); assertEquals("FAILED", s.fileRecord(r.id)!!.state); assertEquals(0, m.uploads)
        }
    }
    @Test fun `incoming file over private quota fails before any download`() = runBlocking<Unit> {
        store("quota-s").use { s -> store("quota-r").use { receiver -> val m = media(); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true); val r = c.prepare(peer, source(50_000)); send(c, s, r)
            val receive = DesktopFiles(tmp.newFolder().toPath(), receiver, m, peer, quotaBytes = 70_000); accept(receive, r); receive.run(r.id, { _, _ -> error("no send") })
            assertEquals("FAILED", receiver.fileRecord(r.id)!!.state); assertEquals(0, m.downloads); assertEquals(0, m.confirms - 1)
        } }
    }
    @Test fun `attach is blocked with an explicit reason for disabled and group conversations`() {
        assertNull(DesktopFiles.attachBlockReason(peer, true))
        assertTrue(DesktopFiles.attachBlockReason(peer, false)!!.contains("disabled"))
        assertTrue(DesktopFiles.attachBlockReason("group:abc", true)!!.contains("group", ignoreCase = true))
        assertNotNull(DesktopFiles.attachBlockReason(null, true))
    }
    @Test fun `unsupported message types never render raw wire text`() {
        assertEquals("hello", DesktopFiles.displayText("hello")); assertEquals("Unsupported message type", DesktopFiles.displayText("TYPE:HOLOGRAM|secret"))
    }

    // ---- review findings -------------------------------------------------------------------------

    /** Review B2: Delete on a COMPLETE file used to leave the decrypted file and every ciphertext part. */
    @Test fun `deleting a complete file removes plaintext and ciphertext from disk`() = runBlocking<Unit> {
        val root = tmp.newFolder().toPath(); val m = media(); val s = store("delete")
        val c = DesktopFiles(root, s, m, own, true)
        val r = c.prepare(peer, source(200_000))
        send(c, s, r)
        assertEquals("COMPLETE", s.fileRecord(r.id)!!.state)
        assertTrue(regularFiles(root) > 0)          // parts and the assembled file exist
        c.cancel(r.id, delete = true)
        assertEquals("DELETED", s.fileRecord(r.id)!!.state)
        assertEquals("no plaintext or ciphertext may survive a delete", 0, regularFiles(root))
        s.close()
    }

    @Test fun `cancel keeps the record but still reclaims its bytes`() = runBlocking<Unit> {
        val root = tmp.newFolder().toPath(); val m = media(); val s = store("cancelbytes")
        val c = DesktopFiles(root, s, m, own, true)
        val r = c.prepare(peer, source(50_000))
        send(c, s, r)
        c.cancel(r.id)
        assertEquals("CANCELLED", s.fileRecord(r.id)!!.state)
        assertEquals(0, regularFiles(root))
        s.close()
    }

    /** Review B2: a crash left folders and stage files behind that nothing ever reclaimed. */
    @Test fun `startup sweep removes orphans and stale staging but keeps live records`() = runBlocking<Unit> {
        val root = tmp.newFolder().toPath(); val m = media(); val s = store("sweep")
        val c = DesktopFiles(root, s, m, own, true)
        val live = c.prepare(peer, source(10_000))
        val gone = c.prepare(peer, source(10_000))
        c.cancel(gone.id, delete = true)
        val orphan = Files.createDirectories(root.resolve("f".repeat(64)))
        Files.write(orphan.resolve("part-0"), ByteArray(10))
        val stage = Files.createFile(root.resolve(".stage-crashed"))
        c.sweep()
        assertFalse("orphan folder must go", Files.exists(orphan))
        assertFalse("stale stage file must go", Files.exists(stage))
        assertTrue("a live record's parts must survive", regularFiles(root) > 0)
        s.close()
    }


    /**
     * Review B1: a permanently rejected file copy used to break the flush, so every text copy queued
     * behind it was never posted and the file never confirmed. It gets one retry, then is dropped.
     */
    @Test fun `a rejected file copy does not block the text queued behind it`() = runBlocking<Unit> {
        val m = media(); val s = store("blocked"); val c = DesktopFiles(tmp.newFolder().toPath(), s, m, own, true)
        val file = c.prepare(peer, source(2))
        val refs = FileManifest.fromAuthenticatedPayload(RelayPayload(file.id, file.ts, file.body)) { true }!!.body.parts.map { it.sha256Hex }
        s.queueFileSync(file.id, listOf("file-wire"), refs)

        val http = object : SyncHttp {
            var posts = 0
            override suspend fun post(envelopeJson: String): SyncHttpResult<Boolean> { posts++; return SyncHttpResult.Rejected("rejected") }
            override suspend fun list(after: Long, limit: Int) = error("unused")
            override suspend fun ack(through: Long) = error("unused")
        }
        val publisher = SentSyncPublisher("d".repeat(32), s, http)
        repeat(3) { if (s.outbox(10).isNotEmpty()) publisher.flush() }

        assertEquals("the lane must drain past a permanently rejected file copy", 0, s.outbox(10).size)
        assertFalse("a dropped copy must never be marked published", s.fileRecord(file.id)!!.syncPublished)
        assertTrue("the file copy is retried at least once before being dropped", http.posts >= 2)
        s.close()
    }

    private fun regularFiles(root: Path): Int = Files.walk(root).use { p ->
        p.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.count().toInt()
    }
}
