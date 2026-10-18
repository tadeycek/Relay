package com.relay.app.transport.mailbox

import com.relay.app.transport.HybridStatus
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.mailbox.MailboxProtocol.Frame
import com.relay.app.transport.mailbox.MailboxProtocol.Outcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxProtocolTest {

    @Test
    fun parsesServerFrames() {
        assertEquals(Frame.Ready(99), MailboxProtocol.parseFrame("""{"type":"ready","v":1,"instance":"x","expiresAt":99}"""))
        assertEquals(
            Frame.Batch(listOf(MailboxMessage(3, "w3"), MailboxMessage(4, "w4")), more = true, cursor = 4),
            MailboxProtocol.parseFrame("""{"type":"batch","messages":[{"seq":3,"from":"a","id":"i","envelope":"w3","storedAt":1},{"seq":4,"envelope":"w4"}],"more":true,"cursor":4}"""),
        )
        assertEquals(Frame.Message(MailboxMessage(7, "w7")), MailboxProtocol.parseFrame("""{"type":"message","seq":7,"from":"a","id":"i","envelope":"w7"}"""))
        assertEquals(Frame.Acked(7), MailboxProtocol.parseFrame("""{"type":"acked","acked":2,"through":7}"""))
        assertEquals(Frame.Error("storage_error", true), MailboxProtocol.parseFrame("""{"type":"error","error":{"code":"storage_error","retryable":true}}"""))
    }

    @Test
    fun ignoresWhatItDoesNotUnderstand() {
        assertEquals(Frame.Other, MailboxProtocol.parseFrame("pong"))
        assertEquals(Frame.Other, MailboxProtocol.parseFrame("""{"type":"something-new"}"""))
        // A message without a usable seq or envelope is skipped, never stored.
        assertEquals(Frame.Other, MailboxProtocol.parseFrame("""{"type":"message","seq":0,"envelope":"w"}"""))
        assertEquals(Frame.Batch(emptyList(), false, 0), MailboxProtocol.parseFrame("""{"type":"batch","messages":[{"seq":5}],"more":false,"cursor":0}"""))
    }

    @Test
    fun buildsRequests() {
        // Compared as parsed JSON: key order isn't part of the protocol.
        JSONObject(MailboxProtocol.syncFrame(12)).let { assertEquals("sync", it.getString("type")); assertEquals(12L, it.getLong("after")) }
        JSONObject(MailboxProtocol.ackFrame(12)).let { assertEquals("ack", it.getString("type")); assertEquals(12L, it.getLong("through")) }
        val body = JSONObject(MailboxProtocol.sendBody("ab".repeat(32), "id-1", "wrap"))
        assertEquals(1, body.getInt("v"))
        val d = body.getJSONArray("deliveries").getJSONObject(0)
        assertEquals("ab".repeat(32), d.getString("to"))
        assertEquals("id-1", d.getString("id"))
        assertEquals("wrap", d.getString("envelope"))
    }

    @Test
    fun validatesIdsTheWayTheServerDoes() {
        assertTrue(MailboxProtocol.isDeliveryId("3f2b6c1e-9a7d-4c5b-8e2f-1a2b3c4d5e6f"))
        assertFalse(MailboxProtocol.isDeliveryId("has space"))
        assertFalse(MailboxProtocol.isDeliveryId("x".repeat(65)))
        assertTrue(MailboxProtocol.isRecipient("0f".repeat(32)))
        assertFalse(MailboxProtocol.isRecipient("0F".repeat(32)))
    }

    private fun result(status: String, code: String? = null, retryable: Boolean = false): String {
        val r = JSONObject().put("to", "t").put("id", "i").put("status", status)
        if (code != null) r.put("error", JSONObject().put("code", code).put("retryable", retryable)) else r.put("seq", 5)
        return JSONObject().put("v", 1).put("results", org.json.JSONArray().put(r)).toString()
    }

    @Test
    fun mapsSendReplies() {
        assertEquals(Outcome.Stored, MailboxProtocol.sendOutcome(200, result("stored")))
        assertEquals(Outcome.Stored, MailboxProtocol.sendOutcome(200, result("duplicate")))
        // An earlier attempt of the same message is already waiting there with other bytes.
        assertEquals(Outcome.Stored, MailboxProtocol.sendOutcome(200, result("rejected", "id_conflict")))
        assertEquals(Outcome.NotOnMailbox, MailboxProtocol.sendOutcome(200, result("rejected", "unknown_recipient")))
        assertTrue(MailboxProtocol.sendOutcome(200, result("rejected", "recipient_full", retryable = true)) is Outcome.Retry)
        assertTrue(MailboxProtocol.sendOutcome(200, result("rejected", "storage_error", retryable = true)) is Outcome.Retry)
        assertTrue(MailboxProtocol.sendOutcome(200, result("rejected", "envelope_too_large")) is Outcome.Rejected)
    }

    @Test
    fun mapsRequestLevelFailures() {
        // This install isn't on the pilot allowlist: use public relays instead.
        assertEquals(Outcome.NotOnMailbox, MailboxProtocol.sendOutcome(403, """{"v":1,"error":{"code":"not_allowed","retryable":false}}"""))
        assertTrue(MailboxProtocol.sendOutcome(401, """{"v":1,"error":{"code":"auth_expired","retryable":false}}""") is Outcome.Retry)
        assertTrue(MailboxProtocol.sendOutcome(503, """{"v":1,"error":{"code":"storage_error","retryable":true}}""") is Outcome.Retry)
        assertTrue(MailboxProtocol.sendOutcome(502, "<html>bad gateway</html>") is Outcome.Retry)
        assertTrue(MailboxProtocol.sendOutcome(200, "not json") is Outcome.Retry)
    }

    @Test
    fun eitherRouteBeingOnlineMeansOnline() {
        val s = TransportStatus.entries
        assertEquals(TransportStatus.ONLINE, HybridStatus.combine(TransportStatus.OFFLINE, TransportStatus.ONLINE))
        assertEquals(TransportStatus.ONLINE, HybridStatus.combine(TransportStatus.ONLINE, TransportStatus.STOPPED))
        assertEquals(TransportStatus.CONNECTING, HybridStatus.combine(TransportStatus.OFFLINE, TransportStatus.CONNECTING))
        assertEquals(TransportStatus.OFFLINE, HybridStatus.combine(TransportStatus.OFFLINE, TransportStatus.STOPPED))
        // With the mailbox switched off, the hybrid reports exactly what Nostr reports.
        for (nostr in s) if (nostr != TransportStatus.WAITING_FOR_TOR) assertEquals(nostr, HybridStatus.combine(nostr, TransportStatus.STOPPED))
    }
}
