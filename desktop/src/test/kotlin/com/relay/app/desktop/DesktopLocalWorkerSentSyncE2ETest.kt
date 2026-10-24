package com.relay.app.desktop

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.relay.app.linking.LinkBundle
import com.relay.app.linking.LinkBundleCodec
import com.relay.app.linking.LinkCode
import com.relay.app.linking.LinkContact
import com.relay.app.linking.LinkIdentity
import com.relay.app.linking.LinkQr
import com.relay.app.linking.LinkSettings
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.mailbox.MailboxAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rust.nostr.sdk.Keys
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * WP05-AC2 end to end against a real local Worker (port 8795), both directions:
 *
 *  (a) the linked desktop sends to a JVM peer; a JVM phone-primary client fetches the SENT event through
 *      the real `/v1/sync` and applies it with the same codec (same payload id as the peer received);
 *  (b) the JVM phone publishes a SENT; the desktop's own sync loop applies it as outgoing;
 *  (c) a body too big for one wire body is chunked by the phone, travels as several server events and is
 *      reassembled by the desktop.
 *
 * Skipped unless `RELAY_E2E=1`. The Worker state directory is deleted first and the Worker is stopped in
 * `finally`. Run (note: no RELAY_WORKER_DIR, XDG_* untouched):
 *
 * ```
 * RELAY_E2E=1 RELAY_E2E_PERSIST=$PWD/.local/wp05-sent-desktop-state \
 *   ./gradlew :desktop:test --tests '*DesktopLocalWorkerSentSyncE2ETest'
 * ```
 */
class DesktopLocalWorkerSentSyncE2ETest {

