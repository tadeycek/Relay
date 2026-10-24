package com.relay.app.desktop

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.relay.app.groups.GroupProtocol
import com.relay.app.linking.GroupRosterSync
import com.relay.app.linking.LinkBundle
import com.relay.app.linking.LinkBundleCodec
import com.relay.app.linking.LinkCode
import com.relay.app.linking.LinkContact
import com.relay.app.linking.LinkIdentity
import com.relay.app.linking.LinkQr
import com.relay.app.linking.LinkSettings
import com.relay.app.linking.SyncCodec
import com.relay.app.transport.mailbox.MailboxAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * WP05-AC3/AC4 over a real local Worker (port 8797): a JVM stand-in for the phone-primary (NOT a phone) posts
 * GROUP roster events built by the shared `GroupRosterSync` schema and sealed with REAL HPKE to the account key,
 * through the real `/v1/sync`; the linked desktop's own sync loop applies them: create (full-size picture, chunked
 * over several server events), a newer rename, an avatar-only change, a stale replay, and a tombstone.
 *
 * Skipped unless `RELAY_E2E=1`. Run with the pinned Node 24 and without RELAY_WORKER_DIR / XDG_* exported:
 * `env -u RELAY_WORKER_DIR RELAY_E2E=1 RELAY_NODE=<node 24> ./gradlew :desktop:test --tests '*DesktopLocalWorkerGroupSyncE2ETest'`.
 */
class DesktopLocalWorkerGroupSyncE2ETest {

    @Test
    fun `phone roster events cross the real sync endpoint and the desktop mirrors them`() = runBlocking {
        assumeTrue("Set RELAY_E2E=1 to run the local Worker group-sync test", System.getenv("RELAY_E2E") == "1")

        val repoRoot = Path.of("..").toAbsolutePath().normalize()
        val workerDir = System.getenv("RELAY_WORKER_DIR")?.let { Path.of(it) } ?: repoRoot.resolve("server/cloudflare")
        val node = System.getenv("RELAY_NODE") ?: "/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node"
        assumeTrue("Node 24+ not found at $node", Files.isRegularFile(Path.of(node)))
        assumeTrue("wrangler not installed", Files.isRegularFile(workerDir.resolve("node_modules/wrangler/bin/wrangler.js")))

        val port = 8797
        val persist = Files.createTempDirectory("relay-wp05-group-state")

        val phoneKeys = Keys.generate()
        val adminKeys = Keys.generate()
        val allowlist = phoneKeys.publicKey().toHex()

        val log = Files.createTempFile("relay-wp05-group-worker", ".log")
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

            val temp = Files.createTempDirectory("relay-wp05-group")
            val desktopPaths = DesktopPaths(temp.resolve("desktop-config"), temp.resolve("desktop-data"))
            val phoneHpke = phoneKeyset()
            val bundle = LinkBundle(
                createdAt = System.currentTimeMillis(),
                identity = LinkIdentity(
                    nostrSecretHex = phoneKeys.secretKey().toHex(),
                    nostrPubkeyHex = phoneKeys.publicKey().toHex(),
                    deviceId = "primary",
                    deviceLabel = "Pixel (test)",
                    hpkeKeysetBase64 = phoneHpke,
                ),
                contacts = emptyList(),
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
                JSONObject().put("v", 1).put("device", deviceId).put("label", "group-sync desktop")
                    .put("auth_pubkey", authKeys.publicKey().toHex()).toString().toByteArray(), adminKeys).first)

            val sealToSelf: (String) -> String? = {
                com.relay.app.messaging.InnerEnvelope.seal(engine.keyMaterial, engine.keyMaterial.publicKeyBase64(), it)
            }
            val device = DesktopDeviceAuth(deviceId, DesktopNostrSigner(authKeys))
            val desktopSyncHttp = DesktopSyncHttp(base, device.signer, deviceId)
            val desktop = DesktopEngine(
                engine.identity, engine.keyMaterial, engine.store,
                DesktopMailboxTransport(base, engine.identity.keys(), engine.identity.signer(), engine.store, device),
                linkKeys = engine.linkKeys,
                syncPublisher = SentSyncPublisher(deviceId, engine.store, desktopSyncHttp, sealToSelf = sealToSelf),
                syncApplier = SentSyncApplier(
                    deviceId, engine.identity.publicKeyHex(), desktopSyncHttp, engine.store,
                    openBody = { com.relay.app.messaging.InnerEnvelope.open(engine.keyMaterial, it) },
                    knownConversation = { engine.store.conversation(it) != null },
                ),
                syncIntervalMs = 1_000,
            )
            val phoneHttp = DesktopSyncHttp(base, DesktopNostrSigner(adminKeys), "primary")

