package com.relay.app.desktop

import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.mailbox.MailboxMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import rust.nostr.sdk.Keys

class DesktopMailboxTransportTest {

    private class FakeRawStore : RawPacketStore {
        private val lock = Any()
        private val items = mutableListOf<MailboxMessage>()

        override fun store(messages: List<MailboxMessage>): List<Long> = synchronized(lock) {
            val fresh = messages.filter { candidate -> items.none { it.seq == candidate.seq } }
            items += fresh
            fresh.map { it.seq }
        }

        override fun pending(): List<MailboxMessage> = synchronized(lock) { items.toList() }

        override fun delete(seq: Long): Boolean = synchronized(lock) {
            items.removeAll { it.seq == seq }
        }
    }

    @Test
    fun `sends one delivery to the mailbox with NIP-98 auth`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"v":1,"results":[{"status":"stored","seq":7}]}"""),
        )
        server.start()
        try {
            val keys = Keys.generate()
            val transport = DesktopMailboxTransport(
                server.url("/").toString(),
                keys,
                DesktopNostrSigner(keys),
                FakeRawStore(),
            )
            val result = transport.send(keys.publicKey().toHex(), RelayPayload("id-1", 1_000L, "hi"))
            assertTrue(result is SendResult.Sent)

            val request = server.takeRequest()
            assertEquals("/v1/send", request.path)
            assertTrue(request.getHeader("Authorization")!!.startsWith("Nostr "))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(1, body.getInt("v"))
            val delivery = body.getJSONArray("deliveries").getJSONObject(0)
            assertEquals("id-1", delivery.getString("id"))
            assertEquals(keys.publicKey().toHex(), delivery.getString("to"))
            assertNotNull(delivery.getString("envelope"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `receives a batch, stores raw before ack and opens the payload`() = runBlocking {
        val desktopKeys = Keys.generate()
        val peerKeys = Keys.generate()
        val payload = RelayPayload("in-1", 123_000L, "hello desktop")
        val envelope = DesktopGiftWrap.wrap(peerKeys, desktopKeys.publicKey().toHex(), payload)

        val ackReceived = CompletableDeferred<String>()
        val serverSocket = CompletableDeferred<WebSocket>()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    serverSocket.complete(webSocket)
                    webSocket.send("""{"type":"ready","expiresAt":9999999999999}""")
                    webSocket.send(
                        """{"type":"batch","messages":[{"seq":1,"envelope":${JSONObject.quote(envelope)}}],"more":false,"cursor":1}""",
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("\"type\":\"ack\"")) ackReceived.complete(text)
                }
            }),
        )
        server.start()

        val raw = FakeRawStore()
        val transport = DesktopMailboxTransport(server.url("/").toString(), desktopKeys, DesktopNostrSigner(desktopKeys), raw)
        try {
            transport.start()
            val incoming = withTimeout(15_000) { transport.incoming.first() }
            assertEquals("hello desktop", incoming.payload.body)
            assertEquals(peerKeys.publicKey().toHex(), incoming.senderPubkeyHex)

            val ack = withTimeout(5_000) { ackReceived.await() }
            assertTrue("ack must follow the raw store", ack.contains("\"through\":1"))
            assertEquals(1, raw.pending().size)

            incoming.onHandled?.invoke()
            assertEquals(0, raw.pending().size)
        } finally {
            transport.stop()
            // Close the server-side upgrade explicitly; MockWebServer.shutdown() waits for its
            // dispatcher queue and times out if the WebSocket is still open.
            runCatching { serverSocket.getCompleted().close(1000, "test done") }
            Thread.sleep(300)
            server.shutdown()
        }
    }
}
