package com.relay.app.calls.group

import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallState
import com.relay.app.call.group.GroupParticipant
import com.relay.app.groups.GroupProtocol
import org.json.JSONArray
import org.json.JSONObject

/** One device (pid) of a person in the call. */
data class DeviceRow(val pid: String, val device: String, val muted: Boolean, val deafened: Boolean, val speaking: Boolean, val hasAudio: Boolean)

/** One person; their devices are grouped here. [inGroup] is the local roster's verdict, not the server's. */
data class PersonRow(val pubkey: String, val name: String, val inGroup: Boolean, val isSelf: Boolean,
    val speaking: Boolean, val volume: Float, val devices: List<DeviceRow>) {
    val warning: String? get() = if (inGroup) null else NOT_IN_GROUP
    companion object { const val NOT_IN_GROUP = "Not in this group" }
}

data class GroupCallUiModel(val securityLabel: String, val people: List<PersonRow>, val muted: Boolean, val deafened: Boolean, val canEnd: Boolean)

object GroupCallUiModelFactory {
    const val DEFAULT_VOLUME = 1f

    fun build(
        roster: GroupProtocol.State, self: String, selfPid: String?, selfDevice: String,
        participants: List<GroupParticipant>, deafenedByPid: Map<String, Boolean>, speakingPids: Set<String>,
        volumes: Map<String, Float>, muted: Boolean, deafened: Boolean, creator: String?,
        securityLabel: String = GroupCallState.SECURITY_LABEL,
    ): GroupCallUiModel {
        val names = roster.members.associate { it.key to it.name }
        val selfRow = PersonRow(self, names[self] ?: "You", true, true, selfPid != null && selfPid in speakingPids, DEFAULT_VOLUME,
            listOf(DeviceRow(selfPid.orEmpty(), selfDevice, muted, deafened, selfPid != null && selfPid in speakingPids, true)))
        val others = participants.filter { it.pubkey != self }.groupBy { it.pubkey }.map { (key, parts) ->
            val inGroup = key in names
            val devices = parts.sortedBy { it.pid }.map {
                DeviceRow(it.pid, it.device, it.muted, deafenedByPid[it.pid] == true, it.pid in speakingPids, it.sessionId != null)
            }
            PersonRow(key, names[key] ?: "Unknown ${key.take(8)}", inGroup, false, devices.any { it.speaking },
                (volumes[key] ?: DEFAULT_VOLUME), devices)
        }.sortedWith(compareBy<PersonRow>({ !it.inGroup }, { it.name.lowercase() }, { it.pubkey }))
        return GroupCallUiModel(securityLabel, listOf(selfRow) + others, muted, deafened, canEnd = creator == self)
    }
}

/** Speaking indicator from received-audio levels (0..1): an on-threshold plus a hangover so it does not flicker. */
class SpeakingTracker(private val threshold: Double = 0.02, private val hangoverMs: Long = 300) {
    private val lastLoud = HashMap<String, Long>()
    fun update(pid: String, level: Double, nowMs: Long) { if (level >= threshold) lastLoud[pid] = nowMs }
    fun speaking(nowMs: Long): Set<String> = lastLoud.filterValues { nowMs - it <= hangoverMs }.keys.toSet()
    fun retainOnly(pids: Set<String>) { lastLoud.keys.retainAll(pids) }
}

enum class FlowDecision { OK, REPUBLISH }

/** The outbound watchdog's verdict; kept separate so the legacy packet-count path keeps its two outcomes. */
enum class OutboundDecision { OK, REPUBLISH, BACKOFF, FAILED }

/** One reading of this device's sender counters, straight from `outbound-rtp` stats. Either may be absent. */
data class OutboundSample(val packetsSent: Long?, val bytesSent: Long?)

/**
 * Watches our own outbound packet counter. The SFU drops a track that gets no packets for 30 s, and a muted
 * (disabled) track may send only sparse DTX/comfort-noise packets, so a stall of [stallMs] (< 30 s) means the
 * track is about to be, or already was, collected: the host republishes. Any change in the counter counts as
 * progress (a new peer restarts it at zero).
 */
