package com.relay.app.calls.group

import com.relay.app.call.group.GroupParticipant
import com.relay.app.call.group.e2ee.GroupCallE2eeStatus
import com.relay.app.call.group.e2ee.GroupCallKeySchedule
import com.relay.app.call.group.e2ee.GroupCallSecurityLabel
import com.relay.app.call.group.e2ee.ParticipantDevice
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyDerivationAlgorithm
import org.webrtc.FrameCryptorKeyProvider
import java.security.SecureRandom
import java.util.Base64

/**
 * The 32-byte per-call frame-E2EE key plus its epoch. **Memory only.**
 *
 * It is produced by the starter (SecureRandom, epoch 0), travels to the other members as base64 inside the
 * E2E-sealed `group_start`/`group_reseal` control, and must never reach a room record, a log line, the
 * database, a notification or a REST body. [toString] is redacted on purpose and [copyKey] hands out a copy so
 * no caller can mutate the live key.
 */
class GroupCallE2eeKey private constructor(val epoch: Int, private val key: ByteArray) {

    fun copyKey(): ByteArray = key.copyOf()

    override fun toString(): String = "GroupCallE2eeKey(epoch=$epoch, key=<redacted>)"

    companion object {
        const val BYTES = GroupCallKeySchedule.CALL_KEY_BYTES
        private val random = SecureRandom()

        /** [epoch] must fit the one-byte trailer selector; a wrong-sized key is refused, never truncated. */
        fun of(epoch: Int, key: ByteArray): GroupCallE2eeKey? {
            if (epoch !in 0..GroupCallKeySchedule.MAX_EPOCH) return null
            if (key.size != BYTES) return null
            return GroupCallE2eeKey(epoch, key.copyOf())
        }

        /** From a verified announcement. Null for anything missing, out of range or not exactly 32 bytes. */
        fun fromAnnouncement(keyEpoch: Long?, callKeyBase64: String?): GroupCallE2eeKey? {
            if (keyEpoch == null || callKeyBase64 == null) return null
            if (keyEpoch !in 0..GroupCallKeySchedule.MAX_EPOCH.toLong()) return null
            val decoded = runCatching { Base64.getDecoder().decode(callKeyBase64) }.getOrNull() ?: return null
            return of(keyEpoch.toInt(), decoded)
        }

        /** Fresh epoch-0 call key. [source] is SecureRandom in production (the starter's own provider). */
        fun fresh(source: (Int) -> ByteArray = { size -> ByteArray(size).also(random::nextBytes) }): GroupCallE2eeKey {
            val bytes = source(BYTES)
            require(bytes.size == BYTES) { "call key source returned wrong size" }
            return GroupCallE2eeKey(0, bytes.copyOf())
        }

        /** The next epoch key for a rotation; null when the one-byte epoch is exhausted. */
        fun nextEpoch(previous: Int, source: (Int) -> ByteArray = { size -> ByteArray(size).also(random::nextBytes) }): GroupCallE2eeKey? {
            if (previous >= GroupCallKeySchedule.MAX_EPOCH) return null
            val bytes = source(BYTES)
            require(bytes.size == BYTES) { "call key source returned wrong size" }
            return GroupCallE2eeKey(previous + 1, bytes.copyOf())
        }
    }
}

/**
 * One group call's frame-E2EE runtime on Android: epoch key -> per-device key schedule -> `FrameCryptor` key
 * provider, plus the coverage tracker behind the security label.
 *
 * ## Key flow
 * The key arrives here already authenticated: the starter minted it and sealed it into its own `group_start`,
 * a member received it from a verified sealed announcement, or a remaining member received a re-key from the
 * creator's sealed `group_reseal`. Nothing in this class ever reads a key off the room socket or the SFU.
 *
 * ## Roles
 * - The peer connection owns the cryptors, because a `FrameCryptor` is bound to one `RtpSender`/`RtpReceiver`
 *   and has to die with it. It asks this class for [provider] / [keyIndex] only.
 * - This class owns the key, the schedule (one provider per epoch, `ratchetSalt` is taken at provider
 *   creation), the per-device `setKey` calls and the [tracker].
 *
 * ## Fail closed
 * [provider] is null until a schedule exists, and the peer refuses to publish without one. A device that
 * arrives in an epoch whose participant set cannot be extended (see [installEpoch]) gets no key and is not
 * played; [lastError] carries the honest reason for the in-call screen.
 */
