package com.relay.app.messaging

import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import org.json.JSONObject

/** A quoted snapshot, not a claim that the recipient still has the original message. */
object ReplyBody {
    private const val PREFIX = "TYPE:REPLY|"
    data class Reply(val name: String, val quote: String, val reference: String, val text: String)
    fun format(reply: Reply): String {
        require(reply.name.length in 1..30 && reply.quote.length <= 120 && reply.reference.length <= 64 && reply.text.length <= 8000)
        return PREFIX + JSONObject().put("v", 1).put("name", reply.name).put("quote", reply.quote)
            .put("ref", reply.reference).put("text", reply.text).toString()
    }
    fun parse(body: String): Reply? = runCatching {
        require(body.startsWith(PREFIX) && body.length <= 14000)
        val o = JSONObject(body.removePrefix(PREFIX)); require(o.getInt("v") == 1)
        val reply = Reply(o.getString("name"), o.getString("quote"), o.getString("ref"), o.getString("text"))
        format(reply); reply
    }.getOrNull()
    fun preview(body: String) = parse(body)?.text ?: body
    fun summary(message: Message) = when (message.type) {
        MessageType.IMAGE -> "Photo"
        MessageType.VIDEO -> "Video"
        MessageType.LOCATION -> "Location"
        else -> preview(message.body).take(120)
    }
    fun draft(message: Message, name: String) = format(Reply(name.take(30).ifBlank { "Someone" }, summary(message), message.msgId ?: message.id.toString(), ""))
    fun withText(draft: String?, text: String) = draft?.let { parse(it) }?.let { format(it.copy(text = text)) } ?: text
}
