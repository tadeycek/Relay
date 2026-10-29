package com.relay.app.calls.group

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.util.Log
import androidx.core.content.ContextCompat
import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.e2ee.ParticipantDevice
import com.relay.app.calls.CallAudio
import com.relay.app.calls.CallController
import com.relay.app.calls.CallForegroundService
import com.relay.app.data.repository.ContactRepository
import com.relay.app.groups.GroupProtocol
import com.relay.app.groups.SharedGroups
import com.relay.app.messaging.MessagingDb
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.Transports
import com.relay.app.transport.mailbox.MailboxConfig
import com.relay.app.transport.nostr.NostrIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Everything the group call screen paints. [model] is the tested roster/participant reduction. */
data class GroupCallUiState(
    val groupId: Long,
    val groupName: String,
    val room: String,
    val phase: SessionPhase,
    val model: GroupCallUiModel,
    val finished: SessionEnd? = null,
    val message: String = "",
    /** Honest in-call E2EE problem (key missing / a peer's frames could not be decrypted), or null. */
    val error: String? = null,
)

/**
 * The Android host of a group call, and the one place the real group chat, the inbound message path and
 * the room/SFU ports meet.
 *
 * Ingress is authenticated by [SharedGroups] (the envelope sender must equal the event author), and the
 * coordinator turns that sender into a [com.relay.app.call.group.VerifiedGroupSender] itself. An
 * announcement is persisted before the banner/effects are released; a group call frame is never stored as
 * a chat row, unread or notification.
 *
 * Only one group call can be live per process. The 1:1 host checks [isBusy] too, so the two call surfaces
 * cannot overlap. The microphone is opened by [SfuSession.publish] and released by every terminate path.
 */
object GroupCallController {

    private const val TAG = "RelayGroupCall"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coordinators = ConcurrentHashMap<Long, GroupCallCoordinator>()
    private val activeLock = Any()
    @Volatile private var active: Active? = null

    private var appContext: Context? = null
    private var repository: GroupCallRepository? = null
    private var started = false

    private val _banners = MutableStateFlow<Map<Long, GroupCallBanner>>(emptyMap())
    val banners: StateFlow<Map<Long, GroupCallBanner>> = _banners.asStateFlow()

    private val _screen = MutableStateFlow<GroupCallUiState?>(null)
    val screen: StateFlow<GroupCallUiState?> = _screen.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private class Active(
        val groupId: Long,
        val groupName: String,
        val room: String,
        val self: String,
        var roster: GroupProtocol.State,
        val session: GroupCallSessionCore,
        val sfu: SfuSession,
        val peer: GroupSfuPeer,
        val e2ee: GroupCallE2ee,
        val focus: AudioFocusRequest?,
        var participants: List<com.relay.app.call.group.GroupParticipant> = emptyList(),
        var deafenedByPid: Map<String, Boolean> = emptyMap(),
        var volumes: MutableMap<String, Float> = ConcurrentHashMap(),
        var muted: Boolean = false,
        var deafened: Boolean = false,
        var expectedEnd: SessionEnd? = null,
        var securityLabel: String = com.relay.app.call.group.e2ee.GroupCallSecurityLabel.IN_TRANSIT,
        var securityError: String? = null,
        val tracker: SpeakingTracker = SpeakingTracker(),
    )

    fun initialize(context: Context) {
        val app = context.applicationContext
        if (started) return
        synchronized(this) {
            if (started) return
            appContext = app
            repository = GroupCallRepository(app)
            // A persisted banner without a coordinator, socket or in-memory secret is not a live call.
            // Drop it *and* any notification/service a killed process left behind, so a restart can
            // never resurrect a stale call.
            val stale = runCatching { repository?.loadAll().orEmpty() }.getOrDefault(emptyMap())
            if (stale.isNotEmpty()) {
                com.relay.app.calls.CallForegroundService.stop(app)
                com.relay.app.calls.CallNotifier.cancel(app)
            }
            runCatching { repository?.purgeOnColdStart() }
            started = true
        }
    }

