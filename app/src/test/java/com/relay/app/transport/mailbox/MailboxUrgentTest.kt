package com.relay.app.transport.mailbox

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The urgent wake flag on the stored copy of a call invite (PROTOCOL.md, WP11). */
class MailboxUrgentTest {

    private val recipient = "ab".repeat(32)
    private val legacy = MailboxProtocol.sendBody(recipient, "call-1", "wrap")

    @Test fun theLegacyBodyIsPreservedAndOnlyTheFlagIsAdded() {
        val body = MailboxUrgent.withUrgent(legacy)
        val json = JSONObject(body)
        val delivery = json.getJSONArray("deliveries").getJSONObject(0)
        assertEquals(1, json.getInt("v"))
        assertEquals(recipient, delivery.getString("to"))
        assertEquals("call-1", delivery.getString("id"))
        assertEquals("wrap", delivery.getString("envelope"))
        assertTrue(delivery.getBoolean("urgent"))
        assertTrue(MailboxUrgent.isUrgent(body))
        assertFalse(MailboxUrgent.isUrgent(legacy))
    }

    @Test fun anOrdinaryBodyWithoutDeliveriesIsRefusedRatherThanSentHalfFormed() {
        assertTrue(runCatching { MailboxUrgent.withUrgent("""{"v":1}""") }.isFailure)
        assertTrue(runCatching { MailboxUrgent.withUrgent("""{"v":1,"deliveries":[]}""") }.isFailure)
        assertFalse(MailboxUrgent.isUrgent("not json"))
    }

    @Test fun twoDeliveriesAreRefusedBecauseTheServerAllowsOneUrgentDeliveryPerRequest() {
        val two = JSONObject(legacy)
        val deliveries = two.getJSONArray("deliveries")
        deliveries.put(JSONObject(deliveries.getJSONObject(0).toString()).put("id", "call-2"))
        assertTrue(runCatching { MailboxUrgent.withUrgent(two.toString()) }.isFailure)
    }
}
