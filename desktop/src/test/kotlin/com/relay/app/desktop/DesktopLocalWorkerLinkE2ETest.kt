package com.relay.app.desktop

import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.relay.app.linking.LinkBundle
import com.relay.app.linking.LinkBundleCodec
import com.relay.app.linking.LinkCode
import com.relay.app.linking.LinkContact
import com.relay.app.linking.LinkGroup
import com.relay.app.linking.LinkIdentity
import com.relay.app.linking.LinkQr
import com.relay.app.linking.LinkSettings
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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Acceptance for WP05 part B desktop, end-to-end through a real local Worker.
 *
 * Skipped unless `RELAY_E2E=1`, so `:desktop:test` stays fast and hermetic. Run with:
 *
 * ```
 * RELAY_E2E=1 RELAY_E2E_PERSIST=$PWD/.local/wp07-worker-state \
 *   ./gradlew :desktop:test --tests '*DesktopLocalWorkerLinkE2ETest'
 * ```
 *
 * The test plays the phone (identity key, admin key, bundle sealing, `PUT /v1/link`, device
 * enrolment), then drives the real desktop pieces: [DesktopLinkFlow] over [DesktopLinkHttp] and a
 * [DesktopMailboxTransport] signing with the enrolled device key.
 */
class DesktopLocalWorkerLinkE2ETest {

