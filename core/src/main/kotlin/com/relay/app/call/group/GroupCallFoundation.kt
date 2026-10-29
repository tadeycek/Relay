package com.relay.app.call.group

import com.relay.app.call.StrictJson
import com.relay.app.groups.GroupProtocol
import java.security.MessageDigest

/** Control *plaintext* for TYPE:CALL stored group fan-out. Host MUST seal/authenticate E2E before delivery;
 * never put this JSON (especially joinSecret) in a server-visible envelope, log or chat history. */
data class GroupAnnouncement(val op: String, val eventId: String, val groupId: String, val owner: String,
    val revision: Long, val rosterHash: String, val room: String, val joinSecret: String?,
    val caller: String, val device: String, val created: Long, val expires: Long, val keyEpoch: Long? = null,
    /** WP17: base64 of the 32-byte per-call key. Present only inside the E2E-sealed group_start. */
    val callKey: String? = null) {
    override fun toString(): String = "GroupAnnouncement(op=$op, eventId=$eventId, groupId=$groupId, room=$room, " +
        "joinSecret=<redacted>, callKey=<redacted>, keyEpoch=$keyEpoch)"
}
/** Construct ONLY after outer E2E seal verification; this type is not itself cryptographic proof.
 * Public factory exists for platform boundaries and tests: callers must never use the plaintext body caller. */
class VerifiedGroupSender private constructor(val identity: String) {
    companion object {
        fun fromAuthenticatedSeal(identity: String): VerifiedGroupSender {
            require(Regex("[0-9a-f]{64}").matches(identity))
            return VerifiedGroupSender(identity)
        }
    }
}

enum class GroupReject { MALFORMED, TOO_LARGE, FOREIGN_ROSTER, STALE_ROSTER, UNAUTHENTICATED, EXPIRED, DUPLICATE }
sealed interface GroupDecodeResult {
    data class Accepted(val announcement: GroupAnnouncement) : GroupDecodeResult
    data class Rejected(val reason: GroupReject) : GroupDecodeResult
}
data class GroupCallFenceSnapshot(val ended: Map<Pair<String, String>, Long>, val replayed: Map<String, Long>, val activeRoom: Pair<String, String>?)
enum class ReplayAdmission { ACCEPTED, DUPLICATE, FULL }
/** Per-device bounded replay cache; persist a snapshot atomically with accepted events.
 * Each sender may hold at most [perSenderQuota] live entries; a sender over quota (or filling the last slot)
 * evicts ITS OWN oldest entry, so one flooding member cannot starve the others. Entries restored from a
 * snapshot carry no sender and are the first foreign entries evicted. A sender-less accept stays fail-closed. */
