package com.relay.app.desktop

import com.relay.app.files.*
import com.relay.app.messaging.InnerEnvelope
import com.relay.app.transport.*
import com.relay.app.transport.mailbox.*
import com.relay.app.linking.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import rust.nostr.sdk.Keys
import java.nio.file.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import java.security.SecureRandom
import java.security.cert.X509Certificate

/** Real local Worker, enrolled desktop key, non-allowlisted administration key and independent JVM phone codec peer. */
class DesktopFilesWorkerTest {
    @Test fun `empty small sixty MiB both directions through local Worker with JVM phone peer`() = runBlocking<Unit> {
        assumeTrue("Set RELAY_FILES_E2E=1", System.getenv("RELAY_FILES_E2E") == "1")
        val root = Files.createTempDirectory("wp09-desktop-worker-")
        val workerDir = Path.of("../server/cloudflare").toAbsolutePath().normalize()
        val node = "/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node"
        val identity = Keys.generate(); val admin = Keys.generate(); val deviceKey = Keys.generate(); val peerKey = Keys.generate(); val lateKey = Keys.generate()
        val lateDevice = "c".repeat(32)
        val device = "d".repeat(32); val base = "https://127.0.0.1:8796"
        val worker = ProcessBuilder(node, "node_modules/wrangler/bin/wrangler.js", "dev", "--local", "--local-protocol", "https",
            "--port", "8796", "--var", "ALLOWED_PUBKEYS:${identity.publicKey().toHex()},${peerKey.publicKey().toHex()}",
            "--persist-to", root.resolve("state").toString()).directory(workerDir.toFile()).redirectErrorStream(true)
            .redirectOutput(root.resolve("worker.log").toFile()).apply { environment()["WRANGLER_SEND_METRICS"] = "false" }.start()
        // Test-only self-signed localhost fixture. Production TLS validation is not changed.
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
            call("/v1/devices?device=primary", JSONObject().put("v", 1).put("device", device).put("label", "WP09 fixture").put("auth_pubkey", deviceKey.publicKey().toHex()), DesktopNostrSigner(admin))
            fun paths(label: String) = DesktopPaths(root.resolve("$label-config"), root.resolve("$label-data"))
            val dp = paths("desktop"); val pp = paths("phone")
            val ds = FileSecretStore(dp.secretFile); val ps = FileSecretStore(pp.secretFile)
            val di = DesktopIdentity(dp, ds, identity); val dk = DesktopKeyMaterial(dp, ds); val pk = DesktopKeyMaterial(pp, ps)
            val store = DesktopMessageStore(dp.databaseFile, ds).also { stores += it }; val peerStore = DesktopMessageStore(pp.databaseFile, ps).also { stores += it }
            val own = identity.publicKey().toHex(); val peer = peerKey.publicKey().toHex()
            // Recipient HPKE key comes through the production imported-contact store, not a CLI map.
            store.importLinkBundle(LinkBundle(createdAt = System.currentTimeMillis(), identity = LinkIdentity(identity.secretKey().toHex(), own, "primary", "fixture", "fixture"),
                contacts = listOf(LinkContact(name = "JVM phone", phone = "", hasRelay = true, trustLevel = "trusted", nostrPubkey = peer, publicKey = pk.publicKeyBase64())), groups = emptyList(), settings = LinkSettings()), 1)
            val media = MailboxMedia.Client(MailboxMedia.RequestContext(DesktopNostrSigner(deviceKey), device), base, http)
            val peerMedia = MailboxMedia.Client(MailboxMedia.RequestContext(DesktopNostrSigner(peerKey)), base, http)
            var lateEnrolled = false
            val productionMedia = DesktopFileMediaClient(media)
            val downloadCount = java.util.concurrent.atomic.AtomicInteger(); val failDownloadAt = java.util.concurrent.atomic.AtomicInteger(-1)
            fun counting(inner: DesktopFileMedia) = object : DesktopFileMedia by inner {
                override fun download(file: java.io.File, hash: String, size: Long, cancellation: MailboxMedia.Cancellation) {
                    val n = downloadCount.incrementAndGet(); if (n == failDownloadAt.get()) throw java.io.IOException("injected interruption after part ${n - 1}")
                    inner.download(file, hash, size, cancellation)
                }
            }
            val enrollAfterPut = object : DesktopFileMedia by productionMedia {
                override fun download(file: java.io.File, hash: String, size: Long, cancellation: MailboxMedia.Cancellation) {
                    val n = downloadCount.incrementAndGet(); if (n == failDownloadAt.get()) throw java.io.IOException("injected interruption after part ${n - 1}")
                    productionMedia.download(file, hash, size, cancellation)
                }
                override fun upload(file: java.io.File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation) {
                    productionMedia.upload(file, hash, recipients, cancellation)
                    if (!lateEnrolled) {
                        call("/v1/devices?device=primary", JSONObject().put("v", 1).put("device", lateDevice).put("label", "late own device").put("auth_pubkey", lateKey.publicKey().toHex()), DesktopNostrSigner(admin))
                        lateEnrolled = true
                    }
                }
            }
            val controller = DesktopFiles(dp.databaseFile.parent.resolve("files"), store, enrollAfterPut, own, true)
            val latePaths = paths("late-own"); val lateSecrets = FileSecretStore(latePaths.secretFile)
            val lateStore = DesktopMessageStore(latePaths.databaseFile, lateSecrets).also { stores += it }
            val lateMedia = MailboxMedia.Client(MailboxMedia.RequestContext(DesktopNostrSigner(lateKey), lateDevice), base, http)
            val lateFiles = DesktopFiles(latePaths.databaseFile.parent.resolve("files"), lateStore, DesktopFileMediaClient(lateMedia), own)
            val lateApplier = SentSyncApplier(lateDevice, own, DesktopSyncHttp(base, DesktopNostrSigner(lateKey), lateDevice, http), lateStore,
                openBody = { InnerEnvelope.open(dk, it) })
            val syncHttp = DesktopSyncHttp(base, DesktopNostrSigner(deviceKey), device, http)
            val publisher = SentSyncPublisher(device, store, syncHttp, sealToSelf = { InnerEnvelope.seal(dk, dk.publicKeyBase64(), it) })
            engine = DesktopEngine(di, dk, store, DesktopMailboxTransport(base, identity, DesktopNostrSigner(identity), store, DesktopDeviceAuth(device, DesktopNostrSigner(deviceKey)), http),
                syncPublisher = publisher, files = controller)
            val peerT = DesktopMailboxTransport(base, peerKey, DesktopNostrSigner(peerKey), peerStore, httpClient = http); peerTransport = peerT
            val received = Channel<IncomingEnvelope>(Channel.UNLIMITED)
            collector = launch(Dispatchers.IO) { peerT.incoming.collect { received.send(it) } }
            peerT.start(); engine.start()
            for (size in listOf(0L, 19L, 60L * 1024 * 1024)) {
                val startedNs = System.nanoTime()
                val original = root.resolve("source-$size.bin")
                Files.newOutputStream(original).use { out -> val b = ByteArray(65536) { (it % 251).toByte() }; var left = size; while (left > 0) { val n = minOf(left, b.size.toLong()).toInt(); out.write(b, 0, n); left -= n } }
                val digest = DesktopFiles.digest(original)
                val sentId = engine.sendFile(peer, original)
                assertTrue("desktop send failed: ${store.fileRecord(sentId)?.state}", store.fileRecord(sentId)!!.sent)
                assertTrue(lateEnrolled)
                val applied = lateApplier.runOnce()
                assertTrue("late own SENT missing: $applied", lateStore.fileRecord(sentId) != null)
                assertFalse(lateStore.fileRecord(sentId)!!.localSource)
                lateFiles.run(sentId, { _, _ -> error("SENT receiving device must not send") })
                assertEquals("late own device could not fetch uploaded-before-enrollment blob", "COMPLETE", lateStore.fileRecord(sentId)!!.state)
                val ownSaved = root.resolve("late-own-saved-$size.bin"); lateFiles.saveAs(sentId, ownSaved)
                assertEquals(digest, DesktopFiles.digest(ownSaved))
                val incoming = withTimeout(60000) { received.receive() }
                val plain = InnerEnvelope.open(pk, incoming.payload.body) ?: error("peer cannot decrypt")
                val manifest = FileManifest.fromAuthenticatedPayload(incoming.payload.copy(body = plain)) { it == "$base/v1/media/" } ?: error("bad manifest")
                assertEquals(sentId, manifest.fileId)
                val saved = root.resolve("phone-saved-$size.bin")
                Files.newOutputStream(saved).use { out -> assertTrue(FileAssembler().assemble(manifest, sentId, { i, p -> val part = root.resolve("phone-download-$size-$i"); peerMedia.downloadTo(peerMedia.urlFor(p.sha256Hex), p.sha256Hex, part.toFile(), p.storedBytes.toLong()); Files.newInputStream(part) }, out)) }
                assertEquals(digest, DesktopFiles.digest(saved)); incoming.onHandled?.invoke()
                for (p in manifest.body.parts) assertEquals(MailboxMedia.Confirmation.Confirmed, peerMedia.confirm(peerMedia.urlFor(p.sha256Hex)))
                // Independently produced JVM phone file, encrypted once and sent via actual signed media refs.
                val id = PayloadCodec.newId(); val ts = System.currentTimeMillis()
                val reverse = Files.newInputStream(original).use { input -> FileSender().prepare(input, size, id, "from-phone.bin", "application/octet-stream", "$base/v1/media/") { i, p, bytes ->
                    val part = root.resolve("phone-upload-$size-$i"); Files.newOutputStream(part).use { bytes.copyTo(it, 65536) }
                    peerMedia.upload(part.toFile(), p.sha256Hex, listOf(own, peer))
                } }
                val wire = FileBody.encode(reverse)
                val result = peerT.sendWithMedia(own, RelayPayload(id, ts, InnerEnvelope.seal(pk, dk.publicKeyBase64(), wire)), reverse.parts.map { it.sha256Hex })
                assertTrue("peer send failed: $result", result is SendResult.Sent)
                withTimeout(90000) { while (store.fileRecord(id)?.state !in listOf("COMPLETE", "CONFIRM_PENDING")) { delay(100) } }
                val desktopSaved = root.resolve("desktop-saved-$size.bin"); controller.saveAs(id, desktopSaved)
                assertEquals(size, Files.size(desktopSaved)); assertEquals(digest, DesktopFiles.digest(desktopSaved))
                println("WP09_LOCAL_WORKER direction=both bytes=$size plaintext_sha256=$digest parts=${reverse.parts.size} verified=true elapsed_ms=${(System.nanoTime() - startedNs) / 1_000_000}")
            }
            // Interruption: stop after part 1 of 2, recreate store, controller, transport and engine, redownload only the missing part.
            run {
                val size = 27L * 1024 * 1024; val original = root.resolve("source-resume.bin")
                Files.newOutputStream(original).use { out -> val b = ByteArray(65536) { (it % 241).toByte() }; var left = size; while (left > 0) { val n = minOf(left, b.size.toLong()).toInt(); out.write(b, 0, n); left -= n } }
                val digest = DesktopFiles.digest(original); val id = PayloadCodec.newId(); val ts = System.currentTimeMillis()
                val reverse = Files.newInputStream(original).use { input -> FileSender().prepare(input, size, id, "resume.bin", "application/octet-stream", "$base/v1/media/") { i, p, bytes ->
                    val part = root.resolve("phone-upload-resume-$i"); Files.newOutputStream(part).use { bytes.copyTo(it, 65536) }
                    peerMedia.upload(part.toFile(), p.sha256Hex, listOf(own, peer))
                } }
                assertEquals(2, reverse.parts.size)
                downloadCount.set(0); failDownloadAt.set(2)
                val sent = peerT.sendWithMedia(own, RelayPayload(id, ts, InnerEnvelope.seal(pk, dk.publicKeyBase64(), FileBody.encode(reverse))), reverse.parts.map { it.sha256Hex })
                assertTrue("peer send failed: $sent", sent is SendResult.Sent)
                withTimeout(90000) { while (store.fileRecord(id)?.state != "FAILED") { delay(50) } }
                assertEquals(listOf(true, false), store.fileRecord(id)!!.parts); assertEquals(2, downloadCount.get())
                engine.stop(); store.close()
                failDownloadAt.set(-1); downloadCount.set(0)
                val store2 = DesktopMessageStore(dp.databaseFile, ds).also { stores += it }
                assertEquals(listOf(true, false), store2.fileRecord(id)!!.parts)
                val controller2 = DesktopFiles(dp.databaseFile.parent.resolve("files"), store2, counting(productionMedia), own, true)
                val transport2 = DesktopMailboxTransport(base, identity, DesktopNostrSigner(identity), store2, DesktopDeviceAuth(device, DesktopNostrSigner(deviceKey)), http)
                engine = DesktopEngine(di, dk, store2, transport2, syncPublisher = SentSyncPublisher(device, store2, syncHttp, sealToSelf = { InnerEnvelope.seal(dk, dk.publicKeyBase64(), it) }), files = controller2)
                engine.start()
                withTimeout(90000) { while (store2.fileRecord(id)?.state !in listOf("COMPLETE", "CONFIRM_PENDING")) { delay(50) } }
                assertEquals("only the missing part may be downloaded again", 1, downloadCount.get())
                val resumed = root.resolve("desktop-saved-resume.bin"); controller2.saveAs(id, resumed); assertEquals(digest, DesktopFiles.digest(resumed))
                println("WP09_LOCAL_WORKER restart_resume bytes=$size parts=2 redownloaded_after_restart=${downloadCount.get()} verified=true")
            }
            val status = Files.readAllLines(Path.of("/proc/self/status")).filter { it.startsWith("VmHWM") || it.startsWith("VmRSS") }.joinToString(" ").replace(Regex("\\s+"), " ")
            println("WP09_LOCAL_WORKER test_jvm_$status (one JVM hosting desktop, JVM phone peer and late own device)")
            // Disposable packaged-UI fixture only: persist generated identity/link marker, never user state.
            di.adopt(identity.secretKey().toHex())
            DesktopLinkKeys(dp, ds).apply { loadOrCreate("WP09 synthetic desktop"); markLinked(own, System.currentTimeMillis()) }
            Files.writeString(root.resolve("ui-public.json"), JSONObject().put("config", dp.configDir.toString()).put("data", dp.dataDir.toString()).put("peer", peer).toString())
            println("WP09_LOCAL_WORKER scratch=$root admin_allowlisted=false device_auth=true late_own_device_after_put=true")
        } finally {
            collector?.cancelAndJoin(); engine?.stop(); peerTransport?.stop(); stores.forEach { it.close() }
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            val descendants = worker.descendants().toList().reversed(); descendants.forEach { it.destroy() }; worker.destroy()
            if (!worker.waitFor(15, TimeUnit.SECONDS)) worker.destroyForcibly()
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }
}