    @Test
    fun `sent copies flow desktop to phone and phone to desktop through the real sync endpoint`() = runBlocking {
        assumeTrue("Set RELAY_E2E=1 to run the local Worker sent-sync test", System.getenv("RELAY_E2E") == "1")

        val repoRoot = Path.of("..").toAbsolutePath().normalize()
        val workerDir = System.getenv("RELAY_WORKER_DIR")?.let { Path.of(it) } ?: repoRoot.resolve("server/cloudflare")
        val node = System.getenv("RELAY_NODE") ?: "/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node"
        assumeTrue("Node 24+ not found at $node", Files.isRegularFile(Path.of(node)))
        assumeTrue("wrangler not installed", Files.isRegularFile(workerDir.resolve("node_modules/wrangler/bin/wrangler.js")))

        val port = 8795
        val persist = (System.getenv("RELAY_E2E_PERSIST")?.let { Path.of(it) } ?: repoRoot.resolve(".local/wp05-sent-desktop-state"))
            .toAbsolutePath()
        persist.toFile().deleteRecursively()
        Files.createDirectories(persist)

        val phoneKeys = Keys.generate()
        val adminKeys = Keys.generate()
        val peerKeys = Keys.generate()
        // The admin key must NOT be a member: only the identity key and the peer are allowlisted.
        val allowlist = listOf(phoneKeys, peerKeys).joinToString(",") { it.publicKey().toHex() }

        val log = Files.createTempFile("relay-wp05-sent-worker", ".log")
        val worker = ProcessBuilder(
            node, "node_modules/wrangler/bin/wrangler.js", "dev", "--local",
            "--port", port.toString(),
            "--var", "ALLOWED_PUBKEYS:$allowlist",
            "--persist-to", persist.toString(),
        )
            .directory(workerDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .apply { environment()["WRANGLER_SEND_METRICS"] = "false" }
            .start()

        val base = "http://127.0.0.1:$port"
        try {
            waitForHealth(port, worker, log, 90)

            // ---- link the desktop exactly like the WP07 test -----------------------------------
            val temp = Files.createTempDirectory("relay-wp05-sent")
            val desktopPaths = DesktopPaths(temp.resolve("desktop-config"), temp.resolve("desktop-data"))
            val phoneHpke = phoneKeyset()
            val peerPub = peerKeys.publicKey().toHex()
            val bundle = LinkBundle(
                createdAt = System.currentTimeMillis(),
                identity = LinkIdentity(
                    nostrSecretHex = phoneKeys.secretKey().toHex(),
                    nostrPubkeyHex = phoneKeys.publicKey().toHex(),
                    deviceId = "primary",
                    deviceLabel = "Pixel (test)",
                    hpkeKeysetBase64 = phoneHpke,
                ),
                contacts = listOf(LinkContact(name = "Ana", phone = "+441234", hasRelay = true, trustLevel = "trusted", nostrPubkey = peerPub)),
                groups = emptyList(),
                settings = LinkSettings(),
            )
            val engine = DesktopEngine.create(desktopPaths, base)
            val flow = engine.createLinkFlow(base)
            flow.begin()
            val qr = LinkQr.decode(flow.qrText)
            assertNotNull(qr)
            val slot = LinkCode.slotId(qr!!.code)
            val sealed = LinkBundleCodec.seal(DesktopLinkHpke { phoneHpke }, qr.ephemeralPublicKey!!, bundle)
            assertEquals(200, call(base, "PUT", "/v1/link/$slot", sealed, phoneKeys).first)
            assertTrue(flow.step() is LinkState.Linked)

            val reopened = DesktopLinkKeys(desktopPaths, FileSecretStore(desktopPaths.secretFile))
            val authKeys = Keys.parse(reopened.load()!!.session.deviceAuthSecretHex)
            val deviceId = qr.deviceId!!
            assertEquals(200, call(base, "POST", "/v1/devices/admin",
                """{"v":1,"admin_pubkey":"${adminKeys.publicKey().toHex()}"}""".toByteArray(), phoneKeys).first)
            assertEquals(200, call(base, "POST", "/v1/devices?device=primary",
                JSONObject().put("v", 1).put("device", deviceId).put("label", "sent-sync desktop")
                    .put("auth_pubkey", authKeys.publicKey().toHex()).toString().toByteArray(), adminKeys).first)

            // ---- peer, desktop (with sync), phone-primary ---------------------------------------
            val peerPaths = DesktopPaths(temp.resolve("peer-config"), temp.resolve("peer-data"))
            val peerSecrets = FileSecretStore(peerPaths.secretFile)
            val peerIdentity = DesktopIdentity(peerPaths, peerSecrets, peerKeys)
            val peerKeyMaterial = DesktopKeyMaterial(peerPaths, peerSecrets)
            val peerStore = DesktopMessageStore(peerPaths.databaseFile, peerSecrets)
            val peer = DesktopEngine(
                peerIdentity, peerKeyMaterial, peerStore,
                DesktopMailboxTransport(base, peerKeys, DesktopNostrSigner(peerKeys), peerStore),
                peerInnerKeys = mapOf(phoneKeys.publicKey().toHex() to engine.keyMaterial.publicKeyBase64()),
            )

            val sealToSelf: (String) -> String? = {
                com.relay.app.messaging.InnerEnvelope.seal(engine.keyMaterial, engine.keyMaterial.publicKeyBase64(), it)
            }
            val device = DesktopDeviceAuth(deviceId, DesktopNostrSigner(authKeys))
            val desktopSyncHttp = DesktopSyncHttp(base, device.signer, deviceId)
            val desktop = DesktopEngine(
                engine.identity, engine.keyMaterial, engine.store,
                DesktopMailboxTransport(base, engine.identity.keys(), engine.identity.signer(), engine.store, device),
                peerInnerKeys = mapOf(peerPub to peerKeyMaterial.publicKeyBase64()),
                linkKeys = engine.linkKeys,
                syncPublisher = SentSyncPublisher(deviceId, engine.store, desktopSyncHttp, sealToSelf = sealToSelf),
                syncApplier = SentSyncApplier(
                    deviceId, engine.identity.publicKeyHex(), desktopSyncHttp, engine.store,
                    openBody = { InnerEnvelope_open(engine, it) },
                    knownConversation = { engine.store.conversation(it) != null },
                ),
                syncIntervalMs = 1_000,
            )

            // The phone-primary: the administration key signs for device=primary.
            val phonePaths = DesktopPaths(temp.resolve("phone-config"), temp.resolve("phone-data"))
            val phoneStore = DesktopMessageStore(phonePaths.databaseFile, FileSecretStore(phonePaths.secretFile))
            val phoneHttp = DesktopSyncHttp(base, DesktopNostrSigner(adminKeys), "primary")
            // The phone and the desktop share the identity HPKE keyset (it travelled in the link bundle), so the
            // desktop's key material stands in for the phone's: bodies are sealed to the identity's own key.
            val phonePublisher = SentSyncPublisher("primary", phoneStore, phoneHttp, sealToSelf = sealToSelf)
            val phoneApplier = SentSyncApplier(
                "primary", phoneKeys.publicKey().toHex(), phoneHttp, phoneStore,
                openBody = { InnerEnvelope_open(engine, it) },
            )

            try {
                desktop.start()
                peer.start()

                // (a) desktop -> peer, and the copy reaches the phone ---------------------------------
                val text = "sent from the linked desktop"
                val t0 = System.nanoTime()
                assertTrue(desktop.sendText(peerPub, text) is SendResult.Sent)
                val desktopRow = desktop.messages(peerPub).single { it.body == text }
                waitFor(30) { peer.messages(phoneKeys.publicKey().toHex()).any { it.body == text } }
                val peerGotMs = (System.nanoTime() - t0) / 1_000_000
                val peerRow = peer.messages(phoneKeys.publicKey().toHex()).single { it.body == text }

                var phoneReport = phoneApplier.runOnce()
                val deadlineA = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (phoneStore.messages(peerPub).none { it.id == desktopRow.id } && System.nanoTime() < deadlineA) {
                    delay(200); phoneReport = phoneApplier.runOnce()
                }
                val phoneGotMs = (System.nanoTime() - t0) / 1_000_000
                val phoneRow = phoneStore.messages(peerPub).single { it.id == desktopRow.id }
                assertEquals(text, phoneRow.body)
                assertTrue(phoneRow.outgoing)
                assertEquals("payload id must match what the contact received", peerRow.id, phoneRow.id)
                assertEquals(1, phoneStore.messages(peerPub).size)
                assertTrue("phone ack must be sent after commit: $phoneReport", phoneReport.acked || phoneStore.syncCursor() > 0)
                val outboxAfterA = engine.store.outbox(10).size
                assertEquals(0, outboxAfterA)

                // (b) phone publishes a SENT; the desktop's own loop applies it --------------------------
                val phoneText = "sent from the phone"
                val phonePayload = RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), phoneText)
                val t1 = System.nanoTime()
                phonePublisher.recordSent(DesktopMessage(phonePayload.id, peerPub, phoneKeys.publicKey().toHex(), phoneText, phonePayload.ts, true), peerPub, phonePayload)
                val flush = phonePublisher.flush()
                assertEquals(1, flush.posted)
                waitFor(30) { desktop.messages(peerPub).any { it.id == phonePayload.id } }
                val desktopGotMs = (System.nanoTime() - t1) / 1_000_000
                val applied = desktop.messages(peerPub).single { it.id == phonePayload.id }
                assertTrue(applied.outgoing)
                assertEquals(phoneText, applied.body)
                assertEquals(1, desktop.messages(peerPub).count { it.id == phonePayload.id })

                // (c) a chunked body: several server events, reassembled on the desktop ------------------
                // Sealed bodies must stay under the 16 KiB RelayPayload bound, so ~11 KB of text is the largest copy.
                val big = (0 until 11_000).joinToString("") { ('a' + it % 26).toString() }
                val bigPayload = RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), big)
                val wires = phonePublisher.buildWires(peerPub, bigPayload)
                val t2 = System.nanoTime()
                phonePublisher.recordSent(DesktopMessage(bigPayload.id, peerPub, phoneKeys.publicKey().toHex(), big, bigPayload.ts, true), peerPub, bigPayload)
                val flushBig = phonePublisher.flush()
                assertEquals(wires.size, flushBig.posted)
                waitFor(30) { desktop.messages(peerPub).any { it.id == bigPayload.id } }
                val chunkedMs = (System.nanoTime() - t2) / 1_000_000
                assertEquals(big, desktop.messages(peerPub).single { it.id == bigPayload.id }.body)