    fun isBusy(): Boolean = active != null
    fun activeGroup(): Long? = active?.groupId

    /** The banner for one group, recomputed from the durable coordinator fences. */
    fun bannerFor(groupId: Long): GroupCallBanner? {
        val coord = coordinators[groupId] ?: return _banners.value[groupId]?.takeIf { it.expires > System.currentTimeMillis() }
        val live = synchronized(coord) { coord.liveBanner() }
        if (live == null) {
            _banners.value = _banners.value - groupId
            if (!coord.hasActiveRoom()) repository?.clear(groupId)
            return null
        }
        _banners.value = _banners.value + (groupId to live)
        repository?.save(StoredGroupCall(groupId, live.room, live.caller, live.created, live.expires))
        return live
    }

    fun clearNotice() { _notice.value = null }

    // ---- ingress -----------------------------------------------------------------------------------

    /**
     * Called by [SharedGroups] for a `TYPE:CALL|J:` group event, before any chat/notification path.
     * [authenticatedSender] is the verified envelope sender (and must equal the event author there).
     */
    fun onGroupAnnouncement(context: Context, groupId: Long, roster: GroupProtocol.State, authenticatedSender: String, wire: String) {
        initialize(context)
        val coord = coordinatorFor(groupId, roster)
        val result = synchronized(coord) { coord.onWire(authenticatedSender, wire) }
        when (result) {
            is IngressResult.Accepted -> {
                if (result.effects.contains(com.relay.app.call.group.GroupCallEffect.CloseRoom)) {
                    endActive(groupId, SessionEnd.ENDED, "The call ended.")
                }
                // A sealed group_reseal may carry a newer epoch key (a member was removed). Only a strictly
                // newer epoch is applied; a stale one was already refused by the coordinator.
                active?.takeIf { it.groupId == groupId }?.let { adoptCallKey(it, coord) }
                val banner = result.banner
                _banners.value = if (banner != null) _banners.value + (groupId to banner) else _banners.value - groupId
                if (banner != null) {
                    repository?.save(StoredGroupCall(groupId, banner.room, banner.caller, banner.created, banner.expires))
                } else repository?.clear(groupId)
            }
            is IngressResult.PersistFailed -> {
                _banners.value = _banners.value - groupId
                repository?.clear(groupId)
                endActive(groupId, SessionEnd.FAILED, "The call state couldn't be saved, so the call was closed.")
            }
            is IngressResult.Rejected -> Log.i(TAG, "group call frame rejected: ${result.reason}")
            IngressResult.NotAnnouncement -> Unit
        }
    }

    /** Applies a re-key from the coordinator to the live call, or leaves the label/keys untouched. */
    private fun adoptCallKey(holder: Active, coord: GroupCallCoordinator) {
        val announcement = synchronized(coord) { coord.currentAnnouncement() } ?: return
        val next = GroupCallE2eeKey.fromAnnouncement(announcement.keyEpoch, announcement.callKey) ?: return
        if (next.epoch <= holder.e2ee.epoch) return
        // The coordinator only accepts a control bound to the roster revision it already holds, so its roster
        // is the authority on who stayed - not the (possibly newer) local one.
        val remaining = synchronized(coord) { coord.roster().members.map { it.key }.toSet() }
        if (!holder.e2ee.adoptRotation(next, remaining)) {
            Log.w(TAG, "a re-keyed call could not be followed on this device")
        }
    }

    /** Called by [SharedGroups] whenever this device saves a newer roster revision. */
    fun onRosterChanged(context: Context, groupId: Long, roster: GroupProtocol.State) {
        initialize(context)
        val holder = active
        if (holder != null && holder.groupId == groupId) {
            val frozen = holder.roster.members.map { it.key }
            holder.roster = roster
            holder.session.onRosterChanged(roster, frozen)
            return
        }
        val coord = coordinatorFor(groupId, roster)
        synchronized(coord) { coord.updateRoster(roster) }
        bannerFor(groupId)
    }

    // ---- start / join / leave / end ----------------------------------------------------------------

