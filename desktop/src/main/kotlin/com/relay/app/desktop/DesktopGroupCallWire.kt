package com.relay.app.desktop

import com.relay.app.call.CallLimits
import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.GroupCallCodec
import org.json.JSONObject
import java.util.Base64

/**
 * Framing of a `group_start` / `group_end` announcement as one stored group fan-out message:
 * `TYPE:CALL|J:<base64(version-1 JSON from [GroupCallCodec.encode])>`, sent to each member as an ordinary sealed
 * message (NIP-17 gift wrap; E2E). The same prefix as the 1:1 call codec, so the mailbox keeps routing it away from
 * the chat inbox; the 1:1 codec rejects the group ops as unknown, so the two never mix.
 *
 * The JSON carries the join secret: never log or display a framed body.
 */
object GroupCallWire {
    private val OPS = setOf("group_start", "group_end")
    private const val MAX_BODY = CallLimits.MAX_WIRE_CHARS + 512

    fun frame(a: GroupAnnouncement): String =
        CallLimits.PREFIX + Base64.getEncoder().encodeToString(GroupCallCodec.encode(a).toByteArray(Charsets.UTF_8))

    /** The announcement JSON of a framed body, or null when [body] is not a framed group announcement. */
    fun unframe(body: String): String? {
        if (!body.startsWith(CallLimits.PREFIX) || body.length > MAX_BODY) return null
        val json = try {
            String(Base64.getDecoder().decode(body.substring(CallLimits.PREFIX.length)), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) { return null }
        val op = try { JSONObject(json).opt("op") as? String } catch (_: Exception) { null }
        return if (op in OPS) json else null
    }

    fun isGroupOp(body: String): Boolean = unframe(body) != null
}
