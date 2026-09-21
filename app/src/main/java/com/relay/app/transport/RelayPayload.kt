package com.relay.app.transport

import org.json.JSONException
import org.json.JSONObject

/**
 * Transport-neutral message envelope carried inside the encrypted rumor of a NIP-17 message.
 *
 * [body] is the same string the SMS-era protocol used (plain text, or a `TYPE:LOCATION|...` /
 * `TYPE:READ_RECEIPT|...` string parsed by SmsMessageParser). Keeping it means every existing
 * parser and UI path keeps working unchanged; the envelope only adds what SMS could not provide:
 * a stable [id] (idempotency / dedupe / receipts) and the sender's own timestamp [ts].
 *
 * Wrapper timestamps on Nostr gift wraps are deliberately fuzzed by up to two days, so [ts] is the
 * only usable ordering signal — never sort or filter on the wrap's `created_at`.
 */
data class RelayPayload(
    val id: String,
    val ts: Long,
    val body: String,
    val v: Int = PayloadCodec.CURRENT_VERSION,
)

object PayloadCodec {

    const val CURRENT_VERSION = 1
    const val MAX_ID_LENGTH = 64
    /** Well under typical relay event-size limits (commonly 64 KB) once wrapped and encrypted. */
    const val MAX_BODY_LENGTH = 16 * 1024

    fun encode(payload: RelayPayload): String = JSONObject().apply {
        put("v", payload.v)
        put("id", payload.id)
        put("ts", payload.ts)
        put("body", payload.body)
    }.toString()

    /** Returns null for anything that is not a well-formed payload of a supported version. */
    fun decode(json: String): RelayPayload? {
        return try {
            val o = JSONObject(json)
            val v = o.getInt("v")
            if (v < 1 || v > CURRENT_VERSION) return null
            val id = o.getString("id")
            if (id.isEmpty() || id.length > MAX_ID_LENGTH) return null
            val body = o.getString("body")
            if (body.length > MAX_BODY_LENGTH) return null
            RelayPayload(id = id, ts = o.getLong("ts"), body = body, v = v)
        } catch (e: JSONException) {
            null
        }
    }

    /** Fresh random id for a new outgoing message. */
    fun newId(): String = java.util.UUID.randomUUID().toString()
}
