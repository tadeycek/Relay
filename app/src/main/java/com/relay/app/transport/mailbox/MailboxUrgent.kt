package com.relay.app.transport.mailbox

import org.json.JSONObject

/**
 * `POST /v1/send` with `"urgent": true` on the single delivery: the stored copy of a call invite that
 * makes the server send the high-priority wake (PROTOCOL.md, "urgent on a delivery").
 *
 * Built from [MailboxProtocol.sendBody] so the rest of the body (the frozen legacy shape) still comes
 * from one place; only the flag is added here. Pure so the wire shape is unit-tested.
 */
object MailboxUrgent {

    fun withUrgent(legacyBody: String): String {
        val json = JSONObject(legacyBody)
        val deliveries = json.optJSONArray("deliveries")
            ?: throw IllegalArgumentException("Send body has no deliveries")
        require(deliveries.length() == 1) { "Urgent sends carry exactly one delivery" }
        val delivery = deliveries.getJSONObject(0)
        delivery.put("urgent", true)
        return json.toString()
    }

    /** True when a send body already carries the flag (used to keep a resend from being marked urgent twice). */
    fun isUrgent(body: String): Boolean = try {
        JSONObject(body).optJSONArray("deliveries")?.optJSONObject(0)?.optBoolean("urgent") == true
    } catch (e: Exception) {
        false
    }
}
