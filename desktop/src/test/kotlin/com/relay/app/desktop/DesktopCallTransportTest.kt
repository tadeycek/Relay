package com.relay.app.desktop

import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.mailbox.MailboxMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rust.nostr.sdk.Keys
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** WP14 transport wiring: live signal frames, urgent stored invite, call routing and `GET /v1/call/ice`. */
class DesktopCallTransportTest {

    private class Raw : RawPacketStore {
        val items = CopyOnWriteArrayList<MailboxMessage>()
        override fun store(messages: List<MailboxMessage>): List<Long> {
            val fresh = messages.filter { m -> items.none { it.seq == m.seq } }
            items += fresh
            return fresh.map { it.seq }
        }
        override fun pending(): List<MailboxMessage> = items.toList()
        override fun delete(seq: Long): Boolean = items.removeAll { it.seq == seq }
    }

    private val keys = Keys.generate()
    private val peer = Keys.generate()
    private val me = keys.publicKey().toHex()
    private val peerHex = peer.publicKey().toHex()
    private val callBody = "TYPE:CALL|J:{\"x\":1}"
    private fun header(callee: String) = com.relay.app.call.CallHeader("0123456789abcdef0123456789abcdef", 1_000L, 31_000L, me, callee, "primary", "f".repeat(32))
    /** A real, codec-valid invite from this identity to [peerHex]. */
    private val inviteBody = com.relay.app.call.CallBodyCodec.encode(com.relay.app.call.CallPacket.Invite(header(peerHex)))
    private val cancelBody = com.relay.app.call.CallBodyCodec.encode(com.relay.app.call.CallPacket.Cancel(header(peerHex)))
    private val servers = mutableListOf<MockWebServer>()
    private val transports = mutableListOf<DesktopMailboxTransport>()
    private val sockets = mutableListOf<WebSocket>()

    @After fun cleanup() {
        runBlocking { transports.forEach { runCatching { it.stop() } } }
        sockets.forEach { runCatching { it.close(1000, "done") } }
        Thread.sleep(200)
        servers.forEach { runCatching { it.shutdown() } }
    }

    private class Wire(val server: MockWebServer, val frames: LinkedBlockingQueue<String>, val socket: CompletableDeferred<WebSocket>)

