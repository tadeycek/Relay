package com.relay.app.calls.group

import com.relay.app.call.CallLimits
import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallEffect
import com.relay.app.call.group.GroupCallEngine
import com.relay.app.call.group.GroupCallFenceSnapshot
import com.relay.app.call.group.GroupDecodeResult
import com.relay.app.call.group.GroupEventReplay
import com.relay.app.call.group.VerifiedGroupSender
import com.relay.app.groups.GroupProtocol
import org.json.JSONObject

/**
 * Wire form of a `group_start`/`group_end` announcement: the 05 §6 `TYPE:CALL|J:<base64 JSON>` frame, so it
 * travels as a stored, sealed per-member message like any other call body. The 1:1 engine never sees these:
 * [unwrap] only answers for ops that start with `group_`.
 */
object GroupCallWire {
    /**
     * `group_reseal` is an app-level control frame (never a core announcement): after a creator rotates a
     * room capability it hands the new secret to the members who stayed. It is still E2E sealed inside the
     * group fan-out like every other frame here.
     */
    private val OPS = setOf("group_start", "group_end", "group_reseal")

    fun wrap(json: String): String = CallLimits.PREFIX + java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))

    /** The announcement JSON, or null when [body] is anything else (chat text, a 1:1 call body, garbage). */
    fun unwrap(body: String): String? {
        if (!body.startsWith(CallLimits.PREFIX) || body.length > CallLimits.MAX_WIRE_CHARS + CallLimits.PREFIX.length) return null
        val json = try {
            String(java.util.Base64.getDecoder().decode(body.substring(CallLimits.PREFIX.length)), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) { return null }
        val op = try { JSONObject(json).opt("op") as? String } catch (_: Exception) { null }
        return json.takeIf { op in OPS }
    }

    fun isAnnouncement(body: String): Boolean = unwrap(body) != null
}

/** Durable replay/end fences. [commit] must be atomic (all-or-nothing) and is called before any effect is released. */
interface GroupCallStore {
    fun load(): GroupCallFenceSnapshot?
    fun commit(snapshot: GroupCallFenceSnapshot)
}

/** What the group chat may show. Never carries the join secret. */
data class GroupCallBanner(val room: String, val caller: String, val created: Long, val expires: Long)

sealed interface IngressResult {
    data object NotAnnouncement : IngressResult
    data class Rejected(val reason: String) : IngressResult
    data class Accepted(val effects: List<GroupCallEffect>, val banner: GroupCallBanner?) : IngressResult
    /** The snapshot could not be written: the event was NOT applied and any live room must close. */
    data class PersistFailed(val effects: List<GroupCallEffect> = emptyList()) : IngressResult
}

/**
 * One group's call state on this device: the core [GroupCallEngine] plus durable fences.
 *
 * Every state change that touches the replay cache or the end fences is persisted through [store] *before* its
 * effects or banner are released; a failed commit rolls the engine back to the last persisted snapshot and
 * fails closed (the event can be redelivered, nothing is shown). Not thread-safe: the host serialises calls.
 */