class GroupEventReplay(private val capacity: Int = 256, initial: Map<String, Long> = emptyMap(),
    private val perSenderQuota: Int = 12) {
    private class Entry(val expires: Long, val sender: String?)
    private val seen = LinkedHashMap<String, Entry>()
    init {
        require(capacity in 1..4096 && perSenderQuota >= 1)
        // Never throws on an oversized/hostile snapshot: keep the newest `capacity` entries.
        runCatching { initial.entries.toList().takeLast(capacity).forEach { seen[it.key] = Entry(it.value, null) } }
    }
    fun snapshot(): Map<String, Long> = seen.mapValuesTo(LinkedHashMap()) { it.value.expires }
    fun accept(id: String, expires: Long = Long.MAX_VALUE, now: Long = 0L, sender: String? = null): Boolean =
        offer(id, expires, now, sender) == ReplayAdmission.ACCEPTED
    fun offer(id: String, expires: Long = Long.MAX_VALUE, now: Long = 0L, sender: String? = null): ReplayAdmission {
        seen.entries.removeIf { it.value.expires <= now }
        if (id in seen) return ReplayAdmission.DUPLICATE
        if (sender != null) {
            val own = seen.entries.filter { it.value.sender == sender }
            if (own.size >= perSenderQuota) seen.remove(own.first().key)
        }
        if (seen.size >= capacity) {
            val victim = if (sender == null) null else
                seen.entries.firstOrNull { it.value.sender == sender } ?: seen.entries.firstOrNull { it.value.sender == null }
            if (victim == null) return ReplayAdmission.FULL
            seen.remove(victim.key)
        }
        seen[id] = Entry(expires, sender)
        return ReplayAdmission.ACCEPTED
    }
}
object GroupCallCodec {
    const val VERSION = 1
    const val MAX_BYTES = 2048
    const val MAX_LIFETIME_MS = 10 * 60_000L
    private val hex16 = Regex("[0-9a-f]{16}")
    private val hex32 = Regex("[0-9a-f]{32}")
    private val hex64 = Regex("[0-9a-f]{64}")
    private val devicePattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val common = setOf("v", "op", "event", "group", "owner", "revision", "rosterHash", "room",
        "joinSecret", "caller", "device", "created", "expires")
    /** WP17 key fields are optional and only meaningful on group_start. */
    private val keyFields = setOf("keyEpoch", "callKey")
    private fun randomHex(random: (Int) -> ByteArray, bytes: Int): String {
        val value = random(bytes)
        require(value.size == bytes) { "random provider returned wrong size" }
        return value.joinToString("") { "%02x".format(it) }
    }
    /** Host injects SecureRandom::nextBytes-equivalent; no predictable source in production. */
    fun newStart(roster: GroupProtocol.State, caller: String, device: String, now: Long,
        random: (Int) -> ByteArray, keyEpoch: Long? = null, callKey: ByteArray? = null): GroupAnnouncement {
        val room = randomHex(random, 16)
        val secret = randomHex(random, 32)
        val event = randomHex(random, 16)
        require((keyEpoch == null) == (callKey == null)) { "keyEpoch and callKey must be supplied together" }
        return GroupAnnouncement("group_start", event, roster.id, roster.owner, roster.revision,
            rosterHash(roster), room, secret, caller, device, now, now + MAX_LIFETIME_MS, keyEpoch,
            callKey?.let { java.util.Base64.getEncoder().encodeToString(it) }).also { encode(it) }
    }
    fun newEnd(start: GroupAnnouncement, now: Long, random: (Int) -> ByteArray): GroupAnnouncement =
        start.copy(op = "group_end", eventId = randomHex(random, 16), joinSecret = null,
            keyEpoch = null, callKey = null, created = now, expires = now + MAX_LIFETIME_MS).also { encode(it) }
    /** Hash of identity membership only, not mutable display names/relay hints. A signed owner roster
     * revision/proof is still a G5 server contract, NOT provided by this hash. */
    fun rosterHash(roster: GroupProtocol.State): String {
        require(roster.revision > 0 && hex64.matches(roster.owner) && roster.members.size in 1..17 &&
            roster.members.map { it.key }.distinct().size == roster.members.size &&
            roster.members.all { hex64.matches(it.key) } && roster.members.any { it.key == roster.owner })
        val canonical = "relay-group-call-roster-v1\n${roster.id}\n${roster.owner}\n${roster.revision}\n" +
            roster.members.map { it.key }.sorted().joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun valid(a: GroupAnnouncement) = a.op in setOf("group_start", "group_end") &&
        hex32.matches(a.eventId) && a.groupId.length == 36 &&
        runCatching { java.util.UUID.fromString(a.groupId).toString() == a.groupId }.getOrDefault(false) &&
        hex64.matches(a.owner) && a.revision > 0 && hex64.matches(a.rosterHash) && hex32.matches(a.room) &&
        (if (a.op == "group_start") a.joinSecret?.let { hex64.matches(it) } == true else a.joinSecret == null) &&
        hex64.matches(a.caller) && devicePattern.matches(a.device) && a.created > 0 &&
        a.expires > a.created && a.expires - a.created <= MAX_LIFETIME_MS &&
        // WP17: the key travels only inside a sealed group_start; either both fields or neither.
        ((a.keyEpoch == null && a.callKey == null) ||
            (a.op == "group_start" && a.keyEpoch != null && a.keyEpoch!! >= 0 && a.keyEpoch!! <= Int.MAX_VALUE &&
                a.callKey != null && runCatching { java.util.Base64.getDecoder().decode(a.callKey!!).size == 32 }.getOrDefault(false)))

    fun secretHash(joinSecret: String): String {
        require(hex64.matches(joinSecret)) { "invalid join secret" }
        return MessageDigest.getInstance("SHA-256").digest(joinSecret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    fun encode(a: GroupAnnouncement): String {
        require(valid(a)) { "invalid group call control" }
        val out = "{\"v\":1,\"op\":\"${a.op}\",\"event\":\"${a.eventId}\",\"group\":\"${a.groupId}\"," +
            "\"owner\":\"${a.owner}\",\"revision\":${a.revision},\"rosterHash\":\"${a.rosterHash}\"," +
            "\"room\":\"${a.room}\",\"joinSecret\":" + (a.joinSecret?.let { "\"$it\"" } ?: "null") +
            ",\"caller\":\"${a.caller}\",\"device\":\"${a.device}\",\"created\":${a.created},\"expires\":${a.expires}" +
            (a.keyEpoch?.let { ",\"keyEpoch\":$it" } ?: "") +
            (a.callKey?.let { ",\"callKey\":\"$it\"" } ?: "") + "}"
        require(out.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return out
    }
    /** authenticatedSender MUST come from the verified E2E seal, never from caller in the body. */
    fun decode(wire: String, authenticatedSender: VerifiedGroupSender, roster: GroupProtocol.State, now: Long,
        replay: GroupEventReplay): GroupDecodeResult {
        if (wire.length > MAX_BYTES || wire.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return GroupDecodeResult.Rejected(GroupReject.TOO_LARGE)
        val obj = try { StrictJson.parse(wire) as? Map<*, *> } catch (_: Exception) { null }
            ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED)
        if (obj.keys != common && obj.keys != common + keyFields) return GroupDecodeResult.Rejected(GroupReject.MALFORMED)
        if (obj["v"] != 1L) return GroupDecodeResult.Rejected(GroupReject.MALFORMED)
        val a = GroupAnnouncement(
            obj["op"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["event"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["group"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["owner"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["revision"] as? Long ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["rosterHash"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["room"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            (obj["joinSecret"] as? String).also { if (obj["joinSecret"] !== StrictJson.Null && it == null) return GroupDecodeResult.Rejected(GroupReject.MALFORMED) },
            obj["caller"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["device"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["created"] as? Long ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            obj["expires"] as? Long ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED),
            if ("keyEpoch" in obj) obj["keyEpoch"] as? Long ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED) else null,
            if ("callKey" in obj) obj["callKey"] as? String ?: return GroupDecodeResult.Rejected(GroupReject.MALFORMED) else null)
        if (!valid(a)) return GroupDecodeResult.Rejected(GroupReject.MALFORMED)
        if (a.groupId != roster.id || a.owner != roster.owner) return GroupDecodeResult.Rejected(GroupReject.FOREIGN_ROSTER)
        if (a.revision != roster.revision || a.rosterHash != rosterHash(roster)) return GroupDecodeResult.Rejected(GroupReject.STALE_ROSTER)
        if (authenticatedSender.identity != a.caller || roster.members.none { it.key == a.caller }) return GroupDecodeResult.Rejected(GroupReject.UNAUTHENTICATED)
        if (now >= a.expires || now < a.created - 30_000) return GroupDecodeResult.Rejected(GroupReject.EXPIRED)
        if (!replay.accept(a.eventId, a.expires, now, a.caller)) return GroupDecodeResult.Rejected(GroupReject.DUPLICATE)
        return GroupDecodeResult.Accepted(a)
    }
}

data class GroupTrackRef(val pid: String, val sessionId: String, val track: String)
data class GroupParticipant(val pid: String, val pubkey: String, val device: String, val sessionId: String?,
    val track: String?, val muted: Boolean) {
    fun trackRef(): GroupTrackRef? = if (sessionId != null && track != null) GroupTrackRef(pid, sessionId, track) else null
}
sealed interface GroupCallEffect {
    data class OpenRoom(val room: String, val secret: String) : GroupCallEffect {
        override fun toString(): String = "OpenRoom(room=$room, secret=<redacted>)"
    }
    data object CloseRoom : GroupCallEffect
    data class Subscribe(val ref: GroupTrackRef) : GroupCallEffect
    data class Unsubscribe(val ref: GroupTrackRef) : GroupCallEffect
    data class WarnUnknown(val pid: String) : GroupCallEffect
    data class SetMicrophoneMuted(val muted: Boolean) : GroupCallEffect
    data class SetPlaybackDeafened(val deafened: Boolean) : GroupCallEffect
}
data class GroupCallState(val room: String? = null, val participants: List<GroupParticipant> = emptyList(),
    val muted: Boolean = false, val deafened: Boolean = false) {
    /** Computed, not a constructor property: `copy()` cannot change it. */
    val securityLabel: String get() = SECURITY_LABEL
    companion object { const val SECURITY_LABEL = "Group calls are encrypted in transit, not end to end." }
}
private val hex32Re = Regex("[0-9a-f]{32}")
private val hex64Re = Regex("[0-9a-f]{64}")
private val pidRe = Regex("[0-9a-f]{16}")
private val deviceRe = Regex("[A-Za-z0-9_-]{1,64}")
private val sessionRe = Regex("[A-Za-z0-9_-]{1,256}")
private val trackRe = Regex("[A-Za-z0-9_-]{1,64}")

/** Pure serial event reducer. Effects are requests to the host, not evidence of server authorization/cleanup. */
class GroupCallEngine(initialRoster: GroupProtocol.State, private val self: String, private val clock: () -> Long,
    snapshot: GroupCallFenceSnapshot? = null) {
    private companion object {
        const val FENCE_CAP = 256
        const val SENDER_QUOTA = 12      // 17 members x 12 < 256: no single sender can fill the maps
        const val TIE_WINDOW_MS = 5_000L // only unconfirmed starts this close together are tie-broken
        const val MAX_RESTORE_SCAN = 1024
    }
    fun snapshot(): GroupCallFenceSnapshot = GroupCallFenceSnapshot(endedRooms.toMap(), seen.snapshot(), active?.let { it.room to it.caller } ?: recoveryRoom)
    private var roster = initialRoster
    private var active: GroupAnnouncement? = null
    private val endedRooms = LinkedHashMap<Pair<String, String>, Long>()
    private val seen: GroupEventReplay
    private val recoveryRoom: Pair<String, String>?
    private var confirmed = false
    private var subscribed: Set<GroupTrackRef> = emptySet()
    var state = GroupCallState(); private set
    init {
        // A restored snapshot is untrusted: validate, clamp, truncate; never throw (fail closed by dropping).
        val t = clock()
        val limit = t + GroupCallCodec.MAX_LIFETIME_MS
        val replayed = LinkedHashMap<String, Long>()
        var room: Pair<String, String>? = null
        runCatching {
            if (snapshot != null) {
                snapshot.ended.entries.toList().takeLast(MAX_RESTORE_SCAN).forEach { (k, v) ->
                    runCatching {
                        if (hex32Re.matches(k.first) && hex64Re.matches(k.second) && v > t) addFence(k, minOf(v, limit))
                    }
                }
                snapshot.replayed.entries.toList().takeLast(MAX_RESTORE_SCAN).forEach { (k, v) ->
                    runCatching { if (hex32Re.matches(k) && v > t) replayed[k] = minOf(v, limit) }
                }
                room = snapshot.activeRoom?.takeIf { hex32Re.matches(it.first) && hex64Re.matches(it.second) }
            }
        }
        seen = GroupEventReplay(initial = replayed)
        recoveryRoom = room
    }
    /** HOST-TRUST ONLY. Callable solely by the platform group-call controller (Android/desktop host) after an
     * authenticated SFU room join AND a successful live-state readback for exactly this room. Never call it from
     * a received announcement, a participant list or any peer-supplied data. Confirmation keeps an established
     * room alive past the invite expiry, enables Subscribe effects, and is what makes a room immune to preemption.
     * Returns the Subscribe effects for participants already known. */
    fun confirmedRoom(room: String): List<GroupCallEffect> {
        val current = active ?: return emptyList()
        if (current.room != room || clock() >= current.expires) return emptyList()
        confirmed = true
        return reconcile()
    }
    /** Unknown/failed readback is not confirmation: tear down, never retain stale playback. */
    fun roomStateUnknown(): List<GroupCallEffect> = close()
    private fun addFence(key: Pair<String, String>, expires: Long) {
        endedRooms.entries.removeIf { it.value <= clock() }
        val existing = endedRooms.remove(key)
        val own = endedRooms.keys.filter { it.second == key.second }
        if (own.size >= SENDER_QUOTA) endedRooms.remove(own.first())
        if (endedRooms.size >= FENCE_CAP) endedRooms.remove(endedRooms.keys.first())
        endedRooms[key] = maxOf(expires, existing ?: 0L)
    }
    /** Only call with sender from the authenticated E2E seal (or a successful codec decode). */
    fun apply(a: GroupAnnouncement, authenticatedSender: VerifiedGroupSender): List<GroupCallEffect> {
        if (authenticatedSender.identity != a.caller || clock() >= a.expires || a.created > clock() + 30_000 || a.groupId != roster.id || a.owner != roster.owner ||
            a.revision != roster.revision || a.rosterHash != GroupCallCodec.rosterHash(roster) ||
            roster.members.none { it.key == a.caller } || roster.members.none { it.key == self } ||
            !runCatching { GroupCallCodec.encode(a) }.isSuccess) return emptyList()
        // Every rejection below happens BEFORE the replay slot is taken, so rejected events burn nothing.
        if (a.op == "group_end") {
            val isActive = active?.room == a.room
            if (isActive && active?.caller != a.caller) return emptyList()
            val admission = seen.offer(a.eventId, a.expires, clock(), a.caller)
            if (admission == ReplayAdmission.DUPLICATE || (admission == ReplayAdmission.FULL && !isActive)) return emptyList()
            // An end for the ACTIVE room always closes it, whatever the caps say.
            addFence(a.room to a.caller, maxOf(a.expires, a.created + GroupCallCodec.MAX_LIFETIME_MS))
            return if (isActive) close() else emptyList()
        }
        endedRooms.entries.removeIf { it.value <= clock() }
        if ((a.room to a.caller) in endedRooms || (a.room to a.caller) == recoveryRoom) return emptyList()
        val lapsed = tick()
        val current = active
        if (current != null) {
            // A confirmed room is never preempted. Two unconfirmed starts are only tie-broken when created
            // within a few seconds of each other; a far-backdated start cannot displace a live call.
            val tied = !confirmed && kotlin.math.abs(a.created - current.created) <= TIE_WINDOW_MS &&
                (a.created < current.created || (a.created == current.created && a.room < current.room))
            if (!tied) return lapsed
        }
        if (seen.offer(a.eventId, a.expires, clock(), a.caller) != ReplayAdmission.ACCEPTED) return lapsed
        val effects = if (current != null) close() else emptyList()
        active = a
        confirmed = false
        state = GroupCallState(room = a.room)
        return lapsed + effects + GroupCallEffect.OpenRoom(a.room, a.joinSecret!!)
    }
    private fun close(): List<GroupCallEffect> {
        val current = active ?: return emptyList()
        addFence(current.room to current.caller, current.expires)
        val effects = subscribed.map { GroupCallEffect.Unsubscribe(it) }
        active = null
        confirmed = false
        subscribed = emptySet()
        state = GroupCallState()
        return effects + GroupCallEffect.CloseRoom
    }
    private fun participantValid(it: GroupParticipant) = pidRe.matches(it.pid) && hex64Re.matches(it.pubkey) &&
        deviceRe.matches(it.device) && (it.sessionId == null || sessionRe.matches(it.sessionId)) &&
        (it.track == null || (it.sessionId != null && trackRe.matches(it.track)))
    /** Subscriptions follow the host-confirmed room only; unconfirmed rooms subscribe to nothing. */
    private fun reconcile(): List<GroupCallEffect> {
        val desired = if (!confirmed) emptySet() else state.participants.filter { p -> roster.members.any { it.key == p.pubkey } }
            .mapNotNull { it.trackRef() }.toCollection(LinkedHashSet())
        val effects = (subscribed - desired).map { GroupCallEffect.Unsubscribe(it) } + (desired - subscribed).map { GroupCallEffect.Subscribe(it) }
        subscribed = desired
        return effects
    }
    /** Offending entries (malformed, duplicate pid, beyond the 17th valid) are dropped; the rest stay live.
     * Only a snapshot with entries but none usable is treated as unusable and closes the room. */
    fun updateParticipants(participants: List<GroupParticipant>): List<GroupCallEffect> {
        val expired = tick()
        if (expired.isNotEmpty()) return expired
        if (active == null) return emptyList()
        val pids = HashSet<String>()
        val valid = ArrayList<GroupParticipant>()
        for (p in participants) {
            if (valid.size >= 17) break
            if (participantValid(p) && pids.add(p.pid)) valid += p
        }
        if (participants.isNotEmpty() && valid.isEmpty()) return close()
        state = state.copy(participants = valid.toList())
        return reconcile() + valid.filter { p -> roster.members.none { it.key == p.pubkey } }.map { GroupCallEffect.WarnUnknown(it.pid) }
    }
    fun updateRoster(next: GroupProtocol.State): List<GroupCallEffect> {
        if (next.id != roster.id || next.owner != roster.owner || next.revision < roster.revision) return emptyList()
        if (next.revision == roster.revision && GroupCallCodec.rosterHash(next) != GroupCallCodec.rosterHash(roster)) return emptyList()
        roster = next
        // A capability belongs to the frozen revision: all peers leave until a new signed room exists.
        return if (active != null && (next.revision != active!!.revision || next.members.none { it.key == self })) close() else emptyList()
    }
    fun tick() = if (active != null && !confirmed && clock() >= active!!.expires) close() else emptyList()
    fun mute(value: Boolean): List<GroupCallEffect> {
        if (active == null || state.muted == value) return emptyList()
        state = state.copy(muted = value)
        return listOf(GroupCallEffect.SetMicrophoneMuted(value))
    }
    fun deafen(value: Boolean): List<GroupCallEffect> {
        if (active == null || state.deafened == value) return emptyList()
        state = state.copy(deafened = value)
        return listOf(GroupCallEffect.SetPlaybackDeafened(value))
    }
    fun leave() = close()
}