                // ---- invariants after the dust settles -------------------------------------------------
                delay(2_500) // two more loop cycles: nothing may be re-applied or re-published
                assertEquals(1, desktop.messages(peerPub).count { it.id == phonePayload.id })
                assertEquals("desktop must not echo the phone's copies", 0, engine.store.outbox(10).size)
                val lastPhone = phoneApplier.runOnce()
                assertEquals(1, phoneStore.messages(peerPub).count { it.id == desktopRow.id })
                val desktopCursor = engine.store.syncCursor()
                assertEquals("phone released the cursor past its own chunked copy: $lastPhone", 0, lastPhone.pendingParts)
                assertEquals("both devices committed the same head", desktopCursor, phoneStore.syncCursor())
                val serverEvents = (phoneHttp.list(0, 8) as SyncHttpResult.Ok).value.events
                val eventsOnServer = serverEvents.size
                // The server only ever held sealed bodies: no event contains any of the plaintexts.
                val rawServer = serverEvents.joinToString("") { String(Base64.getDecoder().decode(it.dataBase64)) }
                for (plain in listOf(text, phoneText, "abcdefghij")) {
                    val inP = Regex("\\|P:([A-Za-z0-9+/=]{16,})").findAll(rawServer).joinToString("") { runCatching { String(Base64.getDecoder().decode(it.groupValues[1])) }.getOrDefault("") }
                    assertTrue("server event must not hold the plaintext", !inP.contains(plain) && !rawServer.contains(plain))
                }