    fun start(context: Context, groupId: Long) {
        initialize(context)
        val app = context.applicationContext
        scope.launch {
            runCatching { startBlocking(app, groupId) }
                .onFailure { Log.e(TAG, "could not start group call", it); notice("The call couldn't be started.") }
        }
    }

    fun join(context: Context, groupId: Long) {
        initialize(context)
        val app = context.applicationContext
        scope.launch {
            runCatching { joinBlocking(app, groupId) }
                .onFailure { Log.e(TAG, "could not join group call", it); notice("The call couldn't be joined.") }
        }
    }

    fun leave(context: Context) {
        val holder = active ?: return
        holder.expectedEnd = SessionEnd.LEFT
        holder.session.leave()
    }

    fun end(context: Context) {
        initialize(context)
        val app = context.applicationContext
        val holder = active ?: return
        val coord = coordinators[holder.groupId] ?: return
        val announcement = synchronized(coord) { coord.currentAnnouncement() }
        if (announcement == null || announcement.caller != NostrIdentity.publicKeyHex(app)) {
            notice("Only the group creator can end the call for everyone.")
            return
        }
        scope.launch {
            val answer = GroupCallStarter(AndroidGroupCallHttp(app), coord,
                AnnouncementSender { wire -> SharedGroups.stageCallControl(app, holder.groupId, wire) },
                NostrIdentity.publicKeyHex(app), GROUP_DEVICE, { System.currentTimeMillis() })
                .end(announcement)
            when (answer) {
                EndOutcome.Ended -> {
                    holder.expectedEnd = SessionEnd.ENDED
                    holder.session.dispose()
                }
                EndOutcome.NotCreator -> notice("Only the group creator can end the call for everyone.")
                is EndOutcome.Failed -> notice("The call couldn't be ended on the server. Try again.")
            }
        }
    }

    fun dismissEnded() {
        if (_screen.value?.finished != null) _screen.value = null
    }

    fun mute(context: Context, muted: Boolean) {
        val holder = active ?: return
        holder.muted = muted
        holder.session.mute(muted)
        rebuild(holder)
    }

    fun deafen(context: Context, deafened: Boolean) {
        val holder = active ?: return
        holder.deafened = deafened
        holder.session.deafen(deafened)
        rebuild(holder)
    }

    fun setVolume(context: Context, pubkey: String, volume: Float) {
        val holder = active ?: return
        val v = volume.coerceIn(0f, 1f)
        holder.volumes[pubkey] = v
        val sessions = holder.participants.filter { it.pubkey == pubkey }.mapNotNull { it.sessionId }
        if (sessions.isNotEmpty()) holder.session.setParticipantVolume(sessions, v)
        rebuild(holder)
    }

    // ---- wiring ------------------------------------------------------------------------------------

    private suspend fun startBlocking(app: Context, groupId: Long) {
        val stored = SharedGroups.get(MessagingDb.get(app), groupId)
        if (stored == null || stored.hidden) { notice(GroupCallGuard.message(GroupStartBlock.NOT_IN_GROUP) ?: "You're no longer in this group."); return }
        val self = NostrIdentity.publicKeyHex(app)
        val block = GroupCallGuard.startBlock(
            linked = MailboxConfig.available,
            online = online(app),
            micGranted = hasMic(app),
            otherCallLive = CallController.isBusy() || isBusy(),
            callsEnabled = true,
            roster = stored.state,
            self = self,
            reachableMembers = reachableMembers(app, stored.state, self),
        )
        if (block != GroupStartBlock.NONE) { notice(GroupCallGuard.message(block) ?: "The call can't be started right now."); return }

        val coord = coordinatorFor(groupId, stored.state)
        val http = AndroidGroupCallHttp(app)
        val starter = GroupCallStarter(http, coord,
            AnnouncementSender { wire -> SharedGroups.stageCallControl(app, groupId, wire) },
            self, GROUP_DEVICE, { System.currentTimeMillis() })
        when (val outcome = starter.start(stored.state)) {
            is StartOutcome.Started -> {
                val banner = bannerFor(groupId)
                if (banner == null) { notice("The call was created but its invite couldn't be saved."); return }
                val key = GroupCallE2eeKey.fromAnnouncement(outcome.announcement.keyEpoch, outcome.announcement.callKey)
                if (key == null) {
                    notice("The call's encryption key couldn't be created, so it wasn't started.")
                    return
                }
                connect(app, groupId, stored.state.name, outcome.announcement.room,
                    outcome.announcement.joinSecret!!, stored.state, key)
            }
            is StartOutcome.JoinWinner -> {
                bannerFor(groupId)
                notice("Another member already started this call. Join from the call banner when it arrives.")
            }
            StartOutcome.StaleRevision -> notice("The group changed. Reopen the chat and try again.")
            StartOutcome.NotInRoster -> notice(GroupCallGuard.message(GroupStartBlock.NOT_IN_GROUP) ?: "You're no longer in this group.")
            StartOutcome.RosterRequired -> notice("This server requires roster-bound group calls. Update Relay.")
            is StartOutcome.Failed -> notice(if (outcome.retryable) "The call couldn't start. Try again." else "This call wasn't allowed.")
        }
    }

