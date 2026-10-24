package com.relay.app.calls

import com.relay.app.call.CallOutcome
import org.json.JSONObject
import java.util.Base64

/** One locally written call event (never sent): what the thread shows after a call. */
data class CallLogEntry(
    val callId: String,
    val incoming: Boolean,
    val outcome: CallOutcome,
    val reason: String?,
    val durationMs: Long,
    val atMs: Long,
)

/**
 * The conversation row a call leaves behind (WP13: "a missed-call record in the thread").
 *
 * Local only, like the existing conversation events: the body carries a `TYPE:CALLLOG` marker so the
 * timeline can render it as a centred system note instead of a text bubble, and it is never handed to
 * the outbox. The `msgId` is derived from the call id, so the same call is recorded at most once.
 */
object CallRecord {

    const val PREFIX = "TYPE:CALLLOG|J:"
    /** DB-only origin marker: incoming network messages are always inserted with DeliveryState.NONE. */
    const val LOCAL_DELIVERY_STATE = -13

    private val CALL_ID = Regex("^[0-9a-f]{32}$")
    private val REASON = Regex("^[a-z0-9_]{1,32}$")

    fun isCallLog(body: String): Boolean = body.startsWith(PREFIX)

    fun isLocalRow(message: com.relay.app.data.model.Message): Boolean =
        message.type == com.relay.app.data.model.MessageType.TEXT &&
            message.deliveryState == LOCAL_DELIVERY_STATE &&
            message.msgId == decode(message.body)?.let { messageId(it.callId) }

    fun messageId(callId: String): String = "call:$callId"

    fun encode(
        callId: String,
        incoming: Boolean,
        outcome: CallOutcome,
        reason: String?,
        durationMs: Long,
        atMs: Long,
    ): String {
        require(CALL_ID.matches(callId)) { "call id must be 32 lowercase hex" }
        require(reason == null || REASON.matches(reason)) { "reason must be a short lowercase token" }
        require(durationMs >= 0) { "duration cannot be negative" }
        val json = JSONObject()
            .put("v", 1)
            .put("call", callId)
            .put("in", incoming)
            .put("out", outcome.name)
            .put("dur", durationMs)
            .put("at", atMs)
            .also { if (reason != null) it.put("reason", reason) }
        return PREFIX + Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Null for anything that is not a version-1 call log of this app (never throws). */
    fun decode(body: String): CallLogEntry? = try {
        if (!isCallLog(body)) null else {
            val json = JSONObject(String(Base64.getDecoder().decode(body.removePrefix(PREFIX)), Charsets.UTF_8))
            val call = json.optString("call")
            val outcome = CallOutcome.entries.firstOrNull { it.name == json.optString("out") }
            val reason = json.optString("reason").takeIf { it.isNotEmpty() }?.takeIf { REASON.matches(it) }
            val duration = json.optLong("dur", -1)
            if (json.optInt("v") != 1 || !CALL_ID.matches(call) || outcome == null || duration < 0) null
            else CallLogEntry(call, json.optBoolean("in"), outcome, reason, duration, json.optLong("at", 0))
        }
    } catch (e: Exception) {
        null
    }

    /** The sentence shown in the thread. [contactName] is the peer's display name. */
    fun label(entry: CallLogEntry, contactName: String): String = when (entry.outcome) {
        CallOutcome.MISSED -> "Missed call"
        CallOutcome.DECLINED -> if (entry.incoming) "You declined the call" else "$contactName declined your call"
        CallOutcome.BUSY -> if (entry.incoming) "You were on another call" else "$contactName was busy"
        CallOutcome.NO_ANSWER -> "No answer"
        CallOutcome.CANCELLED -> "Call cancelled"
        CallOutcome.FAILED -> "Call failed"
        CallOutcome.COMPLETED ->
            if (entry.durationMs > 0) "Call ended · ${CallUiModelFactory.duration(entry.durationMs)}" else "Call ended"
        CallOutcome.ANSWERED_ELSEWHERE -> "Answered on another device"
        CallOutcome.GLARE_LOST, CallOutcome.LOST_RACE -> "Call ended"
    }
}
