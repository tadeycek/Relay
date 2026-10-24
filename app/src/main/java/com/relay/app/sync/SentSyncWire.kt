package com.relay.app.sync

import com.relay.app.linking.LinkedDeviceList
import com.relay.app.media.MediaBody
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Pure helpers for the sync channel's JSON and for choosing what is mirrored. */
object SentSyncWire {

    const val SYNC_TTL_MS = 24L * 60 * 60 * 1000
    /** The server refuses decoded bodies over 12 KiB; mirror its bound so a bad event is skipped, not parsed. */
    const val MAX_EVENT_BYTES = SyncLimits.MAX_EVENT_BYTES

    /** Server event id for [syncId]'s wire body number [index] of [count] (`[0-9a-f]{8,64}`). */
    fun eventId(syncId: String, index: Int, count: Int): String =
        if (count == 1) syncId else sha256Hex("$syncId/$index/$count").take(32)

    /** Deterministic sync id for one payload: a retry, a restart or a re-stage all name the same event. */
    fun syncIdFor(payloadId: String): String = sha256Hex("relay-sent:$payloadId").take(32)

    /** `POST /v1/sync` body: a whole wire body as a single server part. */
    fun envelope(wire: String, id: String, rev: Long, now: Long): String {
        val bytes = wire.toByteArray(Charsets.UTF_8)
        return JSONObject()
            .put("v", 1)
            .put("id", id)
            .put("rev", rev)
            .put("part", 0)
            .put("parts", 1)
            .put("total", bytes.size)
            .put("hash", sha256Hex(bytes))
            .put("expires", now + SYNC_TTL_MS)
            .put("data", Base64.getEncoder().encodeToString(bytes))
            .toString()
    }

    fun postOutcome(status: Int, errorCode: String?): PostResult = when {
        status in 200..299 -> PostResult.Ok
        status == 0 -> PostResult.Retry("unreachable")
        status == 409 && errorCode == "sync_limit" -> PostResult.Retry("sync_limit")
        status == 429 || status >= 500 -> PostResult.Retry("http $status")
        // 400 malformed, 401/403 refused or revoked, 404 route missing on an old server, 409 conflict, 413 too large.
        else -> PostResult.Permanent("http $status ${errorCode ?: ""}".trim())
    }

    fun linkedCount(status: Int, body: String): DevicesResult {
        if (status !in 200..299) return DevicesResult.Failed(retryable = status == 0 || status == 429 || status >= 500)
        val parsed = LinkedDeviceList.parse(body).getOrNull() ?: return DevicesResult.Failed()
        return DevicesResult.Ok(parsed.size)
    }

    /** Parses `GET /v1/sync`. A reply that is not exactly the documented shape fails as a whole. */
    fun parseList(status: Int, body: String): ListResult {
        if (status !in 200..299) return ListResult.Failed
        return try {
            val root = JSONObject(body)
            if (root.getInt("v") != 1) return ListResult.Failed
            val array = root.getJSONArray("events")
            val events = buildList {
                for (i in 0 until minOf(array.length(), SyncLimits.MAX_PAGE_EVENTS)) {
                    val o = array.getJSONObject(i)
                    val data = Base64.getDecoder().decode(o.getString("data"))
                    add(SyncEvent(o.getLong("cursor"), o.getString("id"), o.getLong("rev"), o.getInt("total"), o.getString("hash"), data))
                }
            }
            ListResult.Ok(events, root.optBoolean("more", false))
        } catch (e: Exception) {
            ListResult.Failed
        }
    }

    fun ackBody(through: Long): String = JSONObject().put("v", 1).put("through", through).toString()

    /**
     * Whether a body queued for a contact is a message the user wrote (text, reply or attachment), as
     * opposed to a handshake, receipt, profile, group control, reaction, deletion or location packet.
     * Everything that is not plain text carries a `TYPE:` prefix, so the allow-list is small.
     */
    fun mirrored(plainBody: String): Boolean =
        !plainBody.startsWith("TYPE:") || MediaBody.isMedia(plainBody) || plainBody.startsWith("TYPE:REPLY|")

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The `RelayPayload` JSON carried in `P:`: same id and timestamp as the real send, body sealed to ourselves. */
    fun selfSealedPayload(payloadId: String, ts: Long, plainBody: String, sealToSelf: (String) -> String?): String? {
        val sealed = sealToSelf(plainBody) ?: return null
        return PayloadCodec.encode(RelayPayload(id = payloadId, ts = ts, body = sealed))
    }
}

object SyncLimits {
    const val MAX_EVENT_BYTES = 12 * 1024
    const val MAX_PAGE_EVENTS = 8
    const val MAX_PAGES_PER_POLL = 8
}
