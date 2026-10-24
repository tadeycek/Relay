package com.relay.app.messaging

import org.json.JSONObject
import java.util.UUID

/** Separate authenticated controls, never parsed from ordinary visible text. */
object ConversationEventBody {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:CHAT_EVENT|"
    val EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
    data class Event(val kind: String, val id: String, val target: String = "", val emoji: String = "", val revision: Long = 0, val targets: List<String> = emptyList())
    private fun uuid(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    private fun valid(e: Event) = uuid(e.id) && when (e.kind) {
        "screenshot" -> e.target.isEmpty() && e.emoji.isEmpty() && e.revision == 0L && e.targets.size<=8 && e.targets.distinct().size==e.targets.size && e.targets.all(::uuid)
        "media_saved" -> uuid(e.target) && e.emoji.isEmpty() && e.revision==0L && e.targets.isEmpty()
        "reaction" -> e.targets.isEmpty() && uuid(e.target) && (e.emoji.isEmpty() || e.emoji in EMOJI) && e.revision > 0
        else -> false
    }
    fun format(e: Event): String {
        require(valid(e))
        return PREFIX + JSONObject().put("kind",e.kind).put("id",e.id).put("target",e.target).put("emoji",e.emoji).put("revision",e.revision).put("targets", org.json.JSONArray(e.targets))
    }
    fun parse(body: String): Event? = runCatching {
        require(body.startsWith(PREFIX) && body.length <= 1024)
        val o = JSONObject(body.removePrefix(PREFIX))
        Event(o.getString("kind"),o.getString("id"),o.getString("target"),o.getString("emoji"),o.getLong("revision"),o.optJSONArray("targets")?.let { a -> require(a.length()<=8); (0 until a.length()).map { a.getString(it) } }.orEmpty()).also { require(valid(it)) }
    }.getOrNull()
}