    private fun wsServer(onOpen: (WebSocket) -> Unit = {}): Wire {
        val frames = LinkedBlockingQueue<String>()
        val socket = CompletableDeferred<WebSocket>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket.complete(webSocket); sockets += webSocket
                webSocket.send("""{"type":"ready","expiresAt":9999999999999}""")
                onOpen(webSocket)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (JSONObject(text).getString("type") == "signal") frames.add(text) }
        }))
        server.start(); servers += server
        return Wire(server, frames, socket)
    }

    private fun transport(url: String, device: DesktopDeviceAuth? = null, raw: RawPacketStore = Raw()) =
        DesktopMailboxTransport(url, keys, DesktopNostrSigner(keys), raw, device).also { transports += it }

    private suspend fun online(t: DesktopMailboxTransport) {
        t.start()
        withTimeout(15_000) { while (t.status.value != TransportStatus.ONLINE) kotlinx.coroutines.delay(25) }
    }

    @Test fun liveSignalIsGiftWrappedSignalFrameOnLiveSocketAndNeverStored() = runBlocking {
        val wire = wsServer()
        val t = transport(wire.server.url("/").toString())
        online(t)
        val result = t.send(peerHex, RelayPayload("c1", 1_000L, callBody), ephemeral = true)
        assertTrue("$result", result is SendResult.Sent)
        val frame = JSONObject(wire.frames.poll(5, TimeUnit.SECONDS)!!)
        assertEquals("signal", frame.getString("type"))
        assertEquals(peerHex, frame.getString("to"))
        assertTrue(frame.getString("ref").isNotEmpty())
        val opened = DesktopGiftWrap.unwrap(peer, frame.getString("envelope"))!!
        assertEquals(me, opened.first)
        assertEquals(callBody, opened.second.body)
        assertEquals("a live signal is not an HTTP delivery", 1, wire.server.requestCount) // only the upgrade
    }

    @Test fun signalEnvelopeOverTheServerBoundIsRefusedAndNothingIsSent() = runBlocking {
        val wire = wsServer()
        val t = transport(wire.server.url("/").toString())
        online(t)
        val huge = "TYPE:CALL|J:" + "x".repeat(20_000)
        val result = t.send(peerHex, RelayPayload("c2", 1_000L, huge), ephemeral = true)
        assertTrue("$result", result is SendResult.Failed && !result.retryable)
        assertNull(wire.frames.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test fun signalWithoutALiveSocketFailsRetryableInsteadOfPretendingToSend() = runBlocking {
        val t = transport("http://127.0.0.1:9") // never started
        val result = t.send(peerHex, RelayPayload("c3", 1_000L, callBody), ephemeral = true)
        assertTrue("$result", result is SendResult.Failed && result.retryable)
    }

    @Test fun onlyCallBodiesMayBeSentAsSignals() = runBlocking {
        val wire = wsServer()
        val t = transport(wire.server.url("/").toString())
        online(t)
        val result = t.send(peerHex, RelayPayload("c4", 1_000L, "typing"), ephemeral = true)
        assertTrue("$result", result is SendResult.Failed && !result.retryable)
        assertNull(wire.frames.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun urgentInviteIsStoredWithUrgentTrueAndTheDeviceQuery() = runBlocking {
        val server = MockWebServer(); servers += server
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"v":1,"results":[{"status":"stored","seq":3}]}"""))
        server.start()
        val deviceKeys = Keys.generate()
        val device = DesktopDeviceAuth("d".repeat(32), DesktopNostrSigner(deviceKeys))
        val t = transport(server.url("/").toString(), device)
        val result = t.deliverUrgent(peerHex, RelayPayload("u1", 1_000L, inviteBody))
        assertTrue("$result", result is SendResult.Sent)
        val request = server.takeRequest()
        assertEquals("/v1/send?device=${"d".repeat(32)}", request.path)
        val body = JSONObject(request.body.readUtf8())
        val delivery = body.getJSONArray("deliveries").getJSONObject(0)
        assertEquals(true, delivery.getBoolean("urgent"))
        assertEquals(1, body.getJSONArray("deliveries").length())
        assertEquals(peerHex, delivery.getString("to"))
        assertEquals(inviteBody, DesktopGiftWrap.unwrap(peer, delivery.getString("envelope"))!!.second.body)
        assertEquals(deviceKeys.publicKey().toHex(), authEvent(request.getHeader("Authorization")!!).getString("pubkey"))
    }

    @Test fun urgentDeliveryToOwnIdentityIsRefusedBeforeAnyRequest() = runBlocking {
        val server = MockWebServer(); servers += server; server.start()
        val t = transport(server.url("/").toString())
        val result = t.deliverUrgent(me, RelayPayload("u2", 1_000L, inviteBody))
        assertTrue("$result", result is SendResult.Failed && !result.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test fun urgentRateLimitIsReportedRetryableAndNothingElseHappens() = runBlocking {
        val server = MockWebServer(); servers += server
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"v":1,"error":{"code":"rate_limited","retryable":true}}"""))
        server.start()
        val t = transport(server.url("/").toString())
        val result = t.deliverUrgent(peerHex, RelayPayload("u3", 1_000L, inviteBody))
        assertTrue("$result", result is SendResult.Failed && result.retryable)
    }

    @Test fun receivedCallSignalGoesToTheCallRouterNotTheChatInbox() = runBlocking {
        val received = LinkedBlockingQueue<Triple<String, String, Boolean>>()
        val wire = wsServer { ws ->
            runBlocking {
                val env = DesktopGiftWrap.wrap(peer, me, RelayPayload("s1", 1_000L, callBody))
                ws.send(JSONObject().put("type", "signal").put("envelope", env).toString())
            }
        }
        val t = transport(wire.server.url("/").toString())
        t.callRouter = DesktopCallRouter { sender, body, stored -> received.add(Triple(sender, body, stored)); true }
        online(t)
        val got = received.poll(10, TimeUnit.SECONDS)!!
        assertEquals(Triple(peerHex, callBody, false), got)
        assertNull("call bodies must never become chat rows", inboxPeek(t))
    }

    /** A chat envelope would be available within a moment; null means the inbox stayed empty. */
    private suspend fun inboxPeek(t: DesktopMailboxTransport): Any? = withTimeoutOrNull(700) { t.incoming.first() }

    @Test fun storedCallDeliveryIsRoutedAndDeletedOnlyAfterTheHostHandledIt() = runBlocking {
        val env = DesktopGiftWrap.wrap(peer, me, RelayPayload("st1", 1_000L, callBody))
        val wire = wsServer { ws -> ws.send("""{"type":"batch","messages":[{"seq":1,"envelope":${JSONObject.quote(env)}}],"more":false,"cursor":1}""") }
        val raw = Raw()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val t = transport(wire.server.url("/").toString(), raw = raw)
        t.callRouter = DesktopCallRouter { _, _, stored ->
            assertTrue(stored); entered.countDown(); release.await(10, TimeUnit.SECONDS); true
        }
        online(t)
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        Thread.sleep(400)
        assertEquals("still pending while the host is handling it", 1, raw.pending().size)
        release.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (raw.pending().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(0, raw.pending().size)
    }

    @Test fun storedCallDeliveryWhoseHandlingFailsIsKeptForARetryThenDropped() = runBlocking {
        val env = DesktopGiftWrap.wrap(peer, me, RelayPayload("st2", 1_000L, callBody))
        val wire = wsServer { ws -> ws.send("""{"type":"batch","messages":[{"seq":1,"envelope":${JSONObject.quote(env)}}],"more":false,"cursor":1}""") }
        val raw = Raw()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val t = transport(wire.server.url("/").toString(), raw = raw)
        t.callRouter = DesktopCallRouter { _, _, _ -> calls.incrementAndGet(); error("host busy") }
        online(t)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (raw.pending().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals("poison call packet must not loop forever", 0, raw.pending().size)
        assertTrue("retried before dropping", calls.get() >= 2)
    }

    @Test fun storedCallDeliveryWithNoRouterIsDroppedNotShownAsAMessage() = runBlocking {
        val env = DesktopGiftWrap.wrap(peer, me, RelayPayload("st3", 1_000L, callBody))
        val wire = wsServer { ws -> ws.send("""{"type":"batch","messages":[{"seq":1,"envelope":${JSONObject.quote(env)}}],"more":false,"cursor":1}""") }
        val raw = Raw()
        val t = transport(wire.server.url("/").toString(), raw = raw)
        online(t)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (raw.pending().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
        assertEquals(0, raw.pending().size)
        assertNull(inboxPeek(t))
    }

    // ---- GET /v1/call/ice --------------------------------------------------------------------

    private fun authEvent(header: String): JSONObject =
        JSONObject(String(Base64.getDecoder().decode(header.removePrefix("Nostr ")), Charsets.UTF_8))

    private fun fetch(t: DesktopMailboxTransport): Pair<LinkedBlockingQueue<Pair<Int, String?>>, AutoCloseable> {
        val q = LinkedBlockingQueue<Pair<Int, String?>>()
        return q to t.fetchIce { status, body -> q.add(status to body) }
    }

    @Test fun iceFetchIsSignedWithTheDeviceKeyAndCarriesTheDeviceQuery() {
        val server = MockWebServer(); servers += server
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"v":1,"iceServers":[],"expiresAt":1}"""))
        server.start()
        val deviceKeys = Keys.generate()
        val t = transport(server.url("/").toString(), DesktopDeviceAuth("e".repeat(32), DesktopNostrSigner(deviceKeys)))
        val (q, _) = fetch(t)
        assertEquals(200, q.poll(10, TimeUnit.SECONDS)!!.first)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/call/ice?device=${"e".repeat(32)}", request.path)
        val event = authEvent(request.getHeader("Authorization")!!)
        assertEquals(deviceKeys.publicKey().toHex(), event.getString("pubkey"))
        val tags = event.getJSONArray("tags")
        val u = (0 until tags.length()).map { tags.getJSONArray(it) }.first { it.getString(0) == "u" }.getString(1)
        assertTrue(u, u.endsWith("/v1/call/ice?device=${"e".repeat(32)}"))
    }

    @Test fun iceFetchWithoutALinkedDeviceHasNoDeviceParameter() {
        val server = MockWebServer(); servers += server
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}")); server.start()
        val t = transport(server.url("/").toString())
        val (q, _) = fetch(t)
        q.poll(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/call/ice", server.takeRequest().path)
    }

    @Test fun iceResponseOverTheBoundIsAFailureNotAHugeBuffer() {
        val server = MockWebServer(); servers += server
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(100_000))); server.start()
        val t = transport(server.url("/").toString())
        val (q, _) = fetch(t)
        val (status, body) = q.poll(10, TimeUnit.SECONDS)!!
        assertEquals(0, status)
        assertNull(body)
    }

    @Test fun cancelledIceFetchNeverReportsALateReply() {
        val server = MockWebServer(); servers += server
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}").setBodyDelay(2, TimeUnit.SECONDS)); server.start()
        val t = transport(server.url("/").toString())
        val (q, handle) = fetch(t)
        Thread.sleep(200)
        handle.close()
        assertNull("late reply after cancel must be dropped", q.poll(3, TimeUnit.SECONDS))
    }

    @Test fun iceTransportErrorIsReportedAsStatusZero() {
        val t = transport("http://127.0.0.1:1")
        val (q, _) = fetch(t)
        assertEquals(0, q.poll(15, TimeUnit.SECONDS)!!.first)
    }

    @Test fun urgentDeliveryRequiresAnActualInviteNotJustTheCallPrefix() = runBlocking {
        val server = MockWebServer(); servers += server; server.start()
        val t = transport(server.url("/").toString())
        for ((id, body) in listOf("u4" to callBody, "u5" to cancelBody, "u6" to "TYPE:CALL|J:!!!notbase64")) {
            val result = t.deliverUrgent(peerHex, RelayPayload(id, 1_000L, body))
            assertTrue("$id: $result", result is SendResult.Failed && !result.retryable)
        }
        // An invite addressed to someone else than the recipient is not this call's invite either.
        val other = com.relay.app.call.CallBodyCodec.encode(com.relay.app.call.CallPacket.Invite(header("c".repeat(64))))
        assertTrue(t.deliverUrgent(peerHex, RelayPayload("u7", 1_000L, other)) is SendResult.Failed)
        assertEquals("nothing reached the network", 0, server.requestCount)
    }

    @Test fun liveSignalFramesReachTheHostInArrivalOrder() = runBlocking {
        val order = LinkedBlockingQueue<String>()
        val first = callBody + "A"; val second = callBody + "B"
        val wire = wsServer { ws ->
            runBlocking {
                for (b in listOf(first, second)) {
                    val env = DesktopGiftWrap.wrap(peer, me, RelayPayload("s-" + b.last(), 1_000L, b))
                    ws.send(JSONObject().put("type", "signal").put("envelope", env).toString())
                }
            }
        }
        val t = transport(wire.server.url("/").toString())
        t.callRouter = DesktopCallRouter { _, body, _ ->
            if (body.endsWith("A")) Thread.sleep(400) // a slow host must not let the next frame overtake it
            order.add(body.last().toString()); true
        }
        online(t)
        val got = listOf(order.poll(10, TimeUnit.SECONDS), order.poll(10, TimeUnit.SECONDS))
        assertEquals(listOf("A", "B"), got)
    }
}