            val owner = phoneKeys.publicKey().toHex()
            val gid = "11111111-2222-4333-8444-555555555555"
            val conv = groupConversationId(owner, gid)
            val jpeg = Base64.getDecoder().decode(GROUP_JPEG)
            val members = listOf(GroupProtocol.Member(owner, "Me")) + (1 until 17).map {
                GroupProtocol.Member("%064x".format(it), "member $it", "B".repeat(150))
            }
            fun roster(revision: Long, name: String = "Team", avatarRevision: Long = 1, avatar: ByteArray? = jpeg, tombstone: Boolean = false) =
                GroupRosterSync.Roster(GroupProtocol.State(gid, owner, name, revision, members), avatarRevision, avatar, tombstone)

            suspend fun publish(r: GroupRosterSync.Roster): Int {
                val body = GroupRosterSync.seal(r, "primary", sealToSelf)
                val wires = SyncCodec.chunk(body)
                for (w in wires) {
                    val result = phoneHttp.post(SyncWire.envelope(w, GroupRosterSync.syncRevision(r), System.currentTimeMillis()))
                    assertTrue("post failed: $result", result is SyncHttpResult.Ok)
                }
                return wires.size
            }

            try {
                desktop.start()

                val parts = publish(roster(1))
                assertTrue("a 17-member roster with a picture must be chunked", parts >= 2)
                waitFor(30) { engine.store.storedGroup(conv)?.avatar != null }
                var g = engine.store.storedGroup(conv)!!
                assertEquals("Team", g.name); assertEquals(17, g.members.size); assertEquals(1L, g.avatarRevision)
                assertTrue(engine.store.conversations().any { it.id == conv })

                publish(roster(2, name = "Team 2"))
                waitFor(30) { engine.store.storedGroup(conv)?.revision == 2L }
                assertEquals("Team 2", engine.store.storedGroup(conv)!!.name)

                publish(roster(2, name = "Team 2", avatarRevision = 2, avatar = null)) // avatar-only
                waitFor(30) { engine.store.storedGroup(conv)?.avatarRevision == 2L }
                assertEquals(null, engine.store.storedGroup(conv)!!.avatar)

                publish(roster(1, name = "stale")) // stale replay, different event id
                delay(3_000)
                assertEquals("Team 2", engine.store.storedGroup(conv)!!.name)

                publish(roster(3, name = "Team 2", avatarRevision = 2, avatar = null, tombstone = true))
                waitFor(30) { engine.store.storedGroup(conv)?.hidden == true }
                assertFalse(engine.store.conversations().any { it.id == conv })

                delay(2_500)
                g = engine.store.storedGroup(conv)!!
                val serverEvents = (phoneHttp.list(0, 8) as SyncHttpResult.Ok).value.events.size
                val rawServer = (phoneHttp.list(0, 8) as SyncHttpResult.Ok).value.events.joinToString("") { String(Base64.getDecoder().decode(it.dataBase64)) }
                assertFalse("server must hold only sealed rosters", rawServer.contains("Team") || rawServer.contains("member 1"))
                println("WP05-GROUP-E2E OK port=$port parts=$parts revision=${g.revision} hidden=${g.hidden} cursor=${engine.store.syncCursor()} serverEventsFirstPage=$serverEvents")
            } finally {
                desktop.close()
                engine.close()
            }
        } finally {
            worker.destroy()
            if (!worker.waitFor(15, TimeUnit.SECONDS)) worker.destroyForcibly()
            runCatching { ProcessHandle.of(worker.pid()).ifPresent { h -> h.descendants().forEach { it.destroyForcibly() } } }
            persist.toFile().deleteRecursively()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

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
    companion object {
        const val GROUP_JPEG = "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"
    }
}