                println(
                    "WP05-SENT-E2E OK port=$port persist=$persist device32=$deviceId " +
                        "peerDeliveredMs=$peerGotMs phoneAppliedDesktopSentMs=$phoneGotMs " +
                        "desktopAppliedPhoneSentMs=$desktopGotMs chunkedParts=${wires.size} " +
                        "chunkedWireBytes=${wires.map { it.toByteArray().size }} chunkedApplyMs=$chunkedMs " +
                        "serverEventsFirstPage=$eventsOnServer desktopCursor=$desktopCursor " +
                        "phoneCursor=${phoneStore.syncCursor()} desktopApply=${desktop.syncService()?.lastApply?.copy(cursor = desktopCursor)} " +
                        "lastPhoneApply=$lastPhone",
                )
            } finally {
                desktop.close()
                peer.close()
                peerStore.close()
                phoneStore.close()
                engine.close()
            }
        } finally {
            worker.destroy()
            if (!worker.waitFor(15, TimeUnit.SECONDS)) worker.destroyForcibly()
            // wrangler forks workerd; make sure nothing is left holding the port.
            runCatching { ProcessHandle.of(worker.pid()).ifPresent { h -> h.descendants().forEach { it.destroyForcibly() } } }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun InnerEnvelope_open(engine: DesktopEngine, body: String): String? =
        com.relay.app.messaging.InnerEnvelope.open(engine.keyMaterial, body)

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    private fun call(base: String, method: String, path: String, body: ByteArray?, keys: Keys): Pair<Int, String> {
        val url = base + path
        val builder = Request.Builder().url(url)
            .header("Authorization", MailboxAuth.header(DesktopNostrSigner(keys), method, url, body))
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: ByteArray(0)).toRequestBody(jsonType))
        }
        http.newCall(builder.build()).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private fun phoneKeyset(): String {
        com.google.crypto.tink.config.TinkConfig.register()
        val handle = KeysetHandle.generateNew(KeyTemplates.get(DesktopKeyMaterial.TEMPLATE))
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, JsonKeysetWriter.withOutputStream(out))
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun waitForHealth(port: Int, worker: Process, log: Path, timeoutSeconds: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        var lastError: Exception? = null
        while (System.nanoTime() < deadline) {
            if (!worker.isAlive) error("Local Worker exited; log:\n" + Files.readString(log))
            try {
                val conn = URI("http://127.0.0.1:$port/v1/health").toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                if (conn.responseCode == 200 && conn.inputStream.bufferedReader().readText().contains("\"ok\":true")) return
            } catch (e: Exception) {
                lastError = e
            }
            Thread.sleep(250)
        }
        error("Local Worker did not become healthy in ${timeoutSeconds}s: $lastError\n" + runCatching { Files.readString(log) }.getOrElse { "" })
    }

    private suspend fun waitFor(timeoutSeconds: Int, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline) {
            if (condition()) return
            delay(100)
        }
        error("condition not met within ${timeoutSeconds}s")
    }
}
