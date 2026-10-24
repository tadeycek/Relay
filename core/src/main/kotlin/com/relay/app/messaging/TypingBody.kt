package com.relay.app.messaging

import com.relay.app.groups.GroupProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object TypingBody {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:TYPING|"
    const val TTL = 8000L
    data class Signal(val typing: Boolean, val time: Long, val group: String = "", val owner: String = "", val author: String = "")
    fun format(s: Signal) = PREFIX + JSONObject().put("v", 1).put("typing", s.typing).put("time", s.time)
        .put("group", s.group).put("owner", s.owner).put("author", s.author).toString()
    fun parse(body: String, now: Long): Signal? = runCatching {
        require(body.startsWith(PREFIX) && body.length < 512)
        val o = JSONObject(body.removePrefix(PREFIX)); require(o.getInt("v") == 1)
        val s = Signal(o.getBoolean("typing"), o.getLong("time"), o.getString("group"), o.getString("owner"), o.getString("author"))
        require(s.time > 0 && s.time >= now - TTL && s.time <= now + 2000)
        require((s.group.isEmpty() && s.owner.isEmpty() && s.author.isEmpty()) ||
            (java.util.UUID.fromString(s.group).toString() == s.group && s.owner.matches(Regex("[0-9a-f]{64}")) && s.author.matches(Regex("[0-9a-f]{64}"))))
        s
    }.getOrNull()
}

/** Pure ordering/expiry policy shared by direct and group indicators. */
class TypingPresence {
    data class Entry(val name: String, val expires: Long, val timestamp: Long)
    private val _entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries = _entries.asStateFlow()
    @Synchronized fun update(key: String, name: String, s: TypingBody.Signal, now: Long, ageMs: Long = 0): Boolean {
        val old = _entries.value[key]
        if (old != null && s.time <= old.timestamp) return false
        _entries.value = (_entries.value.filterValues { it.expires > now - 30000 } +
            (key to Entry(name, if (s.typing) now + (TypingBody.TTL - ageMs).coerceIn(0, TypingBody.TTL) else now, s.time)))
            .entries.toList().takeLast(100).associate { it.toPair() }
        return true
    }
    companion object {
        fun names(entries: Map<String, Entry>, contactId: Long?, state: GroupProtocol.State?, now: Long): String? {
            val scoped = if (state != null) entries.filterKeys { it.startsWith("group:${state.owner}:${state.id}:") }
                else entries.filterKeys { it == "contact:${contactId ?: -1}" }
            return scoped.values.filter { it.expires > now }.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() }
        }
    }
}
