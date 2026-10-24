package com.relay.app.linking

import com.relay.app.groups.GroupProtocol
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/**
 * `GroupRoster/1`: the explicit, versioned, bounded body of a `TYPE:SYNC|GROUP` event (WP05-AC3/AC4,
 * LP-D03: rosters are edited on the phone and mirrored to linked computers).
 *
 * Plaintext (UTF-8 JSON, fixed key order, never sent unsealed):
 *
 * ```
 * {"v":1,"state":"<GroupProtocol Sync packet>","avatarRev":<long>,"avatar":"<base64 JPEG>"|null,"tombstone":<bool>}
 * ```
 *
 * - `state` is the existing [GroupProtocol] roster encoding verbatim (`TYPE:PROFILE_PHOTO|KIND:GROUP|{"v":2,"kind":"sync",
 *   id, owner, name, revision, members[key,name,inner?,relays?]}`), so id, owner, name, revision and members keep one
 *   definition and one validator (<= 17 members, name 1..30, <= 10 000 bytes).
 * - `avatarRev` is the group picture revision: `-1` = never had one (then `avatar` is null), `>= 0` with `avatar`
 *   null = picture removed at that revision. `avatar` is a JPEG (FF D8 FF magic) of 3..[MAX_AVATAR_BYTES] bytes.
 * - `tombstone` = the group is gone for this account (owner deleted it, we were removed, or we left): the
 *   receiver hides it. It never deletes message history.
 *
 * The whole plaintext is <= [MAX_PLAINTEXT_BYTES], is sealed to the account's own key (the sync server never sees
 * names, keys or the picture), and the sealed string travels as `J:` of a [SyncBody.Group] (chunked by
 * [SyncCodec.chunk] when it exceeds one 12 KiB wire body).
 */
object GroupRosterSync {
    const val VERSION = 1
    const val ORIGIN_PRIMARY = "primary"
    const val MAX_AVATAR_BYTES = 8 * 1024
    const val MAX_PLAINTEXT_BYTES = 24 * 1024

    private val KEYS = setOf("v", "state", "avatarRev", "avatar", "tombstone")

    data class Roster(
        val state: GroupProtocol.State,
        val avatarRevision: Long = -1,
        val avatar: ByteArray? = null,
        val tombstone: Boolean = false,
    ) {
        override fun equals(other: Any?) = this === other || (other is Roster && state == other.state &&
            avatarRevision == other.avatarRevision && tombstone == other.tombstone &&
            (avatar?.contentEquals(other.avatar ?: ByteArray(0)) ?: (other.avatar == null)))

        override fun hashCode() = state.hashCode() * 31 + avatarRevision.hashCode()
    }

    enum class RosterError { TOO_LARGE, UNSUPPORTED_VERSION, MALFORMED, UNREADABLE }

    sealed interface Result {
        data class Ok(val roster: Roster) : Result
        data class Err(val error: RosterError, val detail: String? = null) : Result
    }

    /** The avatar bound shared by encode and decode: a JPEG (FF D8 FF) of 3..[MAX_AVATAR_BYTES] bytes. */
    fun isValidAvatar(b: ByteArray) = b.size in 3..MAX_AVATAR_BYTES && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte() && b[2] == 0xff.toByte()

    /** Plaintext schema JSON. Throws [IllegalArgumentException] for anything outside the schema. */
    fun encode(r: Roster): String {
        require(r.avatarRevision >= -1) { "avatar revision" }
        require(r.avatar == null || (r.avatarRevision >= 0 && isValidAvatar(r.avatar))) { "avatar must be a JPEG of <= $MAX_AVATAR_BYTES bytes with a revision" }
        val state = GroupProtocol.format(GroupProtocol.Sync(r.state)) // validates id/owner/name/members/revision
        val avatar = r.avatar?.let { "\"" + Base64.getEncoder().encodeToString(it) + "\"" } ?: "null"
        val out = "{\"v\":$VERSION,\"state\":${JSONObject.quote(state)},\"avatarRev\":${r.avatarRevision},\"avatar\":$avatar,\"tombstone\":${r.tombstone}}"
        require(out.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) { "roster over $MAX_PLAINTEXT_BYTES bytes" }
        return out
    }

    /** Parses a plaintext roster. Never throws; every failure is [Result.Err]. */
    fun decode(plain: String): Result {
        if (plain.length > MAX_PLAINTEXT_BYTES || plain.toByteArray(Charsets.UTF_8).size > MAX_PLAINTEXT_BYTES) return Result.Err(RosterError.TOO_LARGE)
        return try {
            val o = JSONObject(plain)
            if (o.keySet() != KEYS) return Result.Err(RosterError.MALFORMED, "keys")
            val v = o.get("v")
            if (v !is Int || v != VERSION) return Result.Err(if (v is Int) RosterError.UNSUPPORTED_VERSION else RosterError.MALFORMED, "v")
            val state = (GroupProtocol.parse(o.get("state") as? String ?: return Result.Err(RosterError.MALFORMED, "state")) as? GroupProtocol.Sync)?.state
                ?: return Result.Err(RosterError.MALFORMED, "state")
            val rev = o.get("avatarRev").let { (it as? Number)?.toLong() } ?: return Result.Err(RosterError.MALFORMED, "avatarRev")
            if (rev < -1) return Result.Err(RosterError.MALFORMED, "avatarRev")
            val tomb = o.get("tombstone") as? Boolean ?: return Result.Err(RosterError.MALFORMED, "tombstone")
            val avatar: ByteArray? = if (o.isNull("avatar")) null else {
                val text = o.get("avatar") as? String ?: return Result.Err(RosterError.MALFORMED, "avatar")
                val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return Result.Err(RosterError.MALFORMED, "avatar")
                if (rev < 0 || !isValidAvatar(bytes)) return Result.Err(RosterError.MALFORMED, "avatar")
                bytes
            }
            Result.Ok(Roster(state, rev, avatar, tomb))
        } catch (e: Exception) {
            Result.Err(RosterError.MALFORMED)
        }
    }

    /** Stable id for one snapshot: the same snapshot always names the same sync event (retry-safe, 32 lower-hex). */
    fun syncId(r: Roster): String = MessageDigest.getInstance("SHA-256")
        .digest("relay-group:${r.state.owner}:${r.state.id}:${r.state.revision}:${r.avatarRevision}:${r.tombstone}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(32)

    /** Sync `R`: grows whenever the roster revision, the picture revision or the tombstone flag grows. */
    fun syncRevision(r: Roster): Long = r.state.revision + (r.avatarRevision + 1) + if (r.tombstone) 1 else 0

    /** Builds the sync body: plaintext sealed with [seal] (to the account's own key), then base64. */
    fun seal(r: Roster, origin: String, seal: (String) -> String?): SyncBody.Group {
        val sealed = seal(encode(r)) ?: throw IllegalArgumentException("cannot seal the roster to our own key")
        return SyncBody.Group(origin, syncId(r), syncRevision(r), Base64.getEncoder().encodeToString(sealed.toByteArray(Charsets.UTF_8)))
    }

    /** Opens [body] with [open] (our own key) and validates the schema. */
    fun open(body: SyncBody.Group, open: (String) -> String?): Result {
        val sealed = runCatching { String(Base64.getDecoder().decode(body.rosterJsonBase64), Charsets.UTF_8) }.getOrNull()
            ?: return Result.Err(RosterError.MALFORMED, "base64")
        val plain = open(sealed) ?: return Result.Err(RosterError.UNREADABLE)
        return decode(plain)
    }
}
