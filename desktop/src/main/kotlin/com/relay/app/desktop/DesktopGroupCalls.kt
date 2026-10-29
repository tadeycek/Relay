package com.relay.app.desktop

import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallEffect
import com.relay.app.call.group.GroupCallEngine
import com.relay.app.call.group.GroupCallFenceSnapshot
import com.relay.app.call.group.GroupDecodeResult
import com.relay.app.call.group.GroupEventReplay
import com.relay.app.call.group.GroupParticipant
import com.relay.app.call.group.RosterAttestation
import com.relay.app.call.group.RosterAttestations
import com.relay.app.call.group.VerifiedGroupSender
import com.relay.app.call.group.e2ee.GroupCallKeySchedule
import com.relay.app.call.group.e2ee.GroupCallSecurityLabel
import com.relay.app.call.group.e2ee.ParticipantDevice
import com.relay.app.groups.GroupProtocol
import com.relay.app.transport.SendResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ExecutorGroupCallTimers : GroupCallTimers, AutoCloseable {
    private val pool: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay-group-call-timer").apply { isDaemon = true } }
    override fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable {
        val f = try { pool.schedule({ runCatching(task) }, delayMs, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.RejectedExecutionException) { return AutoCloseable { } }
        return AutoCloseable { f.cancel(false) }
    }
    override fun close() { pool.shutdownNow() }
}

/**
 * WP16 desktop group-call controller. It owns one [GroupCallEngine] per group, feeds it only authenticated, roster-checked
 * announcements, persists the engine snapshot with every accepted event, and drives the Worker's CallRoom socket and the
 * native media ([GroupMediaPort]) from the engine's effects.
 *
 * Everything runs on ONE serial thread. Public commands block until applied (call them off the UI thread).
 * Honest limits: the join secret and roster are server-visible metadata (LP-D11/12 as amended by G5); calls are encrypted in
 * transit UNLESS every participant device proves possession of the current WP17 epoch key (see [GroupCallE2ee]); `confirmedRoom`
 * is called only after an authenticated room join plus a matching live-state readback.
 */
class DesktopGroupCalls(
    private val selfPubkey: String,
    private val deviceId: String,
    private val groupByConversation: (String) -> StoredGroup?,
    private val groupBySharedId: (owner: String, id: String) -> StoredGroup?,
    private val states: GroupCallStateStore,
    private val api: GroupCallRoomApi?,
    private val announce: suspend (peer: String, body: String) -> SendResult,
    private val online: () -> Boolean,
    private val makeMedia: () -> GroupMediaPort,
    private val nameOf: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: (Int) -> ByteArray = SecureRandom().let { rng -> { n -> ByteArray(n).also(rng::nextBytes) } },
    /** Signs the owner's roster attestation for a live advance; null means this computer cannot advance (fails closed). */
    private val signRoster: ((ByteArray) -> ByteArray?)? = null,
    private val timers: GroupCallTimers = ExecutorGroupCallTimers(),
    private val reconnectGraceMs: Long = 30_000,
    private val rejoinWindowMs: Long = 120_000,
    var onChange: () -> Unit = {},
) : AutoCloseable {

    private companion object {
        const val SPEAKING = 0.02f
        const val MAX_PARTICIPANTS = 17
        val BACKOFF = longArrayOf(1_000, 2_000, 4_000)
    }

    private class Slot(
        var roster: GroupProtocol.State, var engine: GroupCallEngine, var committed: GroupCallFenceSnapshot,
        var inviteCommitted: StoredGroupCall?,
        /** A newer roster from the phone, held back until the owner's signed advance re-binds the live room. */
        var pending: GroupProtocol.State? = null,
    )
    private class Invite(val a: GroupAnnouncement, val rejoin: Boolean)
    private class Peer(val pid: String, val pubkey: String, val device: String, val sessionId: String?, val track: String?, val muted: Boolean, val deafened: Boolean)
    private class PendingWinner(val room: String?, val until: Long)
    private class RejoinHint(val creator: String, val until: Long)
    private class Session(val conv: String, var a: GroupAnnouncement, val creator: Boolean) {
        var socket: RoomSocket? = null
        var gen = 0
        var pid: String? = null
        var welcomed = false
        var media: GroupMediaPort? = null
        val peers = LinkedHashMap<String, Peer>()
        var droppedAt: Long? = null
        var retry: AutoCloseable? = null
        var attempts = 0
        var muted = false
        var deafened = false
        val volumes = HashMap<String, Float>()
        var levels: Map<String, Float> = emptyMap()
        val warned = HashSet<String>()
        var ending = false
        var connectedAt: Long? = null
        var heartbeat: AutoCloseable? = null
        /** WP17 frame E2EE for this call; null when the verified announcement carried no usable key (fail closed). */
        var e2ee: GroupCallE2ee? = null
        /** Why the microphone and speaker are off because encryption is unavailable; shown in the call panel. */
        var securityError: String? = null
        var sendPathEncrypted = false
    }

    private val serial = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-group-call").apply { isDaemon = true } }
    private val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
    /** Only source of call keys. Never the injected [random] seam (tests make that deterministic on purpose). */
    private val secureRandom = SecureRandom()
    /** This computer's frame-E2EE identity; null when the device id is not encodable, which disables E2EE entirely. */
    private val selfDevice: ParticipantDevice? = GroupCallE2ee.selfDevice(selfPubkey, deviceId)
    @Volatile private var closed = false

    private val slots = HashMap<String, Slot>()
    private val invites = HashMap<String, Invite>()
    private val pendingWinner = HashMap<String, PendingWinner>()
    private val rejoinHints = HashMap<String, RejoinHint>()
    private var current: Session? = null
    private var stage = GroupCallStage.IDLE
    private var notice: String? = null
    private var tickTimer: AutoCloseable? = null

    private val _state = MutableStateFlow(DesktopGroupCallUi())
    val state: StateFlow<DesktopGroupCallUi> = _state.asStateFlow()

    init { run { restoreInvitations(); scheduleTick() } }

    // ---- serial plumbing -------------------------------------------------------------------

    private fun <T> run(block: () -> T): T {
        if (Thread.currentThread() === worker.get()) return block()
        check(!closed) { "Group calls are shut down" }
        val f = try { serial.submit<T> { worker.set(Thread.currentThread()); block() } }
        catch (e: java.util.concurrent.RejectedExecutionException) { throw IllegalStateException("Group calls are shut down", e) }
        try { return f.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
    }
    private fun post(block: () -> Unit) {
        if (closed) return
        try { serial.execute { worker.set(Thread.currentThread()); runCatching(block) } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }
    /** Test/UI barrier: returns after everything queued so far has run. */
    fun sync() { if (!closed) run { } }

    private fun scheduleTick() {
        tickTimer?.close()
        tickTimer = timers.schedule(1_000) { post { tick(); scheduleTick() } }
    }

    // ---- engines ---------------------------------------------------------------------------

    private fun slotFor(conv: String, group: StoredGroup? = groupByConversation(conv)): Slot? {
        group ?: return null
        val state = group.protocolState()
        slots[conv]?.let { slot -> refreshRoster(conv, slot, state); return slot }
        if (group.hidden || state.members.none { it.key == selfPubkey }) return null
        val snap = runCatching { states.snapshot(conv) }.getOrNull()
        val engine = try { GroupCallEngine(state, selfPubkey, clock, snap); } catch (_: Exception) { return null }
        runCatching { GroupCallCodec.rosterHash(state) }.getOrNull() ?: return null
        val storedInvite = runCatching { states.invitations()[conv] }.getOrNull()
        return Slot(state, engine, engine.snapshot(), storedInvite).also { slots[conv] = it }
    }

    /** A newer stored roster (mirrored from the phone) reaches the engine before any event is judged against it. */
    private fun refreshRoster(conv: String, slot: Slot, next: GroupProtocol.State) {
        if (next.id != slot.roster.id || next.owner != slot.roster.owner) return
        val changed = next.revision > slot.roster.revision ||
            (next.revision == slot.roster.revision && next.members.map { it.key } != slot.roster.members.map { it.key })
        if (!changed || next.revision < slot.roster.revision) return
        val hash = runCatching { GroupCallCodec.rosterHash(next) }.getOrNull() ?: return
        if (next.revision == slot.roster.revision && hash != runCatching { GroupCallCodec.rosterHash(slot.roster) }.getOrNull()) return
        val live = current?.takeIf { it.conv == conv }
        if (live != null && next.revision > slot.roster.revision) {
            // Keep the frozen revision: only the owner's signed advance may re-bind the live room. A self removal
            // still closes it right away (the engine sees the new set and cannot stay).
            slot.pending = next
            handle(conv, slot.engine.updateRoster(next))
            persist(conv, slot, strict = false)
            return
        }
        slot.pending = null
        slot.roster = next
        handle(conv, slot.engine.updateRoster(next))
        persist(conv, slot, strict = false)
    }

    /**
     * Writes the fence snapshot and the current joinable announcement as one durable operation. It is called after every
     * accepted event, before any effect runs, so an effect is never observed for state this computer did not save.
     */
    private fun persist(conv: String, slot: Slot, strict: Boolean) {
        val snap = slot.engine.snapshot()
        val invite = invites[conv]?.let { StoredGroupCall(it.a, it.rejoin) }
        if (snap == slot.committed && invite == slot.inviteCommitted) return
        try { states.commit(conv, snap, invite); slot.committed = snap; slot.inviteCommitted = invite }
        catch (e: Exception) { if (strict) throw e else notice = "Couldn't save the call state on this computer." }
    }

    /**
     * A restart loses the socket and the natives, but a call that was still inside its ten-minute invitation can be
     * joined again. The saved announcement is re-validated against the CURRENT roster (and the ended-room fence) before
     * it becomes a banner; the room is then re-checked with the server before the banner is trusted. The join secret is
     * read from the encrypted store only and is never part of the UI state.
     */
    private fun restoreInvitations() {
        val stored = runCatching { states.invitations() }.getOrNull() ?: return
        for ((conv, saved) in stored) {
            val a = saved.announcement
            val slot = slotFor(conv) ?: continue
            fun drop() {
                runCatching { states.commit(conv, slot.engine.snapshot(), null) }
                slot.inviteCommitted = null
            }
            if (a.op != "group_start" || a.joinSecret == null || clock() >= a.expires) { drop(); continue }
            if ((a.room to a.caller) in slot.engine.snapshot().ended) { drop(); continue }
            val sender = runCatching { VerifiedGroupSender.fromAuthenticatedSeal(a.caller) }.getOrNull()
            if (sender == null) { drop(); continue }
            val decoded = runCatching {
                GroupCallCodec.decode(GroupCallCodec.encode(a), sender, slot.roster, clock(), GroupEventReplay())
            }.getOrNull()
            if (decoded !is GroupDecodeResult.Accepted) { drop(); continue }
            // Re-open the engine at exactly the persisted fence with only this already-authenticated event admitted again;
            // it is put back into the replay cache by apply(), so a redelivered copy still stays dead.
            val snap = slot.engine.snapshot()
            val reopened = snap.copy(replayed = snap.replayed - a.eventId, activeRoom = null)
            val engine = runCatching { GroupCallEngine(slot.roster, selfPubkey, clock, reopened) }.getOrNull()
            if (engine == null) { drop(); continue }
            val effects = runCatching { engine.apply(a, sender) }.getOrDefault(emptyList())
            if (effects.none { it is GroupCallEffect.OpenRoom && it.room == a.room }) { drop(); continue }
            slot.engine = engine
            slot.committed = reopened
            invites[conv] = Invite(a, saved.rejoin)
            persist(conv, slot, strict = false)
            verifyLater(conv, a)
        }
        if (invites.isNotEmpty()) publish()
    }

    /** Rebuilds the engine as it was before a rejected (uncommitted) event, keeping any live call alive. */
    private fun rollback(conv: String, slot: Slot, before: GroupCallFenceSnapshot) {
        val old = invites[conv]?.takeIf { !it.rejoin }?.a
        val snap = before.copy(replayed = before.replayed - (old?.eventId ?: ""), activeRoom = null)
        val engine = GroupCallEngine(slot.roster, selfPubkey, clock, snap)
        if (old != null) {
            engine.apply(old, VerifiedGroupSender.fromAuthenticatedSeal(old.caller))
            val s = current?.takeIf { it.conv == conv }
            if (s != null && s.welcomed) {
                engine.updateParticipants(s.peers.values.map { it.toParticipant() })
                engine.confirmedRoom(old.room)
                if (s.muted) engine.mute(true); if (s.deafened) engine.deafen(true)
            }
        }
        slot.engine = engine
        slot.committed = before
    }

    private fun Peer.toParticipant() = GroupParticipant(pid, pubkey, device, sessionId, track, muted)

    // ---- WP17 frame E2EE --------------------------------------------------------------------

    /** sessionId → participant device for every peer that publishes (the provider needs it to attribute a stream). */
    private fun Session.deviceMapping(): List<Triple<String, String, String>> =
        peers.values.mapNotNull { p -> p.sessionId?.let { Triple(it, p.pubkey, p.device) } }

    /**
     * Builds this call's E2EE runtime from the **verified** announcement only. The starter's own `group_start` was accepted by
     * the engine from this identity, and a joiner's came out of `GroupCallCodec.decode` behind an opened E2E seal; either way
     * `callKey`/`keyEpoch` have never been near the server. A missing key returns null and the call runs muted.
     */
    private fun startE2ee(s: Session): GroupCallE2ee? {
        val me = selfDevice ?: return null
        val e2ee = GroupCallE2ee.fromAnnouncement(s.a, me) ?: return null
        s.applyE2eeRoster(e2ee)
        return e2ee
    }

    /**
     * Feeds the room roster into the runtime: every participant device becomes label coverage, and every peer with a room
     * session id becomes a stream attribution. Returns true when anything changed.
     */
    private fun Session.applyE2eeRoster(e2ee: GroupCallE2ee): Boolean {
        val devices = peers.values.mapNotNull { p -> runCatching { ParticipantDevice(p.pubkey, p.device) }.getOrNull() }
        val participantsChanged = e2ee.setParticipants(devices)
        val sessionsChanged = e2ee.setSessionDevices(deviceMapping())
        return participantsChanged || sessionsChanged
    }

    /**
     * A joiner or the owner adopts an advanced `group_start`: the same room, the new frozen revision, the rotated
     * join capability and the next key epoch. The old epoch runtime is disposed (its keys are wiped) and the new
     * one is built from the verified announcement; the removed device is not in its participant set.
     */
    private fun adoptAdvance(conv: String, slot: Slot, a: GroupAnnouncement) {
        val s = current?.takeIf { it.conv == conv } ?: return
        s.a = a
        val old = s.e2ee
        val next = startE2ee(s)
        old?.close()
        s.e2ee = next
        s.securityError = if (next == null) GroupCallE2ee.NO_KEY_MESSAGE else null
        if (next != null) {
            s.media?.setFrameE2ee(next.provider()) { sender, epoch -> post { onE2eeVerified(s, next, sender, epoch) } }
            runCatching { s.media?.publish() }
            s.sendPathEncrypted = true
        } else {
            s.media?.setFrameE2ee(null, null)
            s.media?.setMicMuted(true)
            s.sendPathEncrypted = false
        }
    }

    /** Re-binds the provider after a peer joins/leaves: the device set is part of what the label covers. */
    private fun refreshE2ee(s: Session) {
        val e2ee = s.e2ee ?: return
        if (!s.applyE2eeRoster(e2ee)) return
        val provider = e2ee.provider() ?: return
        s.media?.setFrameE2ee(provider) { sender, epoch -> post { onE2eeVerified(s, e2ee, sender, epoch) } }
    }

    /** A frame (or this host's own installed send path) proved possession of the current epoch key; re-render the label. */
    private fun onE2eeVerified(s: Session, e2ee: GroupCallE2ee, sender: ParticipantDevice, epoch: Int) {
        if (current !== s || s.e2ee !== e2ee) return
        if (e2ee.verify(sender, epoch)) publish()
    }

    // ---- announcement ingress (1) -----------------------------------------------------------

    /**
     * [authenticatedSender] MUST be the sender of the opened NIP-17 seal (never a field of [body]); this method builds the
     * [VerifiedGroupSender] from it and nothing else. Returns true when [body] was a group announcement (valid or not, so it
     * never reaches the 1:1 call codec or the chat), false for anything else. Throws only when the durable commit failed, so
     * the stored packet is retried and the event is not burnt.
     */
    fun ingress(authenticatedSender: String, body: String): Boolean = run {
        val json = GroupCallWire.unframe(body) ?: return@run false
        val ids = try { JSONObject(json) } catch (_: Exception) { return@run true }
        val owner = ids.opt("owner") as? String ?: return@run true
        val groupId = ids.opt("group") as? String ?: return@run true
        val group = groupBySharedId(owner, groupId) ?: return@run true
        val conv = group.conversationId
        val slot = slotFor(conv, group) ?: return@run true
        val sender = try { VerifiedGroupSender.fromAuthenticatedSeal(authenticatedSender) } catch (_: IllegalArgumentException) { return@run true }
        // The codec validates the wire, the roster binding and the sender; its replay cache here is scratch. The engine's
        // cache is the durable one, so the fence is judged (and persisted) once. A strictly newer revision is only
        // acceptable through the explicit advance rule, against the pending roster mirrored from the phone.
        val decoded = GroupCallCodec.decode(json, sender, slot.roster, clock(), GroupEventReplay())
        val pending = slot.pending
        val decodedAdvance = if (decoded is GroupDecodeResult.Rejected && decoded.reason == com.relay.app.call.group.GroupReject.STALE_ROSTER &&
            pending != null && pending.revision > slot.roster.revision) {
            GroupCallCodec.decodeAdvance(json, sender, slot.roster, pending, clock(), GroupEventReplay())
        } else decoded
        val a = (decodedAdvance as? GroupDecodeResult.Accepted)?.announcement ?: return@run true
        val advancing = pending != null && a.revision == pending.revision
        val before = slot.engine.snapshot()
        val previousInvite = invites[conv]
        val effects = if (advancing) slot.engine.advance(pending!!, a, sender) else slot.engine.apply(a, sender)
        val rebound = advancing && effects.any { it is GroupCallEffect.RebindRoom }
        if (rebound) {
            slot.roster = pending!!
            slot.pending = null
            invites[conv] = Invite(a, rejoin = false)
        } else if (a.isStart() && effects.any { it is GroupCallEffect.OpenRoom && it.room == a.room }) {
            invites[conv] = Invite(a, rejoin = false)
        }
        if (!a.isStart()) invites[conv]?.takeIf { it.a.room == a.room && it.a.caller == a.caller }?.let { invites.remove(conv) }
        // The fence and the joinable invitation become durable together, before any effect (banner, media) is published.
        try { persist(conv, slot, strict = true) } catch (e: Exception) {
            if (previousInvite != null) invites[conv] = previousInvite else invites.remove(conv)
            rollback(conv, slot, before)
            throw IllegalStateException("Group call state was not saved", e)
        }
        if (rebound) adoptAdvance(conv, slot, a)
        if (a.isStart() && invites[conv]?.a?.room == a.room) verifyLater(conv, a)
        closeNotice = if (a.isStart()) null else "The call ended."
        handle(conv, effects)
        closeNotice = null
        if (a.isStart() && invites[conv]?.a?.room == a.room) maybeAutoJoin(conv, a)
        publish()
        true
    }

    private fun maybeAutoJoin(conv: String, a: GroupAnnouncement) {
        if (current != null || api == null || !online()) return
        val winner = pendingWinner[conv]
        val hint = rejoinHints[conv]
        val auto = (winner != null && winner.room == a.room && clock() < winner.until) ||
            (hint != null && hint.creator == a.caller && clock() < hint.until)
        if (!auto) return
        pendingWinner.remove(conv); rejoinHints.remove(conv)
        joinInternal(conv)
    }

    /** Live-state readback for a fresh banner: a gone room or a different roster binding clears it; an unreachable server does not. */
    private fun verifyLater(conv: String, a: GroupAnnouncement) {
        val client = api ?: return
        post {
            val outcome = runCatching { client.info(a.room) }.getOrDefault(RoomInfoOutcome.Unknown)
            val bad = when (outcome) {
                RoomInfoOutcome.Gone -> true
                is RoomInfoOutcome.Live -> !outcome.rostered || outcome.revision != a.revision || outcome.rosterHash != a.rosterHash
                RoomInfoOutcome.Unknown -> false
            }
            val inv = invites[conv]
            if (bad && inv != null && inv.a.room == a.room && current?.conv != conv) {
                slots[conv]?.let { slot -> handle(conv, slot.engine.roomStateUnknown()); persist(conv, slot, strict = false) }
                invites.remove(conv)
                slots[conv]?.let { persist(conv, it, strict = false) }
                publish()
            }
        }
    }

    fun tick() = run {
        val t = clock()
        for ((conv, slot) in slots.toMap()) {
            handle(conv, slot.engine.tick()); persist(conv, slot, strict = false)
            val invite = invites[conv] ?: continue
            if ((invite.rejoin && t >= invite.a.expires) || (!invite.rejoin && slot.engine.state.room == null)) {
                invites.remove(conv)
                persist(conv, slot, strict = false)
            }
        }
        pendingWinner.values.removeAll { t >= it.until }; rejoinHints.values.removeAll { t >= it.until }
        current?.let { s -> s.levels = runCatching { s.media?.levels() }.getOrNull() ?: emptyMap() }
        publish()
    }

    // ---- effects ---------------------------------------------------------------------------

    private fun handle(conv: String, effects: List<GroupCallEffect>) {
        val s = current?.takeIf { it.conv == conv }
        for (e in effects) when (e) {
            is GroupCallEffect.OpenRoom -> Unit // known now; joining is the user's action (or the auto-join rules)
            GroupCallEffect.CloseRoom -> {
                val inv = invites.remove(conv)
                if (s != null && current === s) endSession(s, closeNotice ?: "The call was closed.", sendLeave = true, engineClosed = true)
                else if (inv != null) publish()
            }
            // Fail closed: without a usable key nothing is played, so no remote track is ever decoded into a sink.
            is GroupCallEffect.RebindRoom -> Unit // the session adopts the advanced announcement itself
            is GroupCallEffect.Subscribe -> if (s != null && s.e2ee != null) s.media?.subscribe(e.ref)
            is GroupCallEffect.Unsubscribe -> s?.media?.unsubscribe(e.ref)
            is GroupCallEffect.WarnUnknown -> s?.warned?.add(e.pid)
            is GroupCallEffect.SetMicrophoneMuted -> s?.media?.setMicMuted(e.muted)
            is GroupCallEffect.SetPlaybackDeafened -> s?.media?.setDeafened(e.deafened)
        }
    }
    private var closeNotice: String? = null

    /** Closes the engine's room with [why] as the notice the resulting teardown shows. [unknown] = failed readback (fail closed). */
    private fun closeEngine(conv: String, why: String, unknown: Boolean = false) {
        val slot = slots[conv] ?: return
        closeNotice = why
        try { handle(conv, if (unknown) slot.engine.roomStateUnknown() else slot.engine.leave()) } finally { closeNotice = null }
        persist(conv, slot, strict = false)
    }

    // ---- starting (2) ----------------------------------------------------------------------

    /** Null = started (and the room joined); otherwise the honest reason it was refused. */
    fun start(conversationId: String): String? = run { startInternal(conversationId) }

    private fun refusal(): String? = when {
        closed -> "Group calls are shutting down."
        api == null -> "Link this computer to your phone before starting a group call."
        !online() -> "You're offline. Group calls need a connection to the mailbox."
        current != null -> "You're already in a group call."
        else -> null
    }

    /**
     * [rotateEpoch] is set only when the creator is replacing a call after a roster removal: the new `group_start` then carries
     * that epoch and a brand-new call key, so the removed member (who knows the old key) cannot derive the new frames.
     */
    private fun startInternal(conv: String, rotateEpoch: Int? = null): String? {
        refusal()?.let { return it }
        val client = api!!
        val group = groupByConversation(conv) ?: return "This group is not on this computer."
        if (group.hidden || group.members.none { it.key == selfPubkey }) return "You are no longer a member of this group."
        if (invites[conv] != null) return "A call is already live in this group. Join it from the banner."
        val state = group.protocolState()
        val members = state.members.map { it.key }.distinct().sorted()
        if (members.size > MAX_PARTICIPANTS) return "Group calls support at most $MAX_PARTICIPANTS people."
        val slot = slotFor(conv, group) ?: return "This group's roster can't be used for a call."
        val epoch = rotateEpoch ?: 0
        if (epoch !in 0..GroupCallKeySchedule.MAX_EPOCH) return "This call has rotated its keys too often; start a new call."
        if (selfDevice == null) return "This device's id can't be used for an encrypted group call."
        notice = null
        // The call key exists only here (SecureRandom), inside the sealed announcement and in this call's memory. It is never
        // part of the room record, a REST body or a log line.
        val callKey = ByteArray(GroupCallKeySchedule.CALL_KEY_BYTES).also(secureRandom::nextBytes)
        val a = try {
            GroupCallCodec.newStart(state, selfPubkey, deviceId, clock(), random, keyEpoch = epoch.toLong(), callKey = callKey)
        } catch (_: Exception) {
            callKey.fill(0)
            return "This group's roster can't be used for a call."
        } finally {
            callKey.fill(0) // the encoded announcement owns the only copy now
        }
        stage = GroupCallStage.STARTING; publish()
        val outcome = client.create(CreateRoomRequest(a.room, GroupCallCodec.secretHash(a.joinSecret!!), state.id, state.revision, a.rosterHash, members))
        val refused: String? = when (outcome) {
            CreateRoomOutcome.Created -> null
            is CreateRoomOutcome.GroupRoomExists -> {
                pendingWinner[conv] = PendingWinner(outcome.room, clock() + 60_000)
                "Another member already started a call in this group. You'll join it as soon as their invitation arrives."
            }
            CreateRoomOutcome.StaleRevision -> "This group changed on another device. Wait for the roster to sync from your phone, then try again."
            CreateRoomOutcome.NotInRoster -> "You're not on this call's roster, so the server refused it."
            is CreateRoomOutcome.Refused -> "The server refused the call (${outcome.code ?: outcome.status})."
            CreateRoomOutcome.Unreachable -> "Couldn't reach the server to start the call."
        }
        if (refused != null) { stage = GroupCallStage.IDLE; publish(); return refused }
        val me = VerifiedGroupSender.fromAuthenticatedSeal(selfPubkey)
        val before = slot.engine.snapshot()
        val effects = slot.engine.apply(a, me)
        invites[conv] = Invite(a, rejoin = false)
        try { persist(conv, slot, strict = true) } catch (_: Exception) {
            invites.remove(conv)
            rollback(conv, slot, before); stage = GroupCallStage.IDLE; publish()
            return "Couldn't save the call state on this computer."
        }
        handle(conv, effects)
        // The room and this computer's fence are durable: only now tell the group (so a failed local save can never have
        // sent an invitation this computer cannot honour).
        val frame = GroupCallWire.frame(a)
        var failed = 0
        for (peer in members) if (peer != selfPubkey) {
            val sent = runCatching { runBlocking { announce(peer, frame) } }.getOrNull()
            if (sent !is SendResult.Sent) failed++
        }
        if (failed > 0) notice = "Couldn't announce the call to $failed member${if (failed == 1) "" else "s"}; they can't join yet."
        return joinInternal(conv, creator = true)
    }

    // ---- joining (2, 3) --------------------------------------------------------------------

    /** Null = joining; otherwise the honest reason it was refused. */
    fun join(conversationId: String): String? = run { joinInternal(conversationId) }

    private fun joinInternal(conv: String, creator: Boolean = false): String? {
        if (!creator) refusal()?.let { return it }
        val client = api ?: return "Link this computer to your phone before joining a group call."
        var invite = invites[conv] ?: return "There is no live call to join in this group."
        val slot = slots[conv] ?: slotFor(conv) ?: return "This group is not on this computer."
        if (invite.rejoin) {
            // The user left this call earlier; the engine fenced it. An explicit user rejoin of a still-unexpired call
            // reopens it on a fresh engine without that one fence (core has no "reopen after leave" API).
            if (clock() >= invite.a.expires) { invites.remove(conv); publish(); return "That call has ended." }
            val snap = slot.engine.snapshot()
            val reopened = snap.copy(ended = snap.ended - (invite.a.room to invite.a.caller), replayed = snap.replayed - invite.a.eventId, activeRoom = null)
            slot.engine = GroupCallEngine(slot.roster, selfPubkey, clock, reopened)
            val effects = slot.engine.apply(invite.a, VerifiedGroupSender.fromAuthenticatedSeal(invite.a.caller))
            if (effects.none { it is GroupCallEffect.OpenRoom }) { invites.remove(conv); publish(); return "That call can't be rejoined." }
            invite = Invite(invite.a, rejoin = false); invites[conv] = invite
            runCatching { persist(conv, slot, strict = true) }
        }
        val s = Session(conv, invite.a, creator = creator || invite.a.caller == selfPubkey)
        current = s
        stage = GroupCallStage.JOINING; if (!creator) notice = null
        connect(s)
        publish()
        return null
    }

    private fun connect(s: Session) {
        val client = api ?: return
        val gen = ++s.gen
        val listener = object : RoomListener {
            override fun onOpen() = post { if (current === s && s.gen == gen) Unit }
            override fun onText(text: String) = post { if (current === s && s.gen == gen) onFrame(s, text) }
            override fun onClosed(code: Int, reason: String) = post { if (current === s && s.gen == gen && !s.ending) onSocketClosed(s, code) }
            override fun onFailure(httpStatus: Int?, code: String?) = post { if (current === s && s.gen == gen && !s.ending) onSocketFailed(s, httpStatus, code) }
        }
        s.socket = try { client.connect(s.a.room, s.a.joinSecret!!, s.pid, listener) } catch (_: Exception) { null }
        if (s.socket == null) onSocketFailed(s, null, null)
    }

    private fun onFrame(s: Session, text: String) {
        val o = try { JSONObject(text) } catch (_: Exception) { return }
        when (o.opt("type") as? String) {
            "welcome" -> onWelcome(s, o)
            "joined" -> peerFrom(o)?.let {
                if (it.pid != s.pid) { s.peers[it.pid] = it; pushParticipants(s); refreshE2ee(s) }
            }
            "left" -> (o.opt("pid") as? String)?.let {
                if (s.peers.remove(it) != null) { s.warned.remove(it); pushParticipants(s); refreshE2ee(s) }
            }
            "state" -> (o.opt("pid") as? String)?.let { pid ->
                s.peers[pid]?.let { p -> s.peers[pid] = Peer(p.pid, p.pubkey, p.device, p.sessionId, p.track, o.optBoolean("muted", p.muted), o.optBoolean("deafened", p.deafened)) }
            }
            "end" -> {
                val superseded = o.opt("reason") == "superseded"
                val why = if (superseded) "The call moved to a new room. Waiting for the host's new invitation." else "The call ended."
                closeEngine(s.conv, why)
                invites.remove(s.conv)
                slots[s.conv]?.let { persist(s.conv, it, strict = false) }
                if (current === s) endSession(s, why, sendLeave = false, engineClosed = true)
                if (superseded) rejoinHints[s.conv] = RejoinHint(s.a.caller, clock() + rejoinWindowMs)
            }
        }
        publish()
    }

    private fun peerFrom(o: JSONObject): Peer? {
        val pid = o.opt("pid") as? String ?: return null
        return Peer(pid, o.opt("pubkey") as? String ?: return null, o.opt("device") as? String ?: return null,
            o.opt("sessionId") as? String, o.opt("track") as? String, o.optBoolean("muted"), o.optBoolean("deafened"))
    }

    private fun pushParticipants(s: Session) {
        val slot = slots[s.conv] ?: return
        handle(s.conv, slot.engine.updateParticipants(s.peers.values.map { it.toParticipant() }))
        persist(s.conv, slot, strict = false)
    }

    private fun onWelcome(s: Session, o: JSONObject) {
        val you = o.opt("you") as? String ?: return
        s.pid = you
        s.peers.clear()
        val arr = o.optJSONArray("participants")
        if (arr != null) for (i in 0 until minOf(arr.length(), 64)) arr.optJSONObject(i)?.let { peerFrom(it) }?.let { if (it.pid != you) s.peers[it.pid] = it }
        val first = !s.welcomed
        s.welcomed = true
        s.droppedAt = null; s.attempts = 0; s.retry?.close(); s.retry = null
        startHeartbeat(s)
        val slot = slots[s.conv]
        if (slot == null) { endSession(s, "This call is no longer available.", sendLeave = true, engineClosed = false); return }
        if (first) {
            // Authenticated join done; now the live-state readback of exactly this room before anything is trusted.
            val outcome = runCatching { api!!.info(s.a.room) }.getOrDefault(RoomInfoOutcome.Unknown)
            val problem = when (outcome) {
                is RoomInfoOutcome.Live -> if (!outcome.rostered || outcome.revision != s.a.revision || outcome.rosterHash != s.a.rosterHash)
                    "This call doesn't match this group's roster, so it was not joined." else null
                RoomInfoOutcome.Gone -> "That call has ended."
                RoomInfoOutcome.Unknown -> "Couldn't verify the call with the server, so it was not joined."
            }
            if (problem != null) {
                closeEngine(s.conv, problem, unknown = true)
                invites.remove(s.conv)
                endSession(s, problem, sendLeave = true, engineClosed = true)
                return
            }
            val pre = slot.engine.updateParticipants(s.peers.values.map { it.toParticipant() })
            val confirm = slot.engine.confirmedRoom(s.a.room)
            val media = makeMedia()
            s.media = media
            // WP17: the key comes only from the verified group_start this call was joined through.
            val e2ee = startE2ee(s)
            s.e2ee = e2ee
            s.securityError = if (e2ee == null) GroupCallE2ee.NO_KEY_MESSAGE else null
            try {
                media.open(api!!.sfu(s.a.room)) { ev -> post { if (current === s && s.media === media && ev is GroupMediaEvent.Failed) endSession(s, "Audio connection failed (${ev.reason}).", sendLeave = true, engineClosed = false) } }
                if (e2ee != null) {
                    media.setFrameE2ee(e2ee.provider()) { sender, epoch -> post { onE2eeVerified(s, e2ee, sender, epoch) } }
                    media.publish()
                } else {
                    // No key: the port must drop everything and the microphone stays off. Never a plaintext call.
                    media.setFrameE2ee(null, null)
                    media.setMicMuted(true)
                }
            } catch (e: Exception) {
                endSession(s, "Couldn't start audio on this computer.", sendLeave = true, engineClosed = false); return
            }
            handle(s.conv, pre + confirm)
            // Intent recorded here; publish() still requires the port to report a live sealing path before the label moves.
            s.sendPathEncrypted = e2ee != null
            s.connectedAt = clock()
            stage = GroupCallStage.ACTIVE
            persist(s.conv, slot, strict = false)
        } else {
            handle(s.conv, slot.engine.updateParticipants(s.peers.values.map { it.toParticipant() }))
            handle(s.conv, slot.engine.tick())
            refreshE2ee(s)
            stage = GroupCallStage.ACTIVE
            if (s.muted || s.deafened) s.socket?.send(stateFrame(s))
        }
    }

    private fun stateFrame(s: Session) = JSONObject().put("type", "state").put("muted", s.muted).put("deafened", s.deafened).toString()

    /**
     * The room refreshes a hibernatable attachment only on an incoming frame; a silent socket can be evicted. One
     * heartbeat every 15 s sits inside the server's 12 s recheck cadence and is cheap (no row write).
     */
    private fun startHeartbeat(s: Session) {
        s.heartbeat?.close()
        fun scheduleBeat() {
            s.heartbeat = timers.schedule(15_000) {
                post {
                    if (current === s && !s.ending && s.socket?.send("""{"type":"heartbeat"}""") == true) scheduleBeat()
                }
            }
        }
        scheduleBeat()
    }

    // ---- socket loss and reconnect ----------------------------------------------------------

    private fun onSocketClosed(s: Session, code: Int) {
        if (code == 4009) {
            // removed / revoked: access is gone. No automatic reconnect, and no rejoin banner either.
            val why = "You were removed from this call, or this device's access was revoked."
            closeEngine(s.conv, why)
            invites.remove(s.conv)
            endSession(s, why, sendLeave = false, engineClosed = true)
            return
        }
        beginReconnect(s)
    }

    private fun onSocketFailed(s: Session, status: Int?, code: String?) {
        if (!s.welcomed && s.droppedAt == null) {
            val text = when {
                status == 403 && code == "not_in_roster" -> "You're not on this call's roster."
                status == 403 -> "The server refused to let this device join the call."
                status == 404 -> "That call has ended."
                status == 409 -> "This call is full."
                status == 401 -> "This device isn't authorised for group calls. Link it again."
                else -> "Couldn't join the call. Check your connection and try again."
            }
            if (status == 404 || status == 403) { closeEngine(s.conv, text, unknown = true); invites.remove(s.conv) }
            endSession(s, text, sendLeave = false, engineClosed = status == 404 || status == 403)
            return
        }
        if (status == 404 || status == 403 || status == 401) {
            invites.remove(s.conv)
            endSession(s, if (status == 404) "The call ended while you were disconnected." else "The server no longer lets this device in the call.", sendLeave = false, engineClosed = false)
            return
        }
        beginReconnect(s)
    }

    private fun beginReconnect(s: Session) {
        if (s.droppedAt == null) s.droppedAt = clock()
        s.heartbeat?.close(); s.heartbeat = null
        stage = GroupCallStage.RECONNECTING
        publish()
        scheduleRetry(s)
    }

    private fun scheduleRetry(s: Session) {
        s.retry?.close()
        val delay = BACKOFF[minOf(s.attempts, BACKOFF.size - 1)]
        s.retry = timers.schedule(delay) { post { retry(s) } }
    }

    private fun retry(s: Session) {
        if (current !== s || s.ending) return
        if (clock() - (s.droppedAt ?: clock()) >= reconnectGraceMs) {
            endSession(s, "Lost the connection to the call.", sendLeave = false, engineClosed = false); return
        }
        s.attempts++
        runCatching { s.socket?.close(1000, "retry") }
        connect(s)
        scheduleRetry(s)
        publish()
    }

    // ---- leaving / ending --------------------------------------------------------------------

    fun leave() = run { current?.let { s -> endSession(s, null, sendLeave = true, engineClosed = false, keepRejoin = true) } }

    /** Creator: ends the room for everyone and announces group_end. Anyone else: just leaves. */
    fun endCall() = run {
        val s = current ?: return@run
        if (!s.creator) { endSession(s, null, sendLeave = true, engineClosed = false, keepRejoin = true); return@run }
        val client = api
        val ok = client?.let { runCatching { it.end(s.a.room) }.getOrDefault(false) } == true
        val end = runCatching { GroupCallCodec.newEnd(s.a, clock(), random) }.getOrNull()
        if (end != null) {
            val frame = GroupCallWire.frame(end)
            slots[s.conv]?.roster?.members?.map { it.key }?.filter { it != selfPubkey }?.sorted()?.forEach { peer ->
                runCatching { runBlocking { announce(peer, frame) } }
            }
        }
        endSession(s, if (ok) null else "Couldn't tell the server the call ended; it will close on its own.", sendLeave = false, engineClosed = false)
    }

    /** Tears down socket, media and timers. Always disposes native media. */
    private fun endSession(s: Session, why: String?, sendLeave: Boolean, engineClosed: Boolean, keepRejoin: Boolean = false) {
        if (current !== s) return
        current = null
        s.ending = true
        s.retry?.close(); s.retry = null
        s.heartbeat?.close(); s.heartbeat = null
        s.socket?.let { sock ->
            if (sendLeave) runCatching { sock.send("""{"type":"leave"}""") }
            runCatching { sock.close(1000, "left") }
        }
        s.socket = null
        runCatching { s.media?.close() }
        s.media = null
        // Disposes this epoch's provider: its transforms now drop, and the derived keys are wiped.
        runCatching { s.e2ee?.close() }
        s.e2ee = null
        s.sendPathEncrypted = false
        val slot = slots[s.conv]
        if (slot != null && !engineClosed) slot.engine.leave() // its Unsubscribe/Close effects target a session that no longer exists
        val inv = invites[s.conv]
        if (keepRejoin && inv != null && clock() < inv.a.expires) invites[s.conv] = Invite(inv.a, rejoin = true) else invites.remove(s.conv)
        if (slot != null) persist(s.conv, slot, strict = false)
        stage = GroupCallStage.IDLE
        notice = why
        publish()
    }

    // ---- media controls ----------------------------------------------------------------------

    fun setMuted(muted: Boolean) = run {
        val s = current ?: return@run
        val slot = slots[s.conv] ?: return@run
        s.muted = muted
        handle(s.conv, slot.engine.mute(muted))
        s.media?.setMicMuted(muted)
        s.socket?.send(stateFrame(s))
        publish()
    }

    fun setDeafened(deafened: Boolean) = run {
        val s = current ?: return@run
        val slot = slots[s.conv] ?: return@run
        s.deafened = deafened
        handle(s.conv, slot.engine.deafen(deafened))
        s.media?.setDeafened(deafened)
        s.socket?.send(stateFrame(s))
        publish()
    }

    fun setVolume(pid: String, volume: Float) = run {
        val s = current ?: return@run
        val v = volume.coerceIn(0f, 2f)
        s.volumes[pid] = v
        s.media?.setVolume(pid, v)
        publish()
    }

    fun dismissNotice() = run { notice = null; publish() }

    // ---- roster changes (owner removal, mirrored from the phone) ------------------------------

    /**
     * A roster was saved (phone sync). A live call is not closed: the OWNER's client advances the room in place with
     * an owner-signed attestation (`POST /roster` -> same room, newer revision, rotated secret, next key epoch) and
     * sends that advanced `group_start` to the members who stayed. Everyone else holds the newer roster until that
     * signed advance arrives. A failed advance fails closed with an honest message.
     */
    fun onRoster(group: StoredGroup) = run {
        val conv = group.conversationId
        val state = group.protocolState()
        val slot = slots[conv]
        if (slot == null) return@run
        val s = current?.takeIf { it.conv == conv }
        val gone = group.hidden || state.members.none { it.key == selfPubkey }
        if (gone) {
            closeNotice = "You were removed from this group, so the call ended."
            if (s != null) endSession(s, closeNotice, sendLeave = true, engineClosed = false)
            closeNotice = null
            invites.remove(conv)
            persist(conv, slot, strict = false) // clears the joinable invitation too
            slots.remove(conv)
            publish(); return@run
        }
        if (state.revision <= slot.roster.revision) { refreshRoster(conv, slot, state); publish(); return@run }
        if (s != null && s.creator && s.welcomed) return@run advance(s, slot, state)
        // Not ours to advance: hold the newer roster until the owner's signed advance re-binds the same room.
        slot.pending = state
        handle(conv, slot.engine.updateRoster(state))
        persist(conv, slot, strict = false)
        publish()
    }

    /** Owner path: attest the new roster, re-bind the live room on the server, rotate the key and announce it. */
    private fun advance(s: Session, slot: Slot, state: GroupProtocol.State) {
        val conv = s.conv
        val members = state.members.map { it.key }.distinct().sorted()
        val hash = GroupCallCodec.rosterHash(state)
        val nowS = clock() / 1000
        val signature = signRoster?.let { signer ->
            runCatching {
                RosterAttestations.sign(RosterAttestation(state.id, state.revision, hash, members, s.a.room, nowS, nowS + 600, state.owner), signer)
            }.getOrNull()
        }
        val sig = signature?.signature
        val secretBytes = random(32)
        if (signature == null || sig == null || secretBytes.size != 32) {
            return failAdvance(s, "The group changed, but this computer couldn't sign the new member list, so the call was ended.")
        }
        val secret = secretBytes.joinToString("") { "%02x".format(it) }
        val request = RosterAdvance(state.id, state.revision, hash, members, signature.owner, sig,
            signature.issuedAt, signature.expiresAt, GroupCallCodec.secretHash(secret))
        val ok = runCatching { api?.advanceRoster(s.a.room, request) }.getOrNull() == true
        if (!ok) return failAdvance(s, "The group changed, but the call couldn't move to the new member list, so it was ended.")
        // WP17: a fresh call key at the next epoch, held only by this host and the members who stayed.
        val epoch = (s.a.keyEpoch ?: -1L) + 1
        val key = if (s.e2ee != null) ByteArray(32).also(secureRandom::nextBytes) else null
        val advanced = runCatching {
            GroupCallCodec.newRevision(s.a, state, clock(), random, if (key != null) epoch else null, key, secret)
        }.getOrNull() ?: return failAdvance(s, "The group changed, but the call key couldn't rotate, so the call was ended.")
        val effects = slot.engine.advance(state, advanced, VerifiedGroupSender.fromAuthenticatedSeal(selfPubkey))
        if (effects.none { it is GroupCallEffect.RebindRoom }) {
            return failAdvance(s, "The group changed, but this computer couldn't adopt the new member list, so the call was ended.")
        }
        slot.roster = state
        slot.pending = null
        invites[conv] = Invite(advanced, rejoin = false)
        persist(conv, slot, strict = false)
        adoptAdvance(conv, slot, advanced)
        handle(conv, effects)
        // Only the members who stayed receive the new secret and key; the removed device never does.
        val frame = GroupCallWire.frame(advanced)
        for (peer in members) if (peer != selfPubkey) runCatching { runBlocking { announce(peer, frame) } }
        publish()
    }

    /** Ends the room and the call together: publishing to a revision this host could not advance is never allowed. */
    private fun failAdvance(s: Session, why: String) {
        runCatching { api?.end(s.a.room) }
        endSession(s, why, sendLeave = false, engineClosed = false)
        publish()
    }

    // ---- UI ----------------------------------------------------------------------------------

    private fun publish() {
        val t = clock()
        val s = current
        val banners = invites.filter { (conv, inv) -> conv != s?.conv && t < inv.a.expires }.mapValues { (conv, inv) ->
            GroupCallBanner(conv, inv.a.room, inv.a.caller, runCatching { nameOf(inv.a.caller) }.getOrDefault(inv.a.caller.take(8)), inv.rejoin)
        }
        val ui = if (s == null) DesktopGroupCallUi(banners = banners, stage = stage.takeIf { it == GroupCallStage.STARTING } ?: GroupCallStage.IDLE, notice = notice)
        else {
            val roster = slots[s.conv]?.roster
            fun inRoster(key: String) = roster?.members?.any { it.key == key } == true
            val others = s.peers.values.groupBy { it.pubkey }.map { (key, list) ->
                GroupPersonUi(key, runCatching { nameOf(key) }.getOrDefault(key.take(8)), self = false, inRoster = inRoster(key),
                    devices = list.map { p -> GroupDeviceUi(p.pid, p.device, p.muted, p.deafened, (s.levels[p.pid] ?: 0f) > SPEAKING, s.volumes[p.pid] ?: 1f) })
            }
            val me = GroupPersonUi(selfPubkey, "You", self = true, inRoster = true,
                devices = listOf(GroupDeviceUi(s.pid ?: "", deviceId, s.muted, s.deafened, false, 1f)))
            // The label is derived, never sticky: a new unverified device or a new epoch withdraws the E2EE wording at once.
            val label = s.e2ee?.label(s.sendPathEncrypted && s.media?.frameE2eeActive() == true)
                ?: GroupCallSecurityLabel.IN_TRANSIT
            DesktopGroupCallUi(banners = banners, stage = stage, conversationId = s.conv,
                groupName = groupByConversation(s.conv)?.name.orEmpty(), people = listOf(me) + others,
                muted = s.muted, deafened = s.deafened, host = s.creator, connectedAtMs = s.connectedAt,
                notice = notice, label = label, securityError = s.securityError)
        }
        _state.value = ui
        runCatching { onChange() }
    }

    /** Hang up, close the socket, dispose native audio and stop timers. Idempotent; the caller stops the transport AFTER this. */
    override fun close() {
        if (closed) return
        try {
            if (Thread.currentThread() !== worker.get()) {
                // An app exit hangs up this device but does not end the room for others: keep it rejoinable after restart.
                runCatching { serial.submit { worker.set(Thread.currentThread()); current?.let { endSession(it, null, sendLeave = true, engineClosed = false, keepRejoin = true) } }.get(6, TimeUnit.SECONDS) }
            } else current?.let { endSession(it, null, sendLeave = true, engineClosed = false, keepRejoin = true) }
        } finally {
            closed = true
            tickTimer?.close()
            (timers as? AutoCloseable)?.close()
            serial.shutdownNow()
        }
    }
}