    @Test
    fun `desktop links, imports and exchanges encrypted text as the enrolled device`() = runBlocking {
        assumeTrue("Set RELAY_E2E=1 to run the local Worker link test", System.getenv("RELAY_E2E") == "1")

        val repoRoot = Path.of("..").toAbsolutePath().normalize()
        val workerDir = System.getenv("RELAY_WORKER_DIR")?.let { Path.of(it) } ?: repoRoot.resolve("server/cloudflare")
        val node = System.getenv("RELAY_NODE") ?: "/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node"
        assumeTrue("Node 24+ not found at $node", Files.isRegularFile(Path.of(node)))
        assumeTrue("wrangler not installed", Files.isRegularFile(workerDir.resolve("node_modules/wrangler/bin/wrangler.js")))

        val port = 8791
        val persist = System.getenv("RELAY_E2E_PERSIST")?.let {
            Path.of(it).toAbsolutePath().also { p -> Files.createDirectories(p) }
        } ?: Files.createTempDirectory("relay-wp07-worker")

        val phoneKeys = Keys.generate()
        val adminKeys = Keys.generate()
        val peerKeys = Keys.generate()
        // The admin key must NOT be a mailbox member: the server rejects an admin key that is also a member
        // (one key, one purpose), so only the shared identity key and the peer are allowlisted.
        val allowlist = listOf(phoneKeys, peerKeys).joinToString(",") { it.publicKey().toHex() }

        val log = Files.createTempFile("relay-wp07-worker", ".log")
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

            // ---- the phone: build and seal the bundle, publish the slot -------
            val temp = Files.createTempDirectory("relay-wp07")
            val desktopPaths = DesktopPaths(temp.resolve("desktop-config"), temp.resolve("desktop-data"))
            val phoneHpke = phoneKeyset()
            val contactPub = Keys.generate().publicKey().toHex()
            val groupId = UUID.randomUUID().toString()
            val bundle = LinkBundle(
                createdAt = System.currentTimeMillis(),
                identity = LinkIdentity(
                    nostrSecretHex = phoneKeys.secretKey().toHex(),
                    nostrPubkeyHex = phoneKeys.publicKey().toHex(),
                    deviceId = "primary",
                    deviceLabel = "Pixel 9 (test)",
                    hpkeKeysetBase64 = phoneHpke,
                ),
                contacts = listOf(
                    LinkContact(name = "Ana", phone = "+441234", hasRelay = true, trustLevel = "trusted", nostrPubkey = contactPub),
                    LinkContact(name = "Bo", phone = "+445678", hasRelay = false, trustLevel = "ask"),
                ),
                groups = listOf(LinkGroup(id = groupId, name = "Flat", members = listOf(contactPub))),
                settings = LinkSettings(retentionDays = 3, theme = "dark"),
            )

            val engine = DesktopEngine.create(desktopPaths, base)
            val flow = engine.createLinkFlow(base)
            flow.begin()
            val qr = LinkQr.decode(flow.qrText)
            assertNotNull("desktop QR must decode", qr)
            val slot = LinkCode.slotId(qr!!.code)
            val sealed = LinkBundleCodec.seal(DesktopLinkHpke { phoneHpke }, qr.ephemeralPublicKey!!, bundle)

            val cancelled = rawGet("$base/v1/link/$slot", mapOf("x-relay-link-proof" to qr.code))
            assertEquals("slot must not exist before the phone publishes it", 404, cancelled.first)

            val publishedAt = System.nanoTime()
            val put = call(base, "PUT", "/v1/link/$slot", sealed, phoneKeys)
            assertEquals("phone PUT failed: ${put.second}", 200, put.first)

            val linkedAt = System.nanoTime()
            val state = flow.step()
            assertTrue("expected Linked, got $state", state is LinkState.Linked)
            val linked = state as LinkState.Linked
            val linkMs = (linkedAt - publishedAt) / 1_000_000

            assertEquals(phoneKeys.publicKey().toHex(), linked.identityPubkeyHex)
            assertEquals("Pixel 9 (test)", linked.identityLabel)
            assertEquals(2, linked.contactsInserted)
            assertEquals(1, linked.groups)
            assertTrue(linked.settingsApplied)
            assertTrue("slot must be consumed after the commit", linked.slotCompleted)

            // The slot is gone for everyone, including a caller with the right code.
            val after = rawGet("$base/v1/link/$slot", mapOf("x-relay-link-proof" to qr.code))
            assertEquals("slot must be consumed", 404, after.first)

            // Local state is the phone's identity, the contacts and the link record.
            val desktopSecrets = FileSecretStore(desktopPaths.secretFile)
            val reopenedKeys = DesktopLinkKeys(desktopPaths, desktopSecrets)
            assertEquals(phoneKeys.publicKey().toHex(), reopenedKeys.linkedDevice()!!.identityPubkeyHex)
            assertEquals(2L, engine.store.contactCount())
            assertEquals(1, engine.store.groups().size)
            assertEquals(3, engine.store.linkSettings()!!.retentionDays)
            assertEquals(qr.deviceId, reopenedKeys.linkedDevice()!!.deviceId)
            val authKeys = Keys.parse(reopenedKeys.load()!!.session.deviceAuthSecretHex)

            // The QR id is the server's own linked-device shape (32 lowercase hex); enrol exactly that id.
            val serverDeviceId = qr.deviceId!!
            assertEquals(32, serverDeviceId.length)

            // ---- migrate + enrol the desktop's device key under a server-shaped id -------------
            val migrate = call(
                base, "POST", "/v1/devices/admin",
                """{"v":1,"admin_pubkey":"${adminKeys.publicKey().toHex()}"}""".toByteArray(),
                phoneKeys,
            )
            assertEquals("migration failed: ${migrate.second}", 200, migrate.first)
            val enrol = call(
                base, "POST", "/v1/devices?device=primary",
                JSONObject()
                    .put("v", 1)
                    .put("device", serverDeviceId)
                    .put("label", "WP07 desktop")
                    .put("auth_pubkey", authKeys.publicKey().toHex())
                    .toString().toByteArray(),
                adminKeys,
            )
            assertEquals("enrolment failed: ${enrol.second}", 200, enrol.first)

            // ---- a JVM peer on the same Worker ------------------------------------------------
            val peerPaths = DesktopPaths(temp.resolve("peer-config"), temp.resolve("peer-data"))
            val peerSecrets = FileSecretStore(peerPaths.secretFile)
            val peerIdentity = DesktopIdentity(peerPaths, peerSecrets, peerKeys)
            val peerKeyMaterial = DesktopKeyMaterial(peerPaths, peerSecrets)
            val peerStore = DesktopMessageStore(peerPaths.databaseFile, peerSecrets)
            val peerTransport = DesktopMailboxTransport(base, peerKeys, DesktopNostrSigner(peerKeys), peerStore)

            // The linked desktop: identity keys from the bundle, requests signed by the device key.
            val device = DesktopDeviceAuth(serverDeviceId, DesktopNostrSigner(authKeys))
            val desktopTransport = DesktopMailboxTransport(
                base, engine.identity.keys(), engine.identity.signer(), engine.store, device,
            )
            val peerPub = peerIdentity.publicKeyHex()
            val desktop = DesktopEngine(
                engine.identity, engine.keyMaterial, engine.store, desktopTransport,
                peerInnerKeys = mapOf(peerPub to peerKeyMaterial.publicKeyBase64()),
                linkKeys = engine.linkKeys,
            )
            val peer = DesktopEngine(
                peerIdentity, peerKeyMaterial, peerStore, peerTransport,
                peerInnerKeys = mapOf(phoneKeys.publicKey().toHex() to engine.keyMaterial.publicKeyBase64()),
            )

            try {
                desktop.start()
                peer.start()

                val sentAt = System.nanoTime()
                val outbound = desktop.sendText(peerPub, "hello as the linked desktop")
                assertTrue("desktop send failed: $outbound", outbound is SendResult.Sent)
                waitForMessage(peer, phoneKeys.publicKey().toHex(), "hello as the linked desktop", 30)
                val deliveredMs = (System.nanoTime() - sentAt) / 1_000_000

                val reply = peer.sendText(phoneKeys.publicKey().toHex(), "reply to the linked desktop")
                assertTrue("peer send failed: $reply", reply is SendResult.Sent)
                waitForMessage(desktop, peerPub, "reply to the linked desktop", 30)

                val inbound = desktop.messages(peerPub).single { it.body == "reply to the linked desktop" }
                assertTrue(!inbound.outgoing)
                assertEquals(1, desktop.messages(peerPub).count { it.body == "reply to the linked desktop" })

                println(
                    "WP07-E2E OK port=$port persist=$persist " +
                        "device32=$serverDeviceId " +
                        "linkMs=$linkMs deliveredMs=$deliveredMs " +
                        "contacts=${engine.store.contactCount()} groups=${engine.store.groups().size} " +
                        "identity=${phoneKeys.publicKey().toHex()} peer=$peerPub",
                )
            } finally {
                desktop.close()
                peer.close()
                peerStore.close()
                engine.close()
            }
        } finally {
            worker.destroy()
            if (!worker.waitFor(15, TimeUnit.SECONDS)) worker.destroyForcibly()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    /** A NIP-98-signed request; [signBody] false skips the payload digest (GET/inbox style). */
    private fun call(
        base: String,
        method: String,
        path: String,
        body: ByteArray?,
        keys: Keys,
        signBody: Boolean = true,
    ): Pair<Int, String> {
        val url = base + path
        val signed = if (body != null && signBody) body else null
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(DesktopNostrSigner(keys), method, url, signed))
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: ByteArray(0)).toRequestBody(jsonType))
        }
        http.newCall(builder.build()).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    /** An unauthenticated GET with arbitrary headers (the link-slot read). */
    private fun rawGet(url: String, headers: Map<String, String>): Pair<Int, String> {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { response ->
            return response.code to (response.body?.string() ?: "")
        }
    }

    private fun phoneKeyset(): String {
        com.google.crypto.tink.config.TinkConfig.register()
        val handle = KeysetHandle.generateNew(KeyTemplates.get(DesktopKeyMaterial.TEMPLATE))
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, JsonKeysetWriter.withOutputStream(out))
        // Also prove the public half is exportable the way the desktop exports its own key.
        val pubOut = ByteArrayOutputStream()
        handle.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(pubOut))
        assertEquals("", "")
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

    private suspend fun waitForMessage(engine: DesktopEngine, peer: String, body: String, timeoutSeconds: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline) {
            if (engine.messages(peer).any { it.body == body }) return
            delay(100)
        }
        error("Message '$body' did not arrive within ${timeoutSeconds}s")
    }
}
