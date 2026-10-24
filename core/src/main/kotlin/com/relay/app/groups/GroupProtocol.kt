package com.relay.app.groups

import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.transport.nostr.InboxRelays
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Each hop is a private authenticated message. Only the creator may change the roster; everything else
 * travels straight from its author to every member and is judged against the receiver's own roster. */
object GroupProtocol {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:GROUP|"
    const val VERSION = 2
    const val MAX_MEMBERS = 17 // creator plus sixteen verified friends
    const val MAX_RELAYS = 2
    private const val MAX_BYTES = 10000
    val EVENT_KINDS = listOf("post", "delete", "purge", "profile", "read")

    /** [inner] (Tink public key) and [relays] are the creator's verified copy of how to reach this member
     * directly. Rosters stored before direct delivery (v1) have neither. */
    data class Member(val key: String, val name: String, val inner: String? = null, val relays: List<String> = emptyList())
    data class State(val id: String, val owner: String, val name: String, val revision: Long, val members: List<Member>)
    /** Names a group version without its roster; receivers judge an event against their own roster copy. */
    data class Ref(val id: String, val owner: String, val revision: Long)
    sealed interface Packet
    data class Sync(val state: State) : Packet
    data class Event(val kind: String, val group: Ref, val event: String, val author: String, val text: String, val time: Long) : Packet
    enum class Verdict { ACCEPT, LATER, REJECT }

    fun eligible(contact: Contact) = contact.qrVerified && !contact.isDeleted &&
        contact.trustLevel != ContactTrustLevel.BLOCKED && contact.publicKey != null &&
        contact.pendingPublicKey == null && contact.nostrPubkey != null
    fun isControl(body: String) = body.startsWith(PREFIX)
    fun memberId(key: String): Long = -(key.take(15).toLong(16) + 1)
    fun ref(state: State) = Ref(state.id, state.owner, state.revision)
    fun relayHints(hints: List<String>) = hints.filter { it.length <= 100 && InboxRelays.isAcceptableRelay(it) }.distinct().take(MAX_RELAYS)
    private fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    private fun key(value: String) = value.matches(Regex("[0-9a-f]{64}"))
    private fun inner(value: String) = value.length in 40..256 && value.matches(Regex("[A-Za-z0-9+/]+={0,2}"))
    private fun label(value: String) = value.length in 1..30 && value == value.trim() &&
        value.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
    private fun valid(s: State) = uuid(s.id) && key(s.owner) && label(s.name) && s.revision > 0 && s.members.size in 1..MAX_MEMBERS &&
        s.members.all { m -> key(m.key) && label(m.name) && (m.inner == null || inner(m.inner)) && relayHints(m.relays) == m.relays } &&
        s.members.map { it.key }.distinct().size == s.members.size && s.members.any { it.key == s.owner }
    private fun valid(e: Event) = e.kind in EVENT_KINDS && uuid(e.group.id) && key(e.group.owner) && e.group.revision > 0 &&
        uuid(e.event) && key(e.author) && e.text.isNotBlank() && e.text.length <= 4000 && e.time > 0 && when (e.kind) {
            "post" -> !com.relay.app.media.MediaBody.isMedia(e.text) || com.relay.app.media.MediaBody.parse(e.text) != null
            "delete" -> uuid(e.text)
            "purge" -> (e.text.toLongOrNull() ?: 0) > 0
            "read" -> com.relay.app.messaging.ReadReceiptBody.parseIds(e.text) != null
            else -> com.relay.app.media.ProfileNameBody.parse(e.text) != null
        }

    fun format(p: Packet): String {
        val o = JSONObject().put("v", VERSION)
        when (p) {
            is Sync -> {
                val s = p.state
                require(valid(s))
                o.put("kind", "sync").put("id", s.id).put("owner", s.owner).put("name", s.name).put("revision", s.revision)
                o.put("members", JSONArray().apply { s.members.forEach { m -> put(JSONObject().put("key", m.key).put("name", m.name).apply {
                    m.inner?.let { put("inner", it) }
                    if (m.relays.isNotEmpty()) put("relays", JSONArray().apply { m.relays.forEach { put(it) } })
                }) } })
            }
            is Event -> {
                require(valid(p))
                o.put("kind", p.kind).put("id", p.group.id).put("owner", p.group.owner).put("revision", p.group.revision)
                    .put("event", p.event).put("author", p.author).put("text", p.text).put("time", p.time)
            }
        }
        val result = PREFIX + o.toString()
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return result
    }
    fun parse(body: String): Packet? = runCatching {
        require(isControl(body) && body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val o = JSONObject(body.removePrefix(PREFIX)); val v = o.getInt("v"); val kind = o.getString("kind")
        if (kind == "sync") {
            // Rosters saved before direct delivery are still v1 in the database.
            require(v == 1 || v == VERSION)
            val a = o.getJSONArray("members"); require(a.length() <= MAX_MEMBERS)
            val members = (0 until a.length()).map { i -> a.getJSONObject(i).let { m ->
                Member(m.getString("key"), m.getString("name"), if (m.has("inner")) m.getString("inner") else null,
                    m.optJSONArray("relays")?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty())
            } }
            Sync(State(o.getString("id"), o.getString("owner"), o.getString("name"), o.getLong("revision"), members)).also { require(valid(it.state)) }
        } else {
            // v1 posts were forwarded by the creator; v2 phones only take events straight from their author.
            require(v == VERSION)
            Event(kind, Ref(o.getString("id"), o.getString("owner"), o.getLong("revision")), o.getString("event"),
                o.getString("author"), o.getString("text"), o.getLong("time")).also { require(valid(it)) }
        }
    }.getOrNull()

    /** Drops relay hints (never members or keys) until the roster fits in one message. */
    fun fitted(state: State): State = (MAX_RELAYS downTo 0)
        .map { n -> state.copy(members = state.members.map { it.copy(relays = it.relays.take(n)) }) }
        .firstOrNull { runCatching { format(Sync(it)) }.isSuccess } ?: state

    /** Roster updates come only from the creator and never move backwards; a same-revision copy must be identical. */
    fun acceptSync(sender: String, current: State?, incoming: State) = sender == incoming.owner &&
        (current == null || (current.owner == incoming.owner && current.id == incoming.id &&
            (incoming.revision > current.revision || incoming == current)))

    /** Events arrive straight from their author, so the sender must be the author and the receiver's own roster
     * decides membership. A newer revision than ours may list an author we haven't seen yet: such an event waits
     * for that roster instead of being dropped. */
    fun checkEvent(sender: String, current: State?, e: Event): Verdict = when {
        sender != e.author -> Verdict.REJECT
        current == null -> Verdict.LATER
        current.id != e.group.id || current.owner != e.group.owner -> Verdict.REJECT
        current.members.any { it.key == e.author } -> Verdict.ACCEPT
        e.group.revision > current.revision -> Verdict.LATER
        else -> Verdict.REJECT
    }
}