    private suspend fun joinBlocking(app: Context, groupId: Long) {
        if (isBusy()) { notice("You're already on a call."); return }
        val stored = SharedGroups.get(MessagingDb.get(app), groupId)
        if (stored == null || stored.hidden) { notice(GroupCallGuard.message(GroupStartBlock.NOT_IN_GROUP) ?: "You're no longer in this group."); return }
        val self = NostrIdentity.publicKeyHex(app)
        if (stored.state.members.none { it.key == self }) {
            notice(GroupCallGuard.message(GroupStartBlock.NOT_IN_GROUP) ?: "You're no longer in this group.")
            return
        }
        val block = GroupCallGuard.joinBlock(
            linked = MailboxConfig.available,
            online = online(app),
            micGranted = hasMic(app),
            otherCallLive = CallController.isBusy(),
            callsEnabled = true,
        )
        if (block != GroupStartBlock.NONE) { notice(GroupCallGuard.message(block) ?: "The call can't be started right now."); return }
        val coord = coordinatorFor(groupId, stored.state)
        bannerFor(groupId)
        val info = synchronized(coord) { coord.joinInfo() }
        if (info == null) {
            // A persisted banner without the in-memory secret is not a joinable call: say so instead of
            // opening a microphone for a room we cannot authenticate to.
            notice("This call's join key isn't available on this device any more. Ask for a new invite.")
            return
        }
        // WP17 fail closed: no call key, no join. Playing the SFU's plaintext because the key is missing
        // would silently downgrade the call.
        val key = synchronized(coord) { coord.joinKey() }
        if (key == null) {
            notice("This call isn't end-to-end encrypted on this device, so it can't be joined safely. Ask the creator to update Relay.")
            return
        }
        connect(app, groupId, stored.state.name, info.first, info.second, stored.state, key)
    }