class PacketFlowWatchdog(private val stallMs: Long = 20_000) {
    private var lastPackets: Long? = null
    private var lastProgressAt = 0L
    fun onSample(nowMs: Long, packetsSent: Long): FlowDecision {
        if (lastPackets == null || packetsSent != lastPackets) {
            lastPackets = packetsSent; lastProgressAt = nowMs
            return FlowDecision.OK
        }
        return if (nowMs - lastProgressAt >= stallMs) FlowDecision.REPUBLISH else FlowDecision.OK
    }
    fun reset(nowMs: Long) { lastPackets = null; lastProgressAt = nowMs }
}

/**
 * The real outbound watchdog: samples the sender's `packetsSent`/`bytesSent` once a second and only calls for a
 * republish when the track has made **no** progress for [stallWindowMs]. "No progress" is a small absolute
 * threshold, not zero traffic, because a muted Opus track may still emit a stray comfort-noise packet.
 *
 * Bounded on purpose:
 * - a sample where both counters are unchanged (or absent) is the only thing that can trigger a recovery;
 * - every recovery is followed by [backoffMs] * attempt of silence, so there is no busy loop and no stream of
 *   reconnects;
 * - after [maxRecoveries] attempts the watchdog reports [OutboundDecision.FAILED] once and stops asking: the host
 *   surfaces [lastError] instead of reconnecting forever.
 *
 * [reset] starts a fresh call/session: counters are forgotten, but the failure budget is only cleared by
 * [clearFailures] (a genuinely new publish after the user re-joins), never by the recovery path itself.
 */
class OutboundStallWatchdog(
    private val stallWindowMs: Long = 12_000,
    private val minPackets: Long = 1,
    private val minBytes: Long = 64,
    private val maxRecoveries: Int = 3,
    private val backoffMs: Long = 15_000,
) {
    private var lastPackets: Long? = null
    private var lastBytes: Long? = null
    private var progressAt: Long? = null
    private var attempts = 0
    private var nextAttemptAt = 0L
    private var stopped = false
    var lastError: String? = null; private set

    @Synchronized
    fun onSample(nowMs: Long, packetsSent: Long?, bytesSent: Long?): OutboundDecision {
        if (stopped) return OutboundDecision.FAILED
        if (packetsSent == null && bytesSent == null) return OutboundDecision.OK // no stats: never act blind
        val previousPackets = lastPackets
        val previousBytes = lastBytes
        lastPackets = packetsSent
        lastBytes = bytesSent
        val packetsDelta = if (packetsSent != null && previousPackets != null) packetsSent - previousPackets else 0L
        val bytesDelta = if (bytesSent != null && previousBytes != null) bytesSent - previousBytes else 0L
        // A counter reset (new sender) shows a negative delta: treat it as progress, never as a stall.
        val reset = packetsDelta < 0 || bytesDelta < 0
        val progress = reset || packetsDelta >= minPackets || bytesDelta >= minBytes
        if (progress) {
            progressAt = nowMs
            attempts = 0
            return OutboundDecision.OK
        }
        val since = progressAt ?: nowMs.also { progressAt = it }
        if (nowMs - since < stallWindowMs) return OutboundDecision.OK
        if (nowMs < nextAttemptAt) return OutboundDecision.BACKOFF
        if (attempts >= maxRecoveries) {
            stopped = true
            lastError = "Your audio stopped reaching the call and couldn't be recovered. Rejoin to be heard again."
            return OutboundDecision.FAILED
        }
        attempts++
        nextAttemptAt = nowMs + backoffMs * attempts
        return OutboundDecision.REPUBLISH
    }

    fun reset(nowMs: Long) {
        synchronized(this) {
            lastPackets = null; lastBytes = null
            progressAt = nowMs; attempts = 0; nextAttemptAt = 0
        }
    }

    /** A brand-new publish after a rejoin: forget the failure budget too. */
    fun clearFailures(nowMs: Long) {
        synchronized(this) { stopped = false; lastError = null }
        reset(nowMs)
    }

    /** After a successful republish: the counters belong to the new sender, but the attempt budget stands. */
    fun restartCounters(nowMs: Long) {
        synchronized(this) {
            lastPackets = null; lastBytes = null; progressAt = nowMs; nextAttemptAt = nowMs
        }
    }

    @Synchronized fun attemptsMade(): Int = attempts
    @Synchronized fun exhausted(): Boolean = stopped
}