class GroupCallCoordinator(
    private var roster: GroupProtocol.State,
    private val self: String,
    private val clock: () -> Long,
    private val store: GroupCallStore,
) {
    private var engine = GroupCallEngine(roster, self, clock, store.load())
    private var announcement: GroupAnnouncement? = null

    /**
     * [authenticatedSender] is the identity proven by the E2E seal of the envelope this body arrived in. The
     * [VerifiedGroupSender] is built from it here and from nothing else: the body's own `caller` is only
     * compared against it by the core codec.
     */
    fun onWire(authenticatedSender: String, body: String): IngressResult {
        val json = GroupCallWire.unwrap(body) ?: return IngressResult.NotAnnouncement
        if (json.contains("\"op\":\"group_reseal\"")) {
            return try { onReseal(authenticatedSender, JSONObject(json)) }
            catch (_: Exception) { IngressResult.Rejected("MALFORMED") }
        }
        val verified = try { VerifiedGroupSender.fromAuthenticatedSeal(authenticatedSender) } catch (_: IllegalArgumentException) {
            return IngressResult.Rejected("UNAUTHENTICATED")
        }
        // Shape, size, roster binding, expiry and sender checks; a throwaway replay cache, the engine owns the real one.
        val announcement = when (val decoded = GroupCallCodec.decode(json, verified, roster, clock(), GroupEventReplay())) {
            is GroupDecodeResult.Rejected -> return IngressResult.Rejected(decoded.reason.name)
            is GroupDecodeResult.Accepted -> decoded.announcement
        }
        val hadRoom = engine.state.room != null
        val outcome = persisted { engine.apply(announcement, verified) }
        return when (outcome) {
            null -> IngressResult.PersistFailed(if (hadRoom) listOf(GroupCallEffect.CloseRoom) else emptyList())
            else -> {
                if (outcome.changed.not() && outcome.effects.isEmpty()) return IngressResult.Rejected("IGNORED")
                if (outcome.effects.any { it is GroupCallEffect.OpenRoom && it.room == announcement.room }) this.announcement = announcement
                if (outcome.effects.contains(GroupCallEffect.CloseRoom)) this.announcement = null
                IngressResult.Accepted(outcome.effects, liveBanner())
            }
        }
    }

    /** The call to offer in the group chat, or null: nothing announced, ended, fenced, restarted-over or expired. */
    fun liveBanner(): GroupCallBanner? {
        persisted { engine.tick() }?.let { if (it.effects.contains(GroupCallEffect.CloseRoom)) announcement = null }
        val a = announcement ?: return null
        if (engine.state.room != a.room) return null
        return GroupCallBanner(a.room, a.caller, a.created, a.expires)
    }

    /** `GET /v1/call/rooms/<room>` answered 404 (or a mismatch): the call is gone, whatever the announcement says. */
    fun roomGone(): List<GroupCallEffect> = run { engine.roomStateUnknown() }

    /** Room and secret of the call announced to us, for the join. Memory only; never persisted or logged. */
    fun joinInfo(): Pair<String, String>? = announcement?.takeIf { engine.state.room == it.room }?.let { it.room to it.joinSecret!! }

    /**
     * WP17 epoch key of the call this device is tracking. Comes from the verified, E2E-sealed group_start (or a
     * later sealed group_reseal) and lives in memory only. Null when the call carries no key.
     */
    fun joinKey(): GroupCallE2eeKey? = announcement?.takeIf { engine.state.room == it.room }
        ?.let { GroupCallE2eeKey.fromAnnouncement(it.keyEpoch, it.callKey) }

    /** The announcement this device is tracking, for the creator's end path. Carries the secret: memory only. */
    fun currentAnnouncement(): GroupAnnouncement? = announcement?.takeIf { engine.state.room == it.room }

    /** The roster the engine is currently reducing against. */
    fun roster(): GroupProtocol.State = roster

    /** Whether the engine still holds a room (used to avoid resurrecting a persisted banner after restart). */
    fun hasActiveRoom(): Boolean = engine.state.room != null

    /**
     * App-level capability re-seal after `POST /roster` rotated the room secret. Only the authenticated
     * creator of the live room may do this, and only for the roster revision we already hold. The new
     * secret is kept in memory exactly like the one in `group_start`; it is never persisted.
     */
    fun onReseal(authenticatedSender: String, o: JSONObject): IngressResult {
        if (o.optInt("v", -1) != 1 || o.optString("op") != "group_reseal") return IngressResult.Rejected("MALFORMED")
        val caller = o.optString("caller")
        val room = o.optString("room")
        val secret = o.optString("joinSecret")
        val revision = o.optLong("revision", -1)
        val group = o.optString("group")
        val owner = o.optString("owner")
        val expires = o.optLong("expires", 0)
        val now = clock()
        val current = announcement ?: return IngressResult.Rejected("IGNORED")
        // WP17: a re-key travels in the same sealed control, to the remaining members only. Both key fields
        // or neither; a malformed or stale one is refused rather than applied.
        val hasEpoch = o.has("keyEpoch")
        val hasKey = o.has("callKey")
        if (hasEpoch != hasKey) return IngressResult.Rejected("MALFORMED")
        val epochRaw = if (hasEpoch) o.optLong("keyEpoch", -1L) else -1L
        val rotated = if (hasEpoch && hasKey) {
            GroupCallE2eeKey.fromAnnouncement(epochRaw.takeIf { it >= 0 }, o.optString("callKey", ""))
                ?: return IngressResult.Rejected("MALFORMED")
        } else null
        if (rotated != null && rotated.epoch <= (current.keyEpoch ?: -1L)) return IngressResult.Rejected("STALE_KEY")
        val live = engine.state.room
        if (caller.length != 64 || caller != authenticatedSender || caller != current.caller) return IngressResult.Rejected("UNAUTHENTICATED")
        if (live != room || current.room != room) return IngressResult.Rejected("IGNORED")
        if (group != roster.id || owner != roster.owner || revision != roster.revision) return IngressResult.Rejected("STALE_ROSTER")
        if (!secret.matches(Regex("[0-9a-f]{64}")) || expires <= now || expires - now > GroupCallCodec.MAX_LIFETIME_MS) {
            return IngressResult.Rejected("EXPIRED")
        }
        val before = engine.snapshot()
        announcement = current.copy(
            joinSecret = secret,
            expires = maxOf(current.expires, expires),
            keyEpoch = rotated?.epoch?.toLong() ?: current.keyEpoch,
            callKey = if (rotated != null) o.optString("callKey") else current.callKey,
        )
        // The secret is not part of the persisted fence snapshot, but keeping the same snapshot guarantees
        // a commit failure can never leave a rotated secret attached to an unpersisted room.
        return try {
            store.commit(engine.snapshot())
            IngressResult.Accepted(emptyList(), liveBanner())
        } catch (_: Exception) {
            announcement = current
            runCatching { store.commit(before) }
            IngressResult.PersistFailed()
        }
    }

    /**
     * HOST-TRUST ONLY (see `GroupCallEngine.confirmedRoom`): call after an authenticated room join and a live-state
     * readback of exactly this room that matched our roster. Null when this device is not tracking [room] at all.
     */
    fun confirmedRoom(room: String): List<GroupCallEffect>? =
        if (engine.state.room != room) null else engine.confirmedRoom(room)

    fun updateParticipants(participants: List<com.relay.app.call.group.GroupParticipant>) = run { engine.updateParticipants(participants) }
    fun mute(muted: Boolean) = run { engine.mute(muted) }
    fun deafen(deafened: Boolean) = run { engine.deafen(deafened) }
    fun leave() = run { engine.leave() }
    fun updateRoster(next: GroupProtocol.State): List<GroupCallEffect> {
        if (next.id == roster.id && next.owner == roster.owner && next.revision >= roster.revision) roster = next
        return run { engine.updateRoster(next) }
    }

    /** Runs a state change and persists any fence it created; a failed commit closes the room (fail closed). */
    private fun run(op: () -> List<GroupCallEffect>): List<GroupCallEffect> {
        val hadRoom = engine.state.room != null
        val outcome = persisted(op) ?: return if (hadRoom) listOf(GroupCallEffect.CloseRoom) else emptyList()
        if (outcome.effects.contains(GroupCallEffect.CloseRoom)) announcement = null
        return outcome.effects
    }

    private class Outcome(val effects: List<GroupCallEffect>, val changed: Boolean)

    /** Runs [op] on the engine and commits the resulting snapshot; null (after rolling back) when the commit fails. */
    private fun persisted(op: () -> List<GroupCallEffect>): Outcome? {
        val before = engine.snapshot()
        val effects = op()
        val after = engine.snapshot()
        if (after == before) return Outcome(effects, false)
        return try {
            store.commit(after)
            Outcome(effects, true)
        } catch (_: Exception) {
            engine = GroupCallEngine(roster, self, clock, before)
            announcement = null
            null
        }
    }
}