    private fun connect(app: Context, groupId: Long, groupName: String, room: String, secret: String,
        roster: GroupProtocol.State, callKey: GroupCallE2eeKey) {
        synchronized(activeLock) {
            if (active != null) { notice("You're already on a call."); return }
            val self = NostrIdentity.publicKeyHex(app)
            val coord = coordinatorFor(groupId, roster)
            val peer = GroupSfuPeer(app)
            val sfu = SfuSession(WorkerSfuApi(AndroidGroupCallHttp(app), room), peer)
            val holderRef = arrayOfNulls<Active>(1)
            // The key never leaves memory: it came from the sealed announcement (starter) or the sealed
            // group_start (member). The peer refuses to publish without the provider this builds.
            val e2ee = GroupCallE2ee(callKey, ParticipantDevice(self, GROUP_DEVICE),
                object : GroupCallE2ee.Listener {
                    override fun onSecurityChanged(label: String, error: String?) {
                        val h = holderRef[0] ?: return
                        h.securityLabel = label
                        h.securityError = error
                        // FrameCryptor reports state from a native thread; UI state changes stay on ours.
                        scope.launch { rebuild(h) }
                    }

                    override fun onEpochChanged() { holderRef[0]?.peer?.applyEpoch() }
                })
            peer.bindE2ee(e2ee)
            val listener = object : GroupCallSessionListener {
                override fun onPhase(phase: SessionPhase) {
                    val h = holderRef[0] ?: return
                    val current = _screen.value
                    if (current != null && current.groupId == groupId) _screen.value = current.copy(phase = phase)
                }

                override fun onParticipants(participants: List<com.relay.app.call.group.GroupParticipant>, deafened: Map<String, Boolean>) {
                    val h = holderRef[0] ?: return
                    h.participants = participants
                    h.deafenedByPid = deafened
                    // Every roster device gets its epoch key here; an unknown newcomer drops the label back
                    // to "encrypted in transit" until it proves the key with a frame.
                    h.e2ee.setParticipants(participants)
                    rebuild(h)
                }

                override fun onWarnUnknown(pid: String) {
                    notice("A participant isn't on this group's roster and won't be heard.")
                }

                override fun onDeafened(deafened: Boolean) {
                    val h = holderRef[0] ?: return
                    h.deafened = deafened
                    rebuild(h)
                }

                override fun onFinished(end: SessionEnd, message: String) {
                    val h = holderRef[0] ?: return
                    finish(h, h.expectedEnd ?: end, message)
                }
            }
            val session = GroupCallSessionCore(
                scope = scope,
                coordinator = coord,
                http = AndroidGroupCallHttp(app),
                socketPort = AndroidRoomSocketPort(app),
                timers = AndroidRoomTimers(),
                sfu = sfu,
                roster = { holderRef[0]?.roster ?: roster },
                self = self,
                device = GROUP_DEVICE,
                creator = roster.owner,
                listener = listener,
                onSecretRotated = { roomId, newSecret, remaining ->
                    reseal(app, groupId, roomId, newSecret, remaining)
                },
            )
            val focus = CallAudio.take(app)
            CallAudio.apply(app, true)
            val holder = Active(groupId, groupName, room, self, roster, session, sfu, peer, e2ee, focus)
            holderRef[0] = holder
            active = holder
            _screen.value = GroupCallUiState(groupId, groupName, room, SessionPhase.JOINING,
                model(holder), message = "")
            CallForegroundService.startGroup(app, groupId, groupName)
            session.join(room, secret)
            scope.launch { ticker(holder) }
        }
    }

    /**
     * Called by the session after `POST /roster` removed members and rotated the room capability. The starter
     * also rotates the frame-E2EE key here: a fresh 32-byte key at the next epoch, sealed to exactly the
     * members that stayed. The removed device never receives it, so it cannot derive the new epoch even
     * though it still knows the old call key.
     */
    private fun reseal(app: Context, groupId: Long, room: String, secret: String, remaining: List<String>) {
        if (remaining.isEmpty()) return
        runCatching {
            val stored = SharedGroups.get(MessagingDb.get(app), groupId) ?: return
            val self = NostrIdentity.publicKeyHex(app)
            val now = System.currentTimeMillis()
            val holder = active?.takeIf { it.groupId == groupId && it.self == self }
            val rotated = holder?.e2ee?.rotateForRemoval(remaining.toSet())
            if (holder != null && rotated == null) {
                Log.w(TAG, "call re-key could not be applied locally; send path stays off")
            }
            val json = JSONObject()
                .put("v", 1).put("op", "group_reseal").put("group", stored.state.id)
                .put("owner", stored.state.owner).put("revision", stored.state.revision)
                .put("room", room).put("joinSecret", secret).put("caller", self).put("device", GROUP_DEVICE)
                .put("created", now).put("expires", now + GroupCallCodec.MAX_LIFETIME_MS)
            if (rotated != null) {
                json.put("keyEpoch", rotated.epoch.toLong())
                    .put("callKey", java.util.Base64.getEncoder().encodeToString(rotated.copyKey()))
            }
            val wire = GroupCallWire.wrap(json.toString())
            SharedGroups.stageCallControl(app, groupId, wire, only = remaining.toSet())
        }.onFailure { Log.w(TAG, "capability re-seal couldn't be sent: ${it.message}") }
    }