enum class GroupStartBlock { NONE, UNLINKED, OFFLINE, MIC_DENIED, BUSY, PROFILE_LOCKED, NOT_IN_GROUP, NO_KEY }

/** The honest "no" before a group call starts or is joined: nothing is created on the server when this refuses. */
object GroupCallGuard {
    fun joinBlock(linked: Boolean, online: Boolean, micGranted: Boolean, otherCallLive: Boolean, callsEnabled: Boolean): GroupStartBlock = when {
        !callsEnabled -> GroupStartBlock.PROFILE_LOCKED
        !linked -> GroupStartBlock.UNLINKED
        otherCallLive -> GroupStartBlock.BUSY
        !online -> GroupStartBlock.OFFLINE
        !micGranted -> GroupStartBlock.MIC_DENIED
        else -> GroupStartBlock.NONE
    }

    /** [reachableMembers] counts other members the secret can be sent to end to end (a verified contact or a roster key). */
    fun startBlock(linked: Boolean, online: Boolean, micGranted: Boolean, otherCallLive: Boolean, callsEnabled: Boolean,
        roster: GroupProtocol.State?, self: String, reachableMembers: Int): GroupStartBlock {
        val base = joinBlock(linked, online, micGranted, otherCallLive, callsEnabled)
        if (base != GroupStartBlock.NONE) return base
        if (roster == null || roster.members.none { it.key == self }) return GroupStartBlock.NOT_IN_GROUP
        if (reachableMembers <= 0 || runCatching { GroupCallCodec.rosterHash(roster) }.isFailure) return GroupStartBlock.NO_KEY
        return GroupStartBlock.NONE
    }

    fun message(block: GroupStartBlock): String? = when (block) {
        GroupStartBlock.NONE -> null
        GroupStartBlock.UNLINKED -> "Group calls need the Relay delivery server. This install isn't linked to it."
        GroupStartBlock.OFFLINE -> "No connection. Group calls need the internet."
        GroupStartBlock.MIC_DENIED -> "Microphone access is needed for calls. Allow it in Settings."
        GroupStartBlock.BUSY -> "You're already on a call."
        GroupStartBlock.PROFILE_LOCKED -> "Calls are unavailable while Relay is locked."
        GroupStartBlock.NOT_IN_GROUP -> "You're no longer in this group."
        GroupStartBlock.NO_KEY -> "No one in this group can be reached securely yet, so the call can't be started."
    }
}

/** The body of `POST /v1/call/rooms/<room>/roster`: who to cut off, plus a fresh capability for everyone who stays. */
data class RosterRevocation(val remove: List<String>, val secret: String, val body: String)

object GroupRosterChange {
    /** Members of the call's frozen roster that [next] dropped (never the creator); null when the creator itself left. */
    fun removed(frozenMembers: List<String>, next: GroupProtocol.State, creator: String): List<String>? {
        val still = next.members.map { it.key }.toSet()
        if (creator !in still) return null
        return frozenMembers.distinct().filter { it !in still && it != creator }.sorted()
    }

    fun revocation(remove: List<String>, random: (Int) -> ByteArray): RosterRevocation {
        val sorted = remove.distinct().sorted()
        val bytes = random(32)
        require(bytes.size == 32) { "random provider returned wrong size" }
        val secret = bytes.joinToString("") { "%02x".format(it) }
        val body = JSONObject().put("v", 1).put("remove", JSONArray(sorted)).put("secretHash", GroupCallCodec.secretHash(secret)).toString()
        return RosterRevocation(sorted, secret, body)
    }
}
