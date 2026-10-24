package com.relay.app.desktop

import com.relay.app.groups.GroupAvatarBody
import com.relay.app.groups.GroupProtocol
import com.relay.app.linking.LinkBundle
import com.relay.app.linking.LinkContact
import com.relay.app.linking.LinkIdentity
import com.relay.app.linking.LinkSettings
import com.relay.app.messaging.InnerEnvelope
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.mailbox.MailboxAuth
import com.relay.app.transport.mailbox.MailboxMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * WP07 group traffic over the real local Worker: the desktop creates a group with two independent JVM
 * peers, a peer posts (direct v2 delivery to every member, not via the creator), the desktop post
 * reaches both peers, and roster/avatar controls fan out. This is a JVM peer, NOT a phone.
 *
 * Gated by RELAY_GROUPS_E2E=1 and uses port 8799 with a scratch persist directory.
 */
class DesktopGroupsWorkerTest {

    @Test
    fun `group create post roster and avatar cross the local Worker`() = runBlocking<Unit> {
        assumeTrue("Set RELAY_GROUPS_E2E=1", System.getenv("RELAY_GROUPS_E2E") == "1")
        val root = Files.createTempDirectory("wp07-groups-worker-")
        val workerDir = Path.of("../server/cloudflare").toAbsolutePath().normalize()
        val node = "/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node"
        fun paths(label: String) = DesktopPaths(root.resolve("$label-config"), root.resolve("$label-data"))
        // The desktop identity exists before the Worker so its real pubkey can be allowlisted.
        val dp = paths("desktop")
        val desktopSecrets = FileSecretStore(dp.secretFile)
        val desktopIdentity = DesktopIdentity(dp, desktopSecrets)
        val desktopPub = desktopIdentity.publicKeyHex()
        val alice = Keys.generate(); val bob = Keys.generate()
        val alicePub = alice.publicKey().toHex(); val bobPub = bob.publicKey().toHex()
        val base = "https://127.0.0.1:8799"
        val worker = ProcessBuilder(
            node, "node_modules/wrangler/bin/wrangler.js", "dev", "--local", "--local-protocol", "https",
            "--port", "8799",
            "--var", "ALLOWED_PUBKEYS:$desktopPub,$alicePub,$bobPub",
            "--persist-to", root.resolve("state").toString(),
        ).directory(workerDir.toFile()).redirectErrorStream(true)
            .redirectOutput(root.resolve("worker.log").toFile())
            .apply { environment()["WRANGLER_SEND_METRICS"] = "false" }.start()

        // Test-only self-signed localhost fixture; production TLS validation is untouched.
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val http = OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier { host, _ -> host == "127.0.0.1" }
            .followRedirects(false)
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()

        val stores = mutableListOf<DesktopMessageStore>()
        var engine: DesktopEngine? = null
        val peerTransports = mutableListOf<DesktopMailboxTransport>()
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        try {
            withTimeoutOrNull(90_000) {
                while (true) {
                    if (!worker.isAlive) error("Worker exited; see $root/worker.log")
                    val ok = runCatching { http.newCall(Request.Builder().url("$base/v1/health").build()).execute().use { it.isSuccessful } }
                        .getOrDefault(false)
                    if (ok) break
                    delay(250)
                }
            } ?: error("Worker did not become healthy")

            // Built by hand so the desktop uses the same trust-all test client as the JVM peers; the
            // packaged app's production client keeps normal TLS validation.
            val desktopKeyMaterial = DesktopKeyMaterial(dp, desktopSecrets)
            val desktopStore = DesktopMessageStore(dp.databaseFile, desktopSecrets).also { stores += it }
            val desktopTransport = DesktopMailboxTransport(
                base, desktopIdentity.keys(), desktopIdentity.signer(), desktopStore, httpClient = http,
            )
            val desktop = DesktopEngine(desktopIdentity, desktopKeyMaterial, desktopStore, desktopTransport).also { engine = it; it.start() }

            val alicePaths = paths("alice"); val bobPaths = paths("bob")
            val aliceSecrets = FileSecretStore(alicePaths.secretFile)
            val bobSecrets = FileSecretStore(bobPaths.secretFile)
            val aliceKeyMaterial = DesktopKeyMaterial(alicePaths, aliceSecrets)
            val bobKeyMaterial = DesktopKeyMaterial(bobPaths, bobSecrets)
            val aliceStore = DesktopMessageStore(alicePaths.databaseFile, aliceSecrets).also { stores += it }
            val bobStore = DesktopMessageStore(bobPaths.databaseFile, bobSecrets).also { stores += it }
            val aliceTransport = DesktopMailboxTransport(base, alice, DesktopNostrSigner(alice), aliceStore, httpClient = http).also { peerTransports += it }
            val bobTransport = DesktopMailboxTransport(base, bob, DesktopNostrSigner(bob), bobStore, httpClient = http).also { peerTransports += it }
            val aliceInbox = Channel<IncomingEnvelope>(Channel.UNLIMITED)
            val bobInbox = Channel<IncomingEnvelope>(Channel.UNLIMITED)
            jobs += launch { aliceTransport.incoming.collect { aliceInbox.send(it) } }
            jobs += launch { bobTransport.incoming.collect { bobInbox.send(it) } }

            // The mailbox echoes a sender's own copy back to it, so wait for the packet we mean
            // rather than assuming inbox order.
            suspend fun awaitPacket(
                inbox: Channel<IncomingEnvelope>,
                keys: DesktopKeyMaterial,
                accept: (String) -> Boolean,
            ): String {
                while (true) {
                    val envelope = inbox.receive()
                    val plain = InnerEnvelope.open(keys, envelope.payload.body) ?: continue
                    envelope.onHandled?.invoke()
                    if (accept(plain)) return plain
                }
            }
            aliceTransport.start(); bobTransport.start()
            // Wait until every socket is actually online before relying on direct delivery.
            withTimeoutOrNull(60_000) {
                while (desktop.status.value != TransportStatus.ONLINE ||
                    aliceTransport.status.value != TransportStatus.ONLINE ||
                    bobTransport.status.value != TransportStatus.ONLINE
                ) { delay(100) }
            } ?: error(
                "transports not online: desktop=${desktop.status.value} alice=${aliceTransport.status.value} " +
                    "bob=${bobTransport.status.value}; worker log: $root/worker.log",
            )

            // The direct-delivery contact rows come through the production import path.
            desktop.store.importLinkBundle(
                LinkBundle(
                    createdAt = System.currentTimeMillis(),
                    identity = LinkIdentity("00".repeat(32), desktopPub, "primary", "fixture", "fixture"),
                    contacts = listOf(
                        LinkContact(name = "Alice", hasRelay = true, trustLevel = "trusted", nostrPubkey = alicePub, publicKey = aliceKeyMaterial.publicKeyBase64(), qrVerified = true),
                        LinkContact(name = "Bob", hasRelay = true, trustLevel = "trusted", nostrPubkey = bobPub, publicKey = bobKeyMaterial.publicKeyBase64(), qrVerified = true),
                    ),
                    groups = emptyList(),
                    settings = LinkSettings(),
                ),
                appliedAt = System.currentTimeMillis(),
            )

            // LP-D03: Alice stands in for the primary phone and authors the roster. The desktop
            // only receives it; no desktop roster-edit API is exercised.
            val phoneRoster = GroupProtocol.State(
                "11111111-1111-4111-8111-111111111111", alicePub, "Design", 1,
                listOf(
                    GroupProtocol.Member(alicePub, "Alice", aliceKeyMaterial.publicKeyBase64()),
                    GroupProtocol.Member(desktopPub, "Desktop", desktop.keyMaterial.publicKeyBase64()),
                    GroupProtocol.Member(bobPub, "Bob", bobKeyMaterial.publicKeyBase64()),
                ),
            )
            suspend fun phoneSend(peer: String, inner: String, body: String) {
                val sealed = InnerEnvelope.seal(aliceKeyMaterial, inner, body) ?: error("cannot seal phone control")
                assertTrue(aliceTransport.send(peer, RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), sealed)) is SendResult.Sent)
            }
            phoneSend(desktopPub, desktop.keyMaterial.publicKeyBase64(), GroupProtocol.format(GroupProtocol.Sync(phoneRoster)))
            phoneSend(bobPub, bobKeyMaterial.publicKeyBase64(), GroupProtocol.format(GroupProtocol.Sync(phoneRoster)))
            val group = withTimeoutOrNull(30_000) {
                while (desktop.store.storedGroup(alicePub, phoneRoster.id) == null) delay(100)
                desktop.store.storedGroup(alicePub, phoneRoster.id)!!
            } ?: error("phone roster not mirrored")
            val conversation = group.conversationId
            assertEquals("Design", group.name)
            assertEquals(3, group.members.size)