    private fun ticker(holder: Active) {
        scope.launch {
            while (active === holder) {
                delay(350)
                val levels = holder.peer.audioLevels()
                val bySession = holder.participants.mapNotNull { p -> p.sessionId?.let { it to p.pubkey } }.toMap()
                val now = System.currentTimeMillis()
                var changed = false
                for ((key, level) in levels) {
                    val pubkey = bySession[key] ?: key.takeIf { PID.matches(it) } ?: continue
                    holder.tracker.update(pubkey, level, now)
                    changed = true
                }
                holder.tracker.retainOnly(bySession.values.toSet() + setOf(holder.self))
                if (changed) rebuild(holder)
            }
        }
    }

    private fun rebuild(holder: Active) {
        if (active !== holder || holder.expectedEnd != null) return
        val phase = _screen.value?.phase ?: SessionPhase.JOINING
        _screen.value = GroupCallUiState(holder.groupId, holder.groupName, holder.room, phase, model(holder),
            error = holder.securityError)
    }

    private fun model(holder: Active): GroupCallUiModel {
        val speaking = holder.tracker.speaking(System.currentTimeMillis())
        return GroupCallUiModelFactory.build(
            roster = holder.roster,
            self = holder.self,
            selfPid = holder.session.selfPid(),
            selfDevice = GROUP_DEVICE,
            participants = holder.participants,
            deafenedByPid = holder.deafenedByPid,
            speakingPids = speaking,
            volumes = holder.volumes,
            muted = holder.muted,
            deafened = holder.deafened,
            creator = holder.roster.owner,
            securityLabel = holder.securityLabel,
        )
    }

    private fun finish(holder: Active, end: SessionEnd, message: String) {
        synchronized(activeLock) {
            if (active !== holder) return
            active = null
            _banners.value = _banners.value - holder.groupId
            repository?.clear(holder.groupId)
            runCatching { holder.e2ee.dispose() }
            _screen.value = GroupCallUiState(holder.groupId, holder.groupName, holder.room, SessionPhase.CONNECTED,
                model(holder), finished = end, message = message, error = holder.securityError)
            CallForegroundService.stop(appContext ?: return)
            CallAudio.release(appContext ?: return, holder.focus)
        }
    }

    private fun endActive(groupId: Long, end: SessionEnd, message: String) {
        val holder = active ?: return
        if (holder.groupId != groupId) return
        holder.expectedEnd = end
        holder.session.dispose()
    }

    private fun coordinatorFor(groupId: Long, roster: GroupProtocol.State): GroupCallCoordinator =
        coordinators.computeIfAbsent(groupId) {
            val app = appContext ?: error("GroupCallController not initialised")
            val durable = AndroidGroupCallStore(app, groupId.toString(), roster.owner)
            // A corrupt fence snapshot throws from the store: fail closed rather than restore an empty
            // replay cache that would accept an old announcement again.
            GroupCallCoordinator(roster, NostrIdentity.publicKeyHex(app), { System.currentTimeMillis() }, durable)
        }.also { existing ->
            // A newer roster may arrive without an active session; keep the reducer current.
            synchronized(existing) { if (existing.roster().revision < roster.revision) existing.updateRoster(roster) }
        }

    private fun reachableMembers(app: Context, roster: GroupProtocol.State, self: String): Int {
        val contacts = ContactRepository(MessagingDb.get(app))
        return roster.members.count { m ->
            m.key != self && (m.inner != null || contacts.findByNostrPubkeySync(m.key)?.let(GroupProtocol::eligible) == true)
        }
    }

    private fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun online(context: Context): Boolean = try {
        Transports.get(context).status.value == TransportStatus.ONLINE
    } catch (_: Exception) { false }

    private fun notice(message: String) {
        _notice.value = message
    }

    private val PID = Regex("[0-9a-f]{16}")
}
