package com.relay.app.groups

import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Each hop is a private authenticated message. Only the creator may announce membership/forward posts. */
object GroupProtocol {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:GROUP|"
    const val MAX_MEMBERS = 17 // creator plus sixteen verified friends
    data class Member(val key: String, val name: String)
    data class State(val id: String, val owner: String, val name: String, val revision: Long, val members: List<Member>)
    data class Packet(val kind: String, val state: State, val event: String = "", val author: String = "", val text: String = "", val time: Long = 0)

    fun eligible(contact: Contact) = contact.qrVerified && !contact.isDeleted &&
        contact.trustLevel != ContactTrustLevel.BLOCKED && contact.publicKey != null &&
        contact.pendingPublicKey == null && contact.nostrPubkey != null
    fun isControl(body: String) = body.startsWith(PREFIX)
    fun memberId(key: String): Long = -(key.take(15).toLong(16) + 1)
    private fun key(value: String) = value.matches(Regex("[0-9a-f]{64}"))
    private fun label(value: String) = value.length in 1..30 && value == value.trim() &&
        value.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
    private fun valid(p: Packet): Boolean {
        val s = p.state
        return runCatching { UUID.fromString(s.id).toString() == s.id }.getOrDefault(false) &&
            key(s.owner) && label(s.name) && s.revision > 0 && s.members.size in 1..MAX_MEMBERS &&
            s.members.all { key(it.key) && label(it.name) } &&
            s.members.map { it.key }.distinct().size == s.members.size && s.members.any { it.key == s.owner } &&
            when (p.kind) {
                "sync" -> p.event.isEmpty() && p.author.isEmpty() && p.text.isEmpty()
                "profile", "profile-submit", "purge", "purge-submit", "delete", "delete-submit", "post", "submit" -> runCatching { UUID.fromString(p.event).toString() == p.event }.getOrDefault(false) &&
                    s.members.any { it.key == p.author } && p.text.isNotBlank() && (!com.relay.app.media.MediaBody.isMedia(p.text) || com.relay.app.media.MediaBody.parse(p.text) != null) && p.text.length <= 4000 && (p.kind !in listOf("delete", "delete-submit") || runCatching { UUID.fromString(p.text).toString() == p.text }.getOrDefault(false)) && (p.kind !in listOf("purge", "purge-submit") || (p.text.toLongOrNull() ?: 0) > 0) && p.time > 0 && (p.kind !in listOf("profile", "profile-submit") || com.relay.app.media.ProfileNameBody.parse(p.text) != null)
                else -> false
            }
    }
    fun format(p: Packet): String {
        require(valid(p))
        val s = p.state
        val result = PREFIX + JSONObject().apply {
            put("v", 1); put("kind", p.kind); put("id", s.id); put("owner", s.owner)
            put("name", s.name); put("revision", s.revision)
            put("members", JSONArray().apply { s.members.forEach { put(JSONObject().put("key", it.key).put("name", it.name)) } })
            put("event", p.event); put("author", p.author); put("text", p.text); put("time", p.time)
        }.toString()
        require(result.toByteArray(Charsets.UTF_8).size <= 10000)
        return result
    }
    fun parse(body: String): Packet? = runCatching {
        require(isControl(body) && body.toByteArray(Charsets.UTF_8).size <= 10000)
        val o = JSONObject(body.removePrefix(PREFIX)); require(o.getInt("v") == 1)
        val a = o.getJSONArray("members"); require(a.length() <= MAX_MEMBERS)
        val p = Packet(o.getString("kind"), State(o.getString("id"), o.getString("owner"), o.getString("name"), o.getLong("revision"),
            (0 until a.length()).map { Member(a.getJSONObject(it).getString("key"), a.getJSONObject(it).getString("name")) }),
            o.getString("event"), o.getString("author"), o.getString("text"), o.getLong("time"))
        require(valid(p)); p
    }.getOrNull()

    /** Submitters cannot supply another author's identity or change the roster. */
    fun acceptSubmit(sender: String, current: State, packet: Packet) = packet.kind in listOf("submit", "delete-submit", "purge-submit") &&
        packet.state.id == current.id && packet.state.owner == current.owner && packet.state.revision <= current.revision && packet.author == sender && current.members.any { it.key == sender } && sender != current.owner
    fun acceptOwner(sender: String, current: State?, packet: Packet) = packet.kind in listOf("sync", "post", "delete", "purge") &&
        sender == packet.state.owner && (current == null || (current.owner == sender && current.id == packet.state.id &&
        (packet.state.revision > current.revision || packet.state == current ||
            (packet.kind in listOf("post", "delete", "purge") && packet.state.revision < current.revision && current.members.any { it.key == packet.author }))))
}
