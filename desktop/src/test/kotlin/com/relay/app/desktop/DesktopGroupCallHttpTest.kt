package com.relay.app.desktop

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import rust.nostr.sdk.Keys
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** WP16: the signed HTTP/WebSocket surface of the roster-bound CallRoom (G5 contract), against a mock server. */
class DesktopGroupCallHttpTest {
    private val server = MockWebServer().apply { start() }
    private val keys = Keys.generate()
    private val api = DesktopGroupCallHttp(server.url("/").toString(), DesktopNostrSigner(keys), "desk-1")
    @After fun stop() { runCatching { server.shutdown() } }

    private val room = "0123456789abcdef0123456789abcdef"
    private val hash = "a".repeat(64)
    private val req = CreateRoomRequest(room, hash, "123e4567-e89b-42d3-a456-426614174000", 3, "b".repeat(64), listOf("a".repeat(64), "b".repeat(64)))

    private fun json(code: Int, body: String, rostered: Boolean? = null) =
        MockResponse().setResponseCode(code).setHeader("content-type", "application/json").also { r -> rostered?.let { r.setHeader("x-relay-call-rostered", if (it) "1" else "0") } }.setBody(body)

    private fun authEvent(header: String?): JSONObject {
        assertNotNull("NIP-98 header", header); assertTrue(header!!.startsWith("Nostr "))
        return JSONObject(String(Base64.getDecoder().decode(header.removePrefix("Nostr "))))
    }
    private fun tag(event: JSONObject, name: String): String? {
        val tags = event.getJSONArray("tags")
        for (i in 0 until tags.length()) if (tags.getJSONArray(i).getString(0) == name) return tags.getJSONArray(i).getString(1)
        return null
    }

    @Test fun createIsSignedWithTheDeviceQueryAndCarriesTheRosterBoundBody() {
        server.enqueue(json(200, """{"v":1,"created":true,"rostered":true}""", true))
        assertEquals(CreateRoomOutcome.Created, api.create(req))
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertEquals("/v1/call/rooms?device=desk-1", r.path)
        val body = JSONObject(r.body.readUtf8())
        assertEquals(setOf("v", "room", "secretHash", "group", "revision", "rosterHash", "members"), body.keys().asSequence().toSet())
        assertEquals(3, body.getInt("revision")); assertEquals(2, body.getJSONArray("members").length())
        val ev = authEvent(r.getHeader("Authorization"))
        assertEquals(keys.publicKey().toHex(), ev.getString("pubkey"))
        assertEquals("POST", tag(ev, "method")); assertTrue(tag(ev, "u")!!.endsWith("/v1/call/rooms?device=desk-1"))
        assertEquals(64, tag(ev, "payload")!!.length)
    }

    @Test fun aCreateAnsweredWithoutTheRosterFlagIsNeverTreatedAsRostered() {
        server.enqueue(json(200, """{"v":1,"created":true}""", false))
        val out = api.create(req)
        assertTrue("a legacy room must be refused, not accepted: $out", out is CreateRoomOutcome.Refused)
    }

    @Test fun everyDocumentedCreateRefusalIsMapped() {
        server.enqueue(json(409, """{"v":1,"error":{"code":"group_room_exists","retryable":false},"room":"$room"}"""))
        assertEquals(CreateRoomOutcome.GroupRoomExists(room), api.create(req))
        server.enqueue(json(409, """{"v":1,"error":{"code":"group_room_exists","retryable":true},"room":"nonsense"}"""))
        assertEquals(CreateRoomOutcome.GroupRoomExists(null), api.create(req))
        server.enqueue(json(409, """{"v":1,"error":{"code":"stale_revision","retryable":false}}"""))
        assertEquals(CreateRoomOutcome.StaleRevision, api.create(req))
        server.enqueue(json(403, """{"v":1,"error":{"code":"not_in_roster","retryable":false}}"""))
        assertEquals(CreateRoomOutcome.NotInRoster, api.create(req))
        server.enqueue(json(400, """{"v":1,"error":{"code":"roster_required","retryable":false}}"""))
        assertEquals(CreateRoomOutcome.Refused(400, "roster_required", false), api.create(req))
        server.enqueue(json(409, """{"v":1,"error":{"code":"room_exists","retryable":false}}"""))
        assertEquals(CreateRoomOutcome.Refused(409, "room_exists", false), api.create(req))
        server.enqueue(json(503, """{"v":1,"error":{"code":"unavailable","retryable":true}}"""))
        assertEquals(CreateRoomOutcome.Refused(503, "unavailable", true), api.create(req))
        server.shutdown()
        assertEquals(CreateRoomOutcome.Unreachable, api.create(req))
    }

    @Test fun infoReportsTheRosterBindingGoneAndUnknown() {
        server.enqueue(json(200, """{"v":1,"room":"$room","participants":2,"createdAt":1,"rostered":true,"revision":3,"rosterHash":"${"b".repeat(64)}"}"""))
        assertEquals(RoomInfoOutcome.Live(true, 3, "b".repeat(64), 2), api.info(room))
        assertEquals("/v1/call/rooms/$room?device=desk-1", server.takeRequest().path)
        server.enqueue(json(404, """{"v":1,"error":{"code":"not_found","retryable":false}}"""))
        assertEquals(RoomInfoOutcome.Gone, api.info(room))
        server.enqueue(json(500, "{}"))
        assertEquals(RoomInfoOutcome.Unknown, api.info(room))
        server.enqueue(json(200, "not json"))
        assertEquals(RoomInfoOutcome.Unknown, api.info(room))
    }

