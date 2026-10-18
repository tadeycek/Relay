package com.relay.app.transport.mailbox

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Wire format of the Relay mailbox (server/cloudflare/PROTOCOL.md, v1): frames in, requests out,
 * and how a send reply maps to an outcome. Pure (org.json only), so unit-tested.
 */
object MailboxProtocol {

    const val VERSION = 1
    private val PUBKEY = Regex("^[0-9a-f]{64}$")
    private val DELIVERY_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isRecipient(hex: String) = PUBKEY.matches(hex)
    fun isDeliveryId(id: String) = DELIVERY_ID.matches(id)

    sealed interface Frame {
        data class Ready(val expiresAt: Long) : Frame
        data class Batch(val messages: List<MailboxMessage>, val more: Boolean, val cursor: Long) : Frame
        data class Message(val message: MailboxMessage) : Frame
        data class Acked(val through: Long) : Frame
        data class Error(val code: String, val retryable: Boolean) : Frame
        /** A typing or presence signal passed straight through; nothing about it was stored. */
        data class Signal(val envelope: String) : Frame
        data object Other : Frame
    }

    fun parseFrame(text: String): Frame = try {
        val o = JSONObject(text)
        when (o.optString("type")) {
            "ready" -> Frame.Ready(o.optLong("expiresAt"))
            "batch" -> Frame.Batch(messages(o.getJSONArray("messages")), o.getBoolean("more"), o.getLong("cursor"))
            "message" -> message(o)?.let { Frame.Message(it) } ?: Frame.Other
            "acked" -> Frame.Acked(o.getLong("through"))
            "error" -> o.getJSONObject("error").let { Frame.Error(it.optString("code"), it.optBoolean("retryable")) }
            "signal" -> o.optString("envelope").takeIf { it.isNotEmpty() }?.let { Frame.Signal(it) } ?: Frame.Other
            else -> Frame.Other
        }
    } catch (e: JSONException) {
        Frame.Other // "pong" and anything this version doesn't know
    }

    private fun messages(array: JSONArray): List<MailboxMessage> =
        (0 until array.length()).mapNotNull { message(array.getJSONObject(it)) }

    private fun message(o: JSONObject): MailboxMessage? {
        val seq = o.optLong("seq", -1)
        val envelope = o.optString("envelope")
        return if (seq > 0 && envelope.isNotEmpty()) MailboxMessage(seq, envelope) else null
    }

    fun syncFrame(after: Long): String = JSONObject().put("type", "sync").put("after", after).toString()

    fun ackFrame(through: Long): String = JSONObject().put("type", "ack").put("through", through).toString()

    fun signalFrame(recipient: String, envelope: String): String =
        JSONObject().put("type", "signal").put("to", recipient).put("envelope", envelope).toString()

    fun sendBody(recipient: String, id: String, envelope: String): String = JSONObject()
        .put("v", VERSION)
        .put("deliveries", JSONArray().put(JSONObject().put("to", recipient).put("id", id).put("envelope", envelope)))
        .toString()

    /** What happened to one message handed to the mailbox. */
    sealed interface Outcome {
        /** Stored (or already stored) on the server: the recipient will get it. */
        data object Stored : Outcome
        /** The recipient (or this install) is not part of the mailbox pilot: use another transport. */
        data object NotOnMailbox : Outcome
        data class Retry(val reason: String) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    /** Maps an HTTP reply to `POST /v1/send` carrying exactly one delivery. */
    fun sendOutcome(httpStatus: Int, body: String): Outcome {
        val json = try { JSONObject(body) } catch (e: JSONException) { null }
        val error = json?.optJSONObject("error")
        return when {
            httpStatus == 200 -> {
                val result = json?.optJSONArray("results")?.optJSONObject(0) ?: return Outcome.Retry("Malformed mailbox reply")
                when (result.optString("status")) {
                    "stored", "duplicate" -> Outcome.Stored
                    "rejected" -> rejection(result.optJSONObject("error"))
                    else -> Outcome.Retry("Unexpected mailbox status")
                }
            }
            // This install isn't on the pilot allowlist; a request the server doesn't understand means
            // a protocol mismatch. Either way the message should travel another way, not be retried here.
            httpStatus == 403 || httpStatus == 400 -> Outcome.NotOnMailbox
            httpStatus == 413 -> Outcome.Rejected("Message too large for the mailbox")
            httpStatus == 401 -> Outcome.Retry("Mailbox refused the signature (${error?.optString("code") ?: "auth"}); check the phone's clock")
            else -> Outcome.Retry("Mailbox unavailable ($httpStatus)")
        }
    }

    private fun rejection(error: JSONObject?): Outcome {
        val code = error?.optString("code").orEmpty()
        return when (code) {
            "unknown_recipient" -> Outcome.NotOnMailbox
            // Same id, other bytes: an earlier attempt of this very message is already waiting there
            // (e.g. its stored packet was lost with an app reinstall). The recipient will get that copy.
            "id_conflict" -> Outcome.Stored
            "envelope_too_large" -> Outcome.Rejected("Message too large for the mailbox")
            else -> if (error?.optBoolean("retryable") == true || code.isEmpty()) Outcome.Retry("Mailbox busy ($code)")
                else Outcome.Rejected("Mailbox refused the message ($code)")
        }
    }
}