class GroupCallE2ee(
    initial: GroupCallE2eeKey,
    val self: ParticipantDevice,
    private val listener: Listener,
    private val callKeySource: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
) {

    interface Listener {
        /** Label for the in-call banner plus an honest error, or null when nothing is wrong. */
        fun onSecurityChanged(label: String, error: String?)

        /** A provider (and therefore every cryptor bound to it) was replaced: rebuild sender/receiver cryptors. */
        fun onEpochChanged()
    }

    private val lock = Any()
    private var key: GroupCallE2eeKey = initial
    private var schedule: GroupCallKeySchedule =
        // The key may be a current-epoch key (a device that joins after a re-key), never assume epoch 0.
        GroupCallKeySchedule.create(initial.copyKey(), initial.epoch, listOf(self))
    private var tracker: GroupCallE2eeStatus = GroupCallE2eeStatus.forSchedule(schedule)
    private var provider: FrameCryptorKeyProvider? = createProvider(schedule)
    private val keyed = HashSet<String>()
    private var sessions: Map<String, ParticipantDevice> = emptyMap()
    private var devices: Set<ParticipantDevice> = setOf(self)
    private var sendPathInstalled = false
    private var failure: String? = null
    /** Set when *our* send path must stay off (no key, failed encryption, failed rotation). */
    private var sendBlocked: String? = null

    /** Only participants that were keyed on the provider; a new device in a frozen epoch is not here. */
    private var supported: Set<ParticipantDevice> = setOf(self)

    @Volatile private var label: String = GroupCallSecurityLabel.IN_TRANSIT

    init {
        schedule.participants.forEach { setKeyOnProvider(it) }
        supported = schedule.participants.toSet()
        if (provider == null) {
            failure = ENCRYPTION_UNAVAILABLE
            sendBlocked = ENCRYPTION_UNAVAILABLE
        }
        emit()
    }

    val epoch: Int get() = synchronized(lock) { key.epoch }
    val keyIndex: Int get() = epoch
    val securityLabel: String get() = label
    val lastError: String? get() = synchronized(lock) { failure }

    /** The key provider for the current epoch, or null when E2EE could not be set up (never publish then). */
    fun provider(): FrameCryptorKeyProvider? = synchronized(lock) { provider }

    /** False when this device must not publish at all: no provider, a failed cryptor, or a failed rotation. */
    fun canSend(): Boolean = synchronized(lock) { provider != null && sendBlocked == null }

    /** Participant id (`identity:device`) the SFU session belongs to, when it is a keyed participant. */
    fun participantForSession(session: String): ParticipantDevice? = synchronized(lock) {
        sessions[session]?.takeIf { it in supported }
    }

    /** Whether a cryptor for [participant] can be created at all in this epoch. */
    fun supports(participant: ParticipantDevice): Boolean = synchronized(lock) { participant in supported }

    /**
     * The room roster changed. New devices are handed their epoch key on the *existing* provider (the epoch
     * salt does not depend on the participant set), so no cryptor churn is needed; only the coverage tracker
     * is rebuilt, which drops the label back to "encrypted in transit" until the new device proves its key.
     */
    fun setParticipants(participants: List<GroupParticipant>) {
        synchronized(lock) {
            sessions = participants.filter { it.pubkey != self.identity }
                .mapNotNull { p -> p.sessionId?.let { session -> session to deviceOf(p) } }.toMap()
            val next = participants.filter { it.pubkey != self.identity }
                .mapNotNull { runCatching { deviceOf(it) }.getOrNull() }.toSet() + self
            if (next == devices) return@synchronized
            devices = next
            // The epoch enters only the HKDF labels, never the participant set, so a schedule for ANY epoch can
            // be rebuilt from the call key + epoch. A device that joins after a re-key therefore adopts the
            // current epoch's key instead of being frozen out (the keys already handed out stay byte-identical).
            schedule = GroupCallKeySchedule.create(key.copyKey(), key.epoch, next)
            schedule.participants.forEach { setKeyOnProvider(it) }
            supported = schedule.participants.toSet()
            rebuildTracker(next)
        }
        emit()
    }

    /** A frame from [participant] authenticated under the current epoch key: that device proved possession. */
    fun verify(participant: ParticipantDevice) {
        val changed = synchronized(lock) {
            if (participant !in supported) return
            // FrameCryptor reports OK on every frame; only a change of proof may reach the UI.
            val alreadyProven = tracker.verifiedEpochs()[participant] == key.epoch
            val hadFailure = failure == participantFailure(participant)
            if (alreadyProven && !hadFailure) return
            tracker.verify(participant, key.epoch)
            if (hadFailure) failure = null
            true
        }
        if (changed) emit()
    }

    /** A device failed to authenticate: drop its proof, mute it and say why. */
    fun invalidate(participant: ParticipantDevice) {
        synchronized(lock) {
            tracker.invalidate(participant)
            failure = participantFailure(participant)
        }
        emit()
    }

    /** A publisher track could not be matched to a roster device: its audio is not played. */
    fun blockedUnknownSource() {
        synchronized(lock) { failure = UNKNOWN_SOURCE }
        emit()
    }

    /** The message [invalidate] shows for one device; also the key [verify] clears when that device recovers. */
    fun participantFailure(participant: ParticipantDevice): String =
        "Audio from ${participant.identity.take(8)}… couldn't be decrypted, so it was muted."

    fun noteSenderInstalled() {
        val changed = synchronized(lock) {
            if (sendPathInstalled) return
            sendPathInstalled = true
            true
        }
        if (changed) emit()
    }

    fun noteSendFailure(message: String) {
        synchronized(lock) {
            sendPathInstalled = false
            sendBlocked = message
            failure = message
        }
        emit()
    }

    fun noteSendOk() {
        val changed = synchronized(lock) {
            if (sendPathInstalled && sendBlocked == null) return
            sendPathInstalled = true
            sendBlocked = null
            if (failure == SEND_FAILED) failure = null
            true
        }
        if (changed) emit()
    }

    /**
     * Rotation after a roster removal: mint a fresh 32-byte key, advance one epoch and hand it back so the
     * caller can seal it to the remaining members only. Null means the rotation could not be applied locally;
     * [lastError] says why and the send path stays off (we never keep using a key a removed member knows).
     */
    fun rotateForRemoval(remainingIdentities: Set<String>): GroupCallE2eeKey? {
        val next: GroupCallE2eeKey
        var old: FrameCryptorKeyProvider? = null
        synchronized(lock) {
            next = GroupCallE2eeKey.nextEpoch(key.epoch, callKeySource)
                ?: run { failure = EPOCH_EXHAUSTED; sendBlocked = EPOCH_EXHAUSTED; return null }
            val allowed = remainingIdentities + self.identity
            val keep = (devices.filter { it.identity in allowed } + self).toSet()
            if (keep.size >= devices.size) {
                failure = ROTATION_IMPOSSIBLE
                sendBlocked = ROTATION_IMPOSSIBLE
                return null
            }
            old = installEpochLocked(next, keep) ?: return null
        }
        listener.onEpochChanged()
        runCatching { old?.dispose() }
        emit()
        return next
    }

    /**
     * Apply a re-key received in a sealed `group_reseal` from the creator. Only a strictly newer epoch is
     * accepted; a replayed or stale one is ignored (and reported), because applying it would let a removed
     * member's key come back.
     */
    fun adoptRotation(next: GroupCallE2eeKey, remainingIdentities: Set<String>): Boolean {
        var old: FrameCryptorKeyProvider? = null
        synchronized(lock) {
            if (next.epoch <= key.epoch) {
                failure = "A stale call key arrived and was ignored."
                return false
            }
            if (next.epoch != key.epoch + 1) {
                failure = ROTATION_IMPOSSIBLE
                sendBlocked = ROTATION_IMPOSSIBLE
                return false
            }
            val allowed = remainingIdentities + self.identity
            val keep = (devices.filter { it.identity in allowed } + self).toSet()
            if (keep.size >= devices.size) {
                failure = ROTATION_IMPOSSIBLE
                sendBlocked = ROTATION_IMPOSSIBLE
                return false
            }
            old = installEpochLocked(next, keep) ?: return false
        }
        listener.onEpochChanged()
        runCatching { old?.dispose() }
        emit()
        return true
    }

    fun dispose() {
        synchronized(lock) {
            runCatching { provider?.dispose() }
            provider = null
            sendPathInstalled = false
        }
    }

    // ---- internals ---------------------------------------------------------------------------------

    /** Caller holds [lock]. Returns the provider that must be disposed once the peer swapped its cryptors. */
    private fun installEpochLocked(next: GroupCallE2eeKey, keep: Set<ParticipantDevice>): FrameCryptorKeyProvider? {
        val rotated = try {
            schedule.rotateForRemoval(keep, next.copyKey())
        } catch (e: IllegalArgumentException) {
            failure = ROTATION_IMPOSSIBLE
            sendBlocked = ROTATION_IMPOSSIBLE
            return null
        }
        val fresh = createProvider(rotated)
        if (fresh == null) {
            failure = ENCRYPTION_UNAVAILABLE
            sendBlocked = ENCRYPTION_UNAVAILABLE
            return null
        }
        keyed.clear()
        rotated.participants.forEach { participant ->
            if (fresh.setKey(participant.frameCryptorParticipantId(), rotated.keyIndex, rotated.materialFor(participant))) {
                keyed += participant.frameCryptorParticipantId()
            }
        }
        val old = provider
        key = next
        schedule = rotated
        provider = fresh
        supported = rotated.participants.toSet()
        devices = supported
        tracker = GroupCallE2eeStatus.forSchedule(rotated)
        sendPathInstalled = false
        sendBlocked = null
        failure = null
        // The new epoch is unproven until frames arrive under it; the label falls back on the next emit().
        return old
    }

    private fun createProvider(forSchedule: GroupCallKeySchedule): FrameCryptorKeyProvider? = runCatching {
        FrameCryptorFactory.createFrameCryptorKeyProvider(
            /* shared = */ false,
            /* ratchetSalt = */ forSchedule.ratchetSaltCopy(),
            /* ratchetWindow = */ 0,
            /* uncryptedMagicBytes = */ null,
            /* failureTolerance = */ 0,
            /* keyRingSize = */ 1,
            /* discardWhenNotReady = */ true,
            /* algorithm = */ FrameCryptorKeyDerivationAlgorithm.PBKDF2,
        )
    }.getOrNull()

    /** Caller holds [lock]. Only the ASCII-hex material goes to the provider, never the raw digest. */
    private fun setKeyOnProvider(participant: ParticipantDevice) {
        val p = provider ?: return
        if (participant !in schedule.participants) return
        val id = participant.frameCryptorParticipantId()
        if (id in keyed) return
        if (p.setKey(id, schedule.keyIndex, schedule.materialFor(participant))) keyed += id
    }

    /** Caller holds [lock]. Proofs survive for devices that are still here; new devices start unverified. */
    private fun rebuildTracker(devices: Set<ParticipantDevice>) {
        val proven = tracker.verifiedEpochs()
        val next = GroupCallE2eeStatus.forEpoch(key.epoch, devices)
        proven.forEach { (device, epoch) -> if (device in devices && epoch == key.epoch) next.verify(device, epoch) }
        tracker = next
    }

    private fun emit() {
        val state = synchronized(lock) {
            val coverage = tracker.coverage(
                // The room protocol carries no frame-E2EE capability bit and the server is out of scope. A
                // peer that does not seal fails decryption here, which invalidates its proof and mutes it, so
                // capability is proven by surviving frames rather than asserted by the roster.
                allParticipantsCapable = true,
                // Only ever set after a key was taken from a verified sealed announcement (or minted locally).
                keyDistributionAuthenticated = true,
                // No provider means nothing is being sealed, and the label must say so.
                sendPathEncrypted = sendPathInstalled && provider != null,
            )
            label = GroupCallSecurityLabel.label(coverage)
            label to failure
        }
        listener.onSecurityChanged(state.first, state.second)
    }

    private fun deviceOf(p: GroupParticipant): ParticipantDevice = ParticipantDevice(p.pubkey, p.device)

    companion object {
        const val ENCRYPTION_UNAVAILABLE = "This device couldn't set up the call's encryption, so no audio was sent."
        const val ROTATION_IMPOSSIBLE = "This call was re-keyed and this device couldn't follow. Rejoin from a new invite."
        const val SEND_FAILED = "Your microphone couldn't be encrypted, so it was muted."
        const val EPOCH_EXHAUSTED = "This call used up its key epochs. Start a new call."
        const val UNKNOWN_DEVICE = "Someone joined who can't be given this call's new key, so they're muted."
        const val UNKNOWN_SOURCE = "A participant's audio couldn't be matched to this call's key, so it was muted."
    }
}