    @Test fun endIsSentWithNoBodyAndNoPayloadTag() {
        server.enqueue(json(200, """{"v":1,"ended":true}"""))
        assertTrue(api.end(room))
        val r = server.takeRequest()
        assertEquals("/v1/call/rooms/$room/end?device=desk-1", r.path); assertEquals(0L, r.bodySize)
        assertNull("end is signed with a null payload", tag(authEvent(r.getHeader("Authorization")), "payload"))
        server.enqueue(json(403, """{"v":1,"error":{"code":"forbidden","retryable":false}}"""))
        assertTrue(!api.end(room))
    }

    @Test fun rosterRemovalAndRotationBodies() {
        server.enqueue(json(200, """{"v":1,"removed":2}"""))
        assertTrue(api.removeMembers(room, listOf("d".repeat(64), "c".repeat(64), "c".repeat(64)), "e".repeat(64)))
        val removal = server.takeRequest()
        assertEquals("/v1/call/rooms/$room/roster?device=desk-1", removal.path)
        val body = JSONObject(removal.body.readUtf8())
        assertEquals(listOf("c".repeat(64), "d".repeat(64)), (0 until 2).map { body.getJSONArray("remove").getString(it) }) // ascending, unique
        assertEquals("e".repeat(64), body.getString("secretHash"))
        server.enqueue(json(200, """{"v":1,"rotated":true}"""))
        assertTrue(api.rotate(room, "f".repeat(64)))
        assertEquals("/v1/call/rooms/$room/rotate?device=desk-1", server.takeRequest().path)
        server.enqueue(json(400, "{}"))
        assertTrue(!api.removeMembers(room, listOf("c".repeat(64)), null))
    }

    @Test fun theSocketUpgradeCarriesSecretDevicePidAndASignedHeader() {
        val frames = LinkedBlockingQueue<String>(); val closed = LinkedBlockingQueue<Int>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type":"welcome","v":1,"you":"0000000000000009","participants":[]}""") }
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text); webSocket.close(4009, "removed") }
        }))
        val inbound = LinkedBlockingQueue<String>()
        val socket = api.connect(room, "9".repeat(64), "0000000000000009", object : RoomListener {
            override fun onOpen() { inbound.add("open") }
            override fun onText(text: String) { inbound.add(text) }
            override fun onClosed(code: Int, reason: String) { closed.add(code) }
            override fun onFailure(httpStatus: Int?, code: String?) { inbound.add("fail") }
        })
        val r = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/call/rooms/$room/ws?secret=${"9".repeat(64)}&device=desk-1&pid=0000000000000009", r.path)
        assertEquals("GET", tag(authEvent(r.getHeader("Authorization")), "method"))
        assertEquals("open", inbound.poll(10, TimeUnit.SECONDS)); assertTrue(inbound.poll(10, TimeUnit.SECONDS)!!.contains("welcome"))
        assertTrue(socket.send("""{"type":"heartbeat"}"""))
        assertEquals("""{"type":"heartbeat"}""", frames.poll(10, TimeUnit.SECONDS))
        assertEquals("the server's 4009 close reaches the controller with its code", 4009, closed.poll(10, TimeUnit.SECONDS))
    }

    @Test fun aRefusedUpgradeReportsTheStatusAndErrorCode() {
        server.enqueue(json(403, """{"v":1,"error":{"code":"not_in_roster","retryable":false}}"""))
        val out = LinkedBlockingQueue<Pair<Int?, String?>>()
        api.connect(room, "9".repeat(64), null, object : RoomListener {
            override fun onOpen() {}; override fun onText(text: String) {}; override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(httpStatus: Int?, code: String?) { out.add(httpStatus to code) }
        })
        assertEquals(403 to "not_in_roster", out.poll(10, TimeUnit.SECONDS))
        assertTrue("no pid on a first join", !server.takeRequest().path!!.contains("pid="))
    }

    @Test fun sfuOperationsUseTheWorkerProxyWithTheRightVerbAndSurfaceErrors() {
        server.enqueue(json(200, """{"v":1,"sessionId":"s1"}"""))
        val sfu = api.sfu(room)
        assertEquals("s1", sfu.call("sessions-new", JSONObject()).getString("sessionId"))
        val r1 = server.takeRequest()
        assertEquals("POST", r1.method); assertEquals("/v1/call/rooms/$room/sfu/sessions-new?device=desk-1", r1.path)
        assertEquals(1, JSONObject(r1.body.readUtf8()).getInt("v"))
        server.enqueue(json(200, """{"v":1}""")); sfu.call("renegotiate", JSONObject().put("answer", "x"))
        assertEquals("PUT", server.takeRequest().method)
        server.enqueue(json(200, """{"v":1}""")); sfu.call("tracks-close", JSONObject().put("mids", JSONObject.NULL))
        assertEquals("PUT", server.takeRequest().method)
        server.enqueue(json(502, """{"v":1,"error":{"code":"upstream_failed","retryable":true}}"""))
        try { sfu.call("tracks-new", JSONObject()); fail() } catch (e: SfuException) { assertEquals(502, e.status); assertEquals("upstream_failed", e.code) }
        try { sfu.call("https://evil.example/x", JSONObject()); fail() } catch (_: IllegalArgumentException) {}
        assertEquals("a refused op never reached the network", 4, server.requestCount)
    }
}
