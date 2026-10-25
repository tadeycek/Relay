package com.relay.app.desktop

import com.relay.app.linking.*
import com.relay.app.media.MediaBody
import com.relay.app.media.MediaCrypto
import com.relay.app.media.MediaRef
import com.relay.app.messaging.InnerEnvelope
import com.relay.app.transport.*
import com.relay.app.transport.mailbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import rust.nostr.sdk.Keys
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * WP08 against the real local Worker (`wrangler dev --local`): enrolled desktop device key, a non-allowlisted
 * admin key, an independent JVM "phone" built from the shared codec, and a late own device enrolled after
 * the upload. Gated: set RELAY_MEDIA_E2E=1 (node 24 via RELAY_NODE_BIN or the npx cache path below).
 */
class DesktopMediaWorkerTest {
    private fun jpeg(w: Int, h: Int, seed: Long): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB); val rnd = Random(seed)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, ((x * 3 + y) and 0xFF) or (rnd.nextInt(64) shl 8) or (rnd.nextInt(32) shl 16))
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }
    private fun mp4Stub(extra: Int) = java.nio.ByteBuffer.allocate(32 + extra).putInt(24).put("ftyp".toByteArray()).put("isom".toByteArray()).putInt(0).put("isomiso2".toByteArray()).array()

    @Test fun `photo video and confusion round trip through the local Worker with grants confirmations and restart`() = runBlocking<Unit> {
        assumeTrue("Set RELAY_MEDIA_E2E=1", System.getenv("RELAY_MEDIA_E2E") == "1")
        val root = Files.createTempDirectory("wp08-desktop-worker-")
        val workerDir = Path.of("../server/cloudflare").toAbsolutePath().normalize()
        val node = System.getenv("RELAY_NODE_BIN") ?: "/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node"
        val identity = Keys.generate(); val admin = Keys.generate(); val deviceKey = Keys.generate(); val peerKey = Keys.generate(); val lateKey = Keys.generate()
        val device = "d".repeat(32); val lateDevice = "c".repeat(32); val base = "https://127.0.0.1:8797"
        val worker = ProcessBuilder(node, "node_modules/wrangler/bin/wrangler.js", "dev", "--local", "--local-protocol", "https",
            "--port", "8797", "--var", "ALLOWED_PUBKEYS:${identity.publicKey().toHex()},${peerKey.publicKey().toHex()}",
            "--persist-to", root.resolve("state").toString()).directory(workerDir.toFile()).redirectErrorStream(true)
            .redirectOutput(root.resolve("worker.log").toFile()).apply { environment()["WRANGLER_SEND_METRICS"] = "false" }.start()
        val trust = object : X509TrustManager { override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}; override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}; override fun getAcceptedIssuers() = emptyArray<X509Certificate>() }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val http = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).hostnameVerifier { host, _ -> host == "127.0.0.1" }.followRedirects(false).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
        val stores = mutableListOf<DesktopMessageStore>(); var engine: DesktopEngine? = null; var peerTransport: DesktopMailboxTransport? = null; var collector: Job? = null
        try {
            withTimeout(90000) { while (true) { if (!worker.isAlive) error("Worker exited; see $root/worker.log"); val ok = runCatching { http.newCall(Request.Builder().url("$base/v1/health").build()).execute().use { it.isSuccessful } }.getOrDefault(false); if (ok) break; delay(250) } }
            fun call(path: String, body: JSONObject, signer: NostrSigner) {
                val url = base + path; val bytes = body.toString().toByteArray()
                http.newCall(Request.Builder().url(url).header("Authorization", MailboxAuth.header(signer, "POST", url, bytes)).post(bytes.toRequestBody("application/json".toMediaType())).build()).execute().use { response -> assertEquals("registration $path: ${response.body?.string()}", 200, response.code) }
            }
            call("/v1/devices/admin", JSONObject().put("v", 1).put("admin_pubkey", admin.publicKey().toHex()), DesktopNostrSigner(identity))
            call("/v1/devices?device=primary", JSONObject().put("v", 1).put("device", device).put("label", "WP08 fixture").put("auth_pubkey", deviceKey.publicKey().toHex()), DesktopNostrSigner(admin))
            fun paths(label: String) = DesktopPaths(root.resolve("$label-config"), root.resolve("$label-data"))
            val dp = paths("desktop"); val pp = paths("phone")
            val ds = FileSecretStore(dp.secretFile); val ps = FileSecretStore(pp.secretFile)
            val di = DesktopIdentity(dp, ds, identity); val dk = DesktopKeyMaterial(dp, ds); val pk = DesktopKeyMaterial(pp, ps)
            val store = DesktopMessageStore(dp.databaseFile, ds).also { stores += it }; val peerStore = DesktopMessageStore(pp.databaseFile, ps).also { stores += it }
            val own = identity.publicKey().toHex(); val peer = peerKey.publicKey().toHex()
            store.importLinkBundle(LinkBundle(createdAt = System.currentTimeMillis(), identity = LinkIdentity(identity.secretKey().toHex(), own, "primary", "fixture", "fixture"),
                contacts = listOf(LinkContact(name = "JVM phone", phone = "", hasRelay = true, trustLevel = "trusted", nostrPubkey = peer, publicKey = pk.publicKeyBase64())), groups = emptyList(), settings = LinkSettings()), 1)
            val desktopClient = MailboxMedia.Client(MailboxMedia.RequestContext(DesktopNostrSigner(deviceKey), device), base, http)
            val peerMedia = MailboxMedia.Client(MailboxMedia.RequestContext(DesktopNostrSigner(peerKey)), base, http)
            var lateEnrolled = false
            val production = DesktopMediaClient(desktopClient)
            val failNextDownload = java.util.concurrent.atomic.AtomicBoolean(false); val downloads = java.util.concurrent.atomic.AtomicInteger()
            val transport: DesktopMediaTransport = object : DesktopMediaTransport by production {
                override fun download(file: java.io.File, hash: String, maxBytes: Long, cancellation: MailboxMedia.Cancellation) {
                    downloads.incrementAndGet(); if (failNextDownload.compareAndSet(true, false)) throw java.io.IOException("injected network failure")
                    production.download(file, hash, maxBytes, cancellation)
                }
                override fun upload(file: java.io.File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation, onBytes: (Long) -> Unit) {
                    production.upload(file, hash, recipients, cancellation, onBytes)
                    if (!lateEnrolled) { // a device enrolled AFTER the PUT must still be granted through the SENT copy digest
                        call("/v1/devices?device=primary", JSONObject().put("v", 1).put("device", lateDevice).put("label", "late own device").put("auth_pubkey", lateKey.publicKey().toHex()), DesktopNostrSigner(admin))
                        lateEnrolled = true
                    }
                }
            }
            val mediaRoot = dp.databaseFile.parent.resolve("media")
            val controller = DesktopMedia(mediaRoot, store, transport, own, sendEnabled = true)
            val latePaths = paths("late-own"); val lateSecrets = FileSecretStore(latePaths.secretFile)
            val lateStore = DesktopMessageStore(latePaths.databaseFile, lateSecrets).also { stores += it }
            val lateClient = MailboxMedia.Client(MailboxMedia.RequestContext(DesktopNostrSigner(lateKey), lateDevice), base, http)
            val lateMedia = DesktopMedia(latePaths.databaseFile.parent.resolve("media"), lateStore, DesktopMediaClient(lateClient), own)
            val lateApplier = SentSyncApplier(lateDevice, own, DesktopSyncHttp(base, DesktopNostrSigner(lateKey), lateDevice, http), lateStore, openBody = { InnerEnvelope.open(dk, it) })
            val syncHttp = DesktopSyncHttp(base, DesktopNostrSigner(deviceKey), device, http)
            fun publisher(s: DesktopMessageStore) = SentSyncPublisher(device, s, syncHttp, sealToSelf = { InnerEnvelope.seal(dk, dk.publicKeyBase64(), it) })
            fun linkedTransport(s: DesktopMessageStore) = DesktopMailboxTransport(base, identity, DesktopNostrSigner(identity), s, DesktopDeviceAuth(device, DesktopNostrSigner(deviceKey)), http)
            engine = DesktopEngine(di, dk, store, linkedTransport(store), syncPublisher = publisher(store), media = controller)
            val peerT = DesktopMailboxTransport(base, peerKey, DesktopNostrSigner(peerKey), peerStore, httpClient = http); peerTransport = peerT
            val received = Channel<IncomingEnvelope>(Channel.UNLIMITED)
            collector = launch(Dispatchers.IO) { peerT.incoming.collect { received.send(it) } }
            peerT.start(); engine.start()

            // ---- desktop -> phone, with own-device grant and the AC2 erase-after-all-confirm order -------------
            val photo = jpeg(800, 600, 1); val src = root.resolve("photo.jpg"); Files.write(src, photo)
            val t0 = System.nanoTime()
            val sentId = engine.sendMedia(peer, src, "from desktop")
            val sent = store.mediaRecord(sentId)!!
            assertTrue("desktop send: ${sent.state} ${sent.error}", sent.sent && sent.uploaded)
            assertEquals("unchanged: already within the phone's size rules", photo.size.toLong(), MediaBody.parse(sent.body)!!.size)
            val incoming = withTimeout(60000) { received.receive() }
            val body = InnerEnvelope.open(pk, incoming.payload.body) ?: error("phone cannot decrypt")
            val ref = MediaBody.parse(body) ?: error("phone cannot parse the desktop TYPE:MEDIA body")
            assertEquals(sentId, incoming.payload.id)
            val phoneBlob = root.resolve("phone-blob"); peerMedia.downloadTo(peerMedia.urlFor(ref.sha256Hex), ref.sha256Hex, phoneBlob.toFile(), MailboxMedia.MAX_STORED_BYTES)
            assertArrayEquals(photo, MediaCrypto.decrypt(Files.readAllBytes(phoneBlob), ref.keyBase64))
            incoming.onHandled?.invoke()
            // Desktop has confirmed (COMPLETE) but the phone and the late device have not: the object must still be there.
            withTimeout(30000) { while (store.mediaRecord(sentId)?.state != "COMPLETE") delay(50) }
            val stillThere = root.resolve("phone-blob-2"); peerMedia.downloadTo(peerMedia.urlFor(ref.sha256Hex), ref.sha256Hex, stillThere.toFile(), MailboxMedia.MAX_STORED_BYTES)
            val applied = lateApplier.runOnce(); assertTrue("late own SENT missing: $applied", lateStore.mediaRecord(sentId) != null)
            assertFalse(lateStore.mediaRecord(sentId)!!.localSource)
            lateMedia.run(sentId, { _, _ -> error("SENT receiving device must not send") })
            assertEquals("late own device could not fetch an object uploaded before it was enrolled", "COMPLETE", lateStore.mediaRecord(sentId)!!.state)
            assertArrayEquals(photo, lateMedia.plaintext(sentId))
            assertEquals(MailboxMedia.Confirmation.Confirmed, peerMedia.confirm(peerMedia.urlFor(ref.sha256Hex)))
            // Contact, desktop and late device have confirmed; the sender's phone (primary device) has not: still stored.
            val stillBeforePhone = root.resolve("phone-blob-2b"); peerMedia.downloadTo(peerMedia.urlFor(ref.sha256Hex), ref.sha256Hex, stillBeforePhone.toFile(), MailboxMedia.MAX_STORED_BYTES)
            val primaryClient = MailboxMedia.Client(MailboxMedia.RequestContext(DesktopNostrSigner(admin), "primary"), base, http)
            assertEquals(MailboxMedia.Confirmation.Confirmed, primaryClient.confirm(primaryClient.urlFor(ref.sha256Hex)))
            val gone = runCatching { peerMedia.downloadTo(peerMedia.urlFor(ref.sha256Hex), ref.sha256Hex, root.resolve("phone-blob-3").toFile(), MailboxMedia.MAX_STORED_BYTES) }.exceptionOrNull()
            assertTrue("server must erase after every recipient confirmed: $gone", gone is MailboxMedia.HttpFailure && gone.status == 404)
            println("WP08_LOCAL_WORKER desktop_to_phone bytes=${photo.size} sha256=${MediaCrypto.sha256Hex(photo)} own_grant_late_device=true erased_after_all_confirm=true elapsed_ms=${(System.nanoTime() - t0) / 1_000_000}")

            // ---- phone -> desktop: photo, video, and a PNG that is really a JPEG ---------------------------------
            data class Case(val name: String, val plain: ByteArray, val mime: String, val verdict: String)
            val cases = listOf(Case("photo", jpeg(640, 480, 2), "image/jpeg", "IMAGE"), Case("video", mp4Stub(300_000), "video/mp4", "VIDEO"), Case("confused", jpeg(64, 64, 3), "image/png", "ATTACHMENT"))
            for (c in cases) {
                val id = PayloadCodec.newId(); val enc = MediaCrypto.encrypt(c.plain)
                val blob = root.resolve("phone-up-${c.name}"); Files.write(blob, enc.blob)
                peerMedia.upload(blob.toFile(), enc.sha256Hex, listOf(own, peer))
                val wire = MediaBody.format(MediaRef("$base/v1/media/${enc.sha256Hex}", enc.sha256Hex, enc.keyBase64, c.mime, c.plain.size.toLong(), "phone ${c.name}"))
                val result = peerT.sendWithMedia(own, RelayPayload(id, System.currentTimeMillis(), InnerEnvelope.seal(pk, dk.publicKeyBase64(), wire)), listOf(enc.sha256Hex))
                assertTrue("peer send failed: $result", result is SendResult.Sent)
                withTimeout(60000) { while (store.mediaRecord(id)?.state != "COMPLETE") delay(50) }
                val r = store.mediaRecord(id)!!; assertEquals(c.verdict, r.verdict); assertTrue(r.confirmed)
                assertArrayEquals(c.plain, controller.plaintext(id))
                if (c.verdict == "VIDEO" || c.verdict == "ATTACHMENT") assertNull(controller.previewBytes(id))
                println("WP08_LOCAL_WORKER phone_to_desktop case=${c.name} bytes=${c.plain.size} verdict=${r.verdict} sha256=${MediaCrypto.sha256Hex(c.plain)}")
            }

            // ---- restart: a failed download is resumed by a brand new store, controller, transport and engine -----------
            run {
                val plain = jpeg(500, 500, 4); val id = PayloadCodec.newId(); val enc = MediaCrypto.encrypt(plain)
                val blob = root.resolve("phone-up-restart"); Files.write(blob, enc.blob)
                peerMedia.upload(blob.toFile(), enc.sha256Hex, listOf(own, peer))
                val wire = MediaBody.format(MediaRef("$base/v1/media/${enc.sha256Hex}", enc.sha256Hex, enc.keyBase64, "image/jpeg", plain.size.toLong(), ""))
                failNextDownload.set(true)
                peerT.sendWithMedia(own, RelayPayload(id, System.currentTimeMillis(), InnerEnvelope.seal(pk, dk.publicKeyBase64(), wire)), listOf(enc.sha256Hex))
                withTimeout(60000) { while (store.mediaRecord(id)?.state != "FAILED") delay(50) }
                assertFalse("a network failure is retryable", store.mediaRecord(id)!!.permanent); assertEquals(0, store.mediaRecord(id)!!.let { if (it.confirmed) 1 else 0 })
                engine.stop(); store.close()
                val store2 = DesktopMessageStore(dp.databaseFile, ds).also { stores += it }
                downloads.set(0)
                val controller2 = DesktopMedia(mediaRoot, store2, transport, own, sendEnabled = true)
                engine = DesktopEngine(di, dk, store2, linkedTransport(store2), syncPublisher = publisher(store2), media = controller2)
                engine.start()
                withTimeout(60000) { while (store2.mediaRecord(id)?.state != "COMPLETE") delay(50) }
                assertEquals("one clean download after restart", 1, downloads.get()); assertArrayEquals(plain, controller2.plaintext(id))
                println("WP08_LOCAL_WORKER restart_resume bytes=${plain.size} downloads_after_restart=${downloads.get()} verified=true")
            }
            // Disposable packaged-UI fixture only: generated identity, never user state.
            di.adopt(identity.secretKey().toHex())
            DesktopLinkKeys(dp, ds).apply { loadOrCreate("WP08 synthetic desktop"); markLinked(own, System.currentTimeMillis()) }
            Files.writeString(root.resolve("ui-public.json"), JSONObject().put("config", dp.configDir.toString()).put("data", dp.dataDir.toString()).put("peer", peer).toString())
            println("WP08_LOCAL_WORKER scratch=$root admin_allowlisted=false device_auth=true late_own_device_after_put=true")
        } finally {
            collector?.cancelAndJoin(); engine?.stop(); peerTransport?.stop(); stores.forEach { runCatching { it.close() } }
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            val descendants = worker.descendants().toList().reversed(); descendants.forEach { it.destroy() }; worker.destroy()
            if (!worker.waitFor(15, TimeUnit.SECONDS)) worker.destroyForcibly()
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }
}