            // Bob receives the phone's roster; the desktop mirror was checked above.
            listOf(bobInbox to bobKeyMaterial).forEach { (inbox, keys) ->
                val plain = withTimeoutOrNull(30_000) {
                    awaitPacket(inbox, keys) { body ->
                        (GroupProtocol.parse(body) as? GroupProtocol.Sync)?.state?.name == "Design"
                    }
                } ?: error("roster not delivered; worker log: $root/worker.log")
                val sync = GroupProtocol.parse(plain) as GroupProtocol.Sync
                assertEquals("Design", sync.state.name)
                assertEquals(3, sync.state.members.size)
            }

            // A member posts straight to the other members (v2 direct delivery): desktop stores it once.
            val event = GroupProtocol.Event(
                kind = "post", group = GroupProtocol.ref(phoneRoster),
                event = "aaaaaaaa-1111-4111-8111-111111111111", author = bobPub, text = "hello from Bob's phone", time = System.currentTimeMillis(),
            )
            val wire = GroupProtocol.format(event)
            listOf(
                Triple(bobTransport, desktopPub, desktop.keyMaterial.publicKeyBase64()),
                Triple(bobTransport, alicePub, aliceKeyMaterial.publicKeyBase64()),
            ).forEach { (transport, peer, inner) ->
                val sealed = InnerEnvelope.seal(bobKeyMaterial, inner, wire) ?: error("cannot seal member post")
                val result = transport.send(peer, RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), sealed))
                assertTrue("member post send failed: $result", result is SendResult.Sent)
            }
            val received = withTimeoutOrNull(30_000) {
                while (desktop.messages(conversation).none { it.body == "hello from Bob's phone" }) delay(100)
                desktop.messages(conversation).first { it.body == "hello from Bob's phone" }
            } ?: error("desktop did not store the member post")
            assertEquals(bobPub, received.sender)
            assertEquals(1, desktop.store.unreadCount(conversation))

            // A desktop post reaches every member directly.
            val report = desktop.groups.send(conversation, "status update from the desktop")
            assertEquals("sent=${report.sent} failed=${report.failed}", 2, report.sent)
            assertEquals(0, report.failed)
            listOf(aliceInbox to aliceKeyMaterial, bobInbox to bobKeyMaterial).forEach { (inbox, keys) ->
                val plain = withTimeoutOrNull(30_000) {
                    awaitPacket(inbox, keys) { body ->
                        (GroupProtocol.parse(body) as? GroupProtocol.Event)?.text == "status update from the desktop"
                    }
                } ?: error("desktop post not delivered; worker log: $root/worker.log")
                val posted = GroupProtocol.parse(plain) as GroupProtocol.Event
                assertEquals("post", posted.kind)
                assertEquals(desktopPub, posted.author)
                assertEquals("status update from the desktop", posted.text)
            }

            // The primary phone alone changes the roster and supplies its avatar.
            val renamed = phoneRoster.copy(name = "Design v2", revision = 2)
            phoneSend(desktopPub, desktop.keyMaterial.publicKeyBase64(), GroupProtocol.format(GroupProtocol.Sync(renamed)))
            phoneSend(bobPub, bobKeyMaterial.publicKeyBase64(), GroupProtocol.format(GroupProtocol.Sync(renamed)))
            withTimeoutOrNull(30_000) {
                while (desktop.store.storedGroup(conversation)?.revision != 2L) delay(100)
            } ?: error("phone rename not mirrored")
            listOf(bobInbox to bobKeyMaterial).forEach { (inbox, keys) ->
                val plain = withTimeoutOrNull(30_000) {
                    awaitPacket(inbox, keys) { body ->
                        (GroupProtocol.parse(body) as? GroupProtocol.Sync)?.state?.name == "Design v2"
                    }
                } ?: error("rename not delivered; worker log: $root/worker.log")
                assertEquals("Design v2", (GroupProtocol.parse(plain) as GroupProtocol.Sync).state.name)
            }
            val jpeg = java.io.ByteArrayOutputStream().use { out ->
                javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", out)
                out.toByteArray()
            }
            val avatar = GroupAvatarBody.format(GroupAvatarBody.Update(group.sharedId, 1, jpeg))
            phoneSend(desktopPub, desktop.keyMaterial.publicKeyBase64(), avatar)
            phoneSend(bobPub, bobKeyMaterial.publicKeyBase64(), avatar)
            withTimeoutOrNull(30_000) {
                while (desktop.store.storedGroup(conversation)?.avatarRevision != 1L) delay(100)
            } ?: error("phone avatar not mirrored")
            listOf(bobInbox to bobKeyMaterial).forEach { (inbox, keys) ->
                val plain = withTimeoutOrNull(30_000) {
                    awaitPacket(inbox, keys) { body -> GroupAvatarBody.parse(body) != null }
                } ?: error("avatar not delivered; worker log: $root/worker.log")
                val update = GroupAvatarBody.parse(plain) ?: error("peer cannot parse avatar")
                assertTrue(update.jpeg!!.contentEquals(jpeg))
            }

            // A non-owner cannot move the roster.
            val forged = GroupProtocol.State(group.sharedId, alicePub, "Hijacked", 9, listOf(GroupProtocol.Member(alicePub, "Alice")))
            assertTrue(desktop.groups.handle(bobPub, GroupProtocol.format(GroupProtocol.Sync(forged))))
            assertEquals("Design v2", desktop.store.storedGroup(conversation)!!.name)
            assertEquals(3, desktop.store.storedGroup(conversation)!!.members.size)

            println(
                "WP07_GROUP_WORKER_OK desktop=$desktopPub alice=$alicePub bob=$bobPub " +
                    "members=${group.members.size} peer_post_stored=true desktop_post_sent=${report.sent} " +
                    "rename_rev=${renamed.revision} avatar_rev=${desktop.store.storedGroup(conversation)!!.avatarRevision} port=8799",
            )
        } finally {
            jobs.forEach { runCatching { it.cancel() } }
            runCatching { engine?.stop() }
            peerTransports.forEach { runCatching { it.stop() } }
            stores.forEach { runCatching { it.close() } }
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            val descendants = worker.descendants().toList().reversed()
            descendants.forEach { it.destroy() }
            worker.destroy()
            if (!worker.waitFor(15, TimeUnit.SECONDS)) worker.destroyForcibly()
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }
}
