package com.relay.app.desktop

import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.e2ee.E2eeCoverage
import com.relay.app.call.group.e2ee.FrameKeyLookup
import com.relay.app.call.group.e2ee.FrameKeyStatus
import com.relay.app.call.group.e2ee.FrameOpenResult
import com.relay.app.call.group.e2ee.FrameSealResult
import com.relay.app.call.group.e2ee.GroupCallKeySchedule
import com.relay.app.call.group.e2ee.GroupCallReplayGuard
import com.relay.app.call.group.e2ee.GroupCallSecurityLabel
import com.relay.app.call.group.e2ee.GroupFrameCrypto
import com.relay.app.call.group.e2ee.ParticipantDevice
import com.relay.app.call.group.e2ee.ReplayDecision
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WP17 desktop frame E2EE: one [GroupCallE2ee] per live call, one [GroupFrameE2eeProvider] per epoch.
 *
 * The call key arrives ONLY inside the E2E-sealed `group_start` (`GroupAnnouncement.callKey`, base64 of 32 bytes at
 * `keyEpoch`) for joiners, and comes from this host's own SecureRandom for the starter. It is never sent to the room or
 * the Worker (they only see `secretHash` and the roster), never logged, never part of a REST body or of the UI state.
 * The only persisted copy is inside the invitation in the already-encrypted store (`DesktopMessageStore` is SQLCipher,
 * and its test asserts neither the join secret nor roster material appears in the file).
 *
 * Fail closed: no key, a malformed key, or an unusable epoch means this host neither publishes nor plays, and says so in
 * the call panel. A frame that fails to open, fails authentication, carries a retired/unknown key index, an unknown
 * sender, a wrong IV trailer or a replayed counter is DROPPED, never forwarded and never passed through.
 *
 * [GroupFrameE2eeProvider.seal]/[GroupFrameE2eeProvider.open] use no native types, so the exact byte-level behaviour the
 * webrtc-java transforms rely on is testable without a peer connection.
 */

/**
 * Byte-exact mirror of `GroupCallKeySchedule`'s frozen v1 derivation for ONE epoch, used as the [FrameKeyLookup] of
 * [GroupFrameCrypto.open].
 *
 * Why a mirror: core exposes a public constructor only for epoch 0 (`create`/`fromCallKeyHex`) plus the creator's
 * `rotateForRemoval` chain. A **joiner** that receives `keyEpoch = 1..255` in a sealed `group_start` has no public core
 * API to construct that epoch. Every label and parameter below is a public core constant
 * ([GroupCallKeySchedule.materialInfo], [GroupCallKeySchedule.ratchetInfo], [GroupCallKeySchedule.hkdfSalt],
 * [GroupCallKeySchedule.PBKDF2_ITERATIONS], [GroupCallKeySchedule.AES_KEY_BYTES]); the derivation was checked against the
 * WP17 fixed vectors (epoch 0 `46dfb902…`, epoch 1 `db9aae04…`) while wiring this host, and core changing its derivation
 * without this file fails those vectors.
 */
internal class GroupCallFrameKeys(
    callKey: ByteArray,
    val epoch: Int,
    private val retiredEpochs: Set<Int>,
) : FrameKeyLookup {
    private val callKey: ByteArray = callKey.copyOf()
    private val extractSalt: ByteArray = GroupCallKeySchedule.hkdfSalt()
    private val ratchetSalt: ByteArray = hkdf(this.callKey, extractSalt,
        GroupCallKeySchedule.ratchetInfo(epoch), 32)
    private val participants: MutableSet<ParticipantDevice> = java.util.Collections.synchronizedSet(LinkedHashSet())
    private val materials = ConcurrentHashMap<ParticipantDevice, ByteArray>()
    private val keys = ConcurrentHashMap<ParticipantDevice, ByteArray>()

    @Volatile private var closed = false

    init {
        require(epoch in 0..GroupCallKeySchedule.MAX_EPOCH) { "epoch out of range" }
        require(this.callKey.size == GroupCallKeySchedule.CALL_KEY_BYTES) { "call key must be 32 bytes" }
    }

    /** The participant devices this epoch currently covers (the room roster, plus this host). */
    fun setParticipants(next: Collection<ParticipantDevice>) {
        participants.clear()
        participants.addAll(next)
        materials.keys.retainAll(participants)
        keys.keys.retainAll(participants)
    }

    fun participants(): Set<ParticipantDevice> = participants.toSet()

    /** ASCII-hex PBKDF2 password (64 bytes) for [participant] — see `GroupCallKeySchedule.materialFor`. */
    fun materialFor(participant: ParticipantDevice): ByteArray {
        require(participant in participants) { "participant is not in this epoch" }
        return materials.computeIfAbsent(participant) {
            hkdf(callKey, extractSalt, GroupCallKeySchedule.materialInfo(epoch, it), 32)
                .joinToString("") { b -> "%02x".format(b) }
                .toByteArray(Charsets.US_ASCII)
        }.copyOf()
    }

    fun aesKeyFor(participant: ParticipantDevice): ByteArray {
        require(participant in participants) { "participant is not in this epoch" }
        return keys.computeIfAbsent(participant) { pbkdf2(materialFor(it), ratchetSalt) }.copyOf()
    }

    override fun classify(sender: ParticipantDevice, keyIndex: Int): FrameKeyStatus = when {
        keyIndex !in 0..GroupCallKeySchedule.MAX_EPOCH -> FrameKeyStatus.UNKNOWN_KEY_INDEX
        sender !in participants -> FrameKeyStatus.UNKNOWN_SENDER
        closed -> FrameKeyStatus.UNKNOWN_KEY_INDEX
        keyIndex == epoch -> FrameKeyStatus.CURRENT
        keyIndex in retiredEpochs -> FrameKeyStatus.RETIRED
        else -> FrameKeyStatus.UNKNOWN_KEY_INDEX
    }

    override fun keyFor(sender: ParticipantDevice, keyIndex: Int): ByteArray? =
        if (classify(sender, keyIndex) == FrameKeyStatus.CURRENT) aesKeyFor(sender) else null

    /** Wipes every derived secret. Only the owning [GroupCallE2ee] calls this (on end / epoch change). */
    fun destroy() {
        closed = true
        keys.values.forEach { it.fill(0) }; keys.clear()
        materials.values.forEach { it.fill(0) }; materials.clear()
        ratchetSalt.fill(0); extractSalt.fill(0); callKey.fill(0)
        participants.clear()
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous); mac.update(infoBytes); mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, take)
            offset += take
            counter++
        }
        return out
    }

    private fun pbkdf2(materialAsciiHex: ByteArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(materialAsciiHex.toString(Charsets.US_ASCII).toCharArray(), salt,
            GroupCallKeySchedule.PBKDF2_ITERATIONS, GroupCallKeySchedule.AES_KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * One epoch's frame transforms, handed to [DesktopSfuPeer]. A new instance is created for every epoch and for every
 * change of the sessionId → participant-device map; [DesktopSfuPeer] swaps it atomically, and [close] makes a stale
 * provider drop instead of decrypt. No failure path returns the input unchanged.
 */
class GroupFrameE2eeProvider internal constructor(
    val epoch: Int,
    val me: ParticipantDevice,
    private val keys: GroupCallFrameKeys,
    senders: Map<String, ParticipantDevice>,
    private val replay: GroupCallReplayGuard,
) : AutoCloseable {
    private val senders: Map<String, ParticipantDevice> = senders.toMap()
    private val myKey: ByteArray = keys.aesKeyFor(me)
    @Volatile private var closed = false

    /** The remote device that publishes [sessionId], or null when this host cannot attribute the stream. */
    fun senderFor(sessionId: String): ParticipantDevice? = if (closed) null else senders[sessionId]

    /** `hdr[1] | AES-GCM(rest) | IV[12] | 12 | epoch`; null when the plaintext is empty, oversized or this provider is dead. */
    fun seal(plaintext: ByteArray): ByteArray? {
        if (closed || plaintext.isEmpty()) return null
        val sealed = GroupFrameCrypto.seal(plaintext, epoch, myKey, GroupFrameCrypto.newIv())
        return (sealed as? FrameSealResult.Sealed)?.frame
    }

    /**
     * Authenticates and replay-checks one encoded frame. Admission happens only AFTER the tag verified, so a forged high
     * counter cannot advance the window and starve the real stream (WP17).
     */
    fun open(sealed: ByteArray, sender: ParticipantDevice, ssrc: Long, rtpSequenceNumber: Int): ByteArray? {
        if (closed) return null
        val opened = GroupFrameCrypto.open(sealed, sender, keys)
        if (opened !is FrameOpenResult.Opened) return null
        if (replay.admit(sender, opened.keyIndex, ssrc, rtpSequenceNumber) != ReplayDecision.ACCEPTED) return null
        return opened.plaintext
    }

    override fun close() {
        closed = true
        myKey.fill(0)
    }

    /**
     * Derives every participant's AES key ahead of the first frame. PBKDF2 is deliberately expensive (~100k iterations) and
     * the receiver transform runs on a WebRTC thread where a stall costs audio, so the runtime warms this off-thread.
     */
    internal fun warmUp() {
        if (closed) return
        for (p in keys.participants()) runCatching { keys.aesKeyFor(p) }
    }
}

/**
 * Per-call E2EE state: the epoch, its keys, the sessionId → participant-device map learned from the room roster, the
 * replay guard shared across the call, and the proofs of key possession that drive the security label.
 *
 * Construct only through [fromAnnouncement]. The provider snapshot is a volatile read, safe from the WebRTC threads;
 * [verified] and the device map are guarded by this instance.
 */
class GroupCallE2ee private constructor(
    val epoch: Int,
    val keyDistributionAuthenticated: Boolean,
    private val keys: GroupCallFrameKeys,
    val me: ParticipantDevice,
    private val replay: GroupCallReplayGuard,
) : AutoCloseable {
    private val devices = LinkedHashMap<String, ParticipantDevice>()   // sessionId -> publishing device
    private val participants = LinkedHashSet<ParticipantDevice>()      // every device the label must cover
    private val verified = HashMap<ParticipantDevice, Int>()
    @Volatile private var provider: GroupFrameE2eeProvider? = null
    @Volatile private var closed = false
    private val warmup = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "relay-group-call-e2ee").apply { isDaemon = true }
    }

    init {
        rebuild()
    }

    /** The current epoch's provider, or null once the call/epoch is torn down (the peer then drops everything). */
    fun provider(): GroupFrameE2eeProvider? = provider

    /** Epoch this host would mint next; null when the one-byte key index is exhausted. */
    fun nextEpoch(): Int? = if (epoch < GroupCallKeySchedule.MAX_EPOCH) epoch + 1 else null

    val alive: Boolean get() = !closed

    /**
     * Records (or clears, with a null pubkey/device) the device that publishes [sessionId] (stream attribution only).
     * Returns true when the map changed, so the caller re-attaches [provider].
     */
    @Synchronized
    fun setSessionDevice(sessionId: String, pubkey: String?, device: String?): Boolean {
        if (closed) return false
        val next = if (pubkey != null && device != null) runCatching { ParticipantDevice(pubkey, device) }.getOrNull() else null
        if (next == null) {
            if (devices.remove(sessionId) == null) return false
        } else {
            if (devices[sessionId] == next) return false
            devices[sessionId] = next
        }
        rebuild()
        return true
    }

    /** Replaces the whole sessionId → device mapping from the room roster; see [setSessionDevice]. */
    @Synchronized
    fun setSessionDevices(mapping: Collection<Triple<String, String, String>>): Boolean {
        if (closed) return false
        val next = LinkedHashMap<String, ParticipantDevice>()
        for ((session, pubkey, device) in mapping) {
            runCatching { ParticipantDevice(pubkey, device) }.getOrNull()?.let { next[session] = it }
        }
        if (next == devices) return false
        devices.clear(); devices.putAll(next)
        rebuild()
        return true
    }

    /** Drops one session's attribution (a peer left or stopped publishing). */
    @Synchronized
    fun removeSessionDevice(sessionId: String): Boolean {
        if (closed || devices.remove(sessionId) == null) return false
        rebuild()
        return true
    }

    /**
     * Sets every device the label must cover: this host plus **every** participant in the room, including one that has no
     * track or has not spoken yet. A device that is only in the roster can never be verified, so the end-to-end wording
     * stays withdrawn until it actually publishes an authenticated frame (WP17: "a silent participant is not verified").
     */
    @Synchronized
    fun setParticipants(next: Collection<ParticipantDevice>): Boolean {
        if (closed) return false
        val wanted = LinkedHashSet(next).also { it += me }
        if (wanted == participants) return false
        participants.clear(); participants += wanted
        verified.keys.retainAll(participants) // a departed device's proof must not count
        rebuild()
        return true
    }

    /** A participant device proved possession of its CURRENT epoch key (an inbound frame that authenticated). */
    @Synchronized
    fun verify(sender: ParticipantDevice, epoch: Int): Boolean {
        if (closed || epoch != this.epoch) return false
        if (sender !in expected()) return false
        verified[sender] = epoch
        return true
    }

    /** Every device the label must cover: this host plus every participant in the room. */
    @Synchronized
    fun expected(): Set<ParticipantDevice> = LinkedHashSet(participants).also { it += me }

    /**
     * Live coverage; proofs for a departed/unknown device or a previous epoch never count.
     *
     * `allParticipantsCapable` is true for a live keyed call: the desktop/DO protocol carries no frame-E2EE capability bit
     * (the server contract is out of this workstream's scope), so the decisive gate is per-device proof — a peer that cannot
     * do frame E2EE never authenticates a frame and therefore keeps the weaker label.
     */
    @Synchronized
    fun coverage(sendPathEncrypted: Boolean): E2eeCoverage {
        val currentEpoch = epoch
        val expected = expected()
        val proofs = verified.filterKeys { it in expected && verified[it] == currentEpoch }
        return E2eeCoverage(
            expected = expected,
            verifiedEpochs = proofs,
            currentEpoch = currentEpoch,
            allParticipantsCapable = !closed,
            keyDistributionAuthenticated = keyDistributionAuthenticated,
            sendPathEncrypted = sendPathEncrypted,
        )
    }

    fun label(sendPathEncrypted: Boolean): String = GroupCallSecurityLabel.label(coverage(sendPathEncrypted))

    @Synchronized
    private fun rebuild() {
        val old = provider
        if (closed) {
            provider = null
        } else {
            keys.setParticipants(expected())
            provider = GroupFrameE2eeProvider(epoch, me, keys, devices, replay)
        }
        old?.close()
        provider?.let { p -> runCatching { warmup.execute { p.warmUp() } } }
    }

    /** Tears the epoch down: transforms drop, keys are wiped, no proof survives. Idempotent. */
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            provider?.close(); provider = null
            devices.clear(); participants.clear(); verified.clear()
        }
        replay.clear()
        runCatching { warmup.shutdownNow() }
        keys.destroy()
    }

    companion object {
        /** Shown in the call panel when this host has no usable key. The call stays muted, never plaintext. */
        const val NO_KEY_MESSAGE =
            "This call has no end-to-end key on this computer, so the microphone and speaker stay off. Ask the host to restart the call."

        /**
         * Builds the runtime from a **verified** `group_start`: [GroupAnnouncement.callKey] is the only key input and is
         * accepted only with a usable epoch. Returns null (fail closed, never a plaintext fallback) for a keyless legacy
         * announcement, a key that is not 32 bytes, or an epoch outside 0..255.
         */
        fun fromAnnouncement(a: GroupAnnouncement, me: ParticipantDevice): GroupCallE2ee? {
            if (a.op != "group_start") return null
            val epoch = a.keyEpoch ?: return null
            val encoded = a.callKey ?: return null
            if (epoch !in 0..GroupCallKeySchedule.MAX_EPOCH.toLong()) return null
            val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
            if (decoded.size != GroupCallKeySchedule.CALL_KEY_BYTES) {
                decoded.fill(0)
                return null
            }
            return try {
                GroupCallE2ee(
                    epoch = epoch.toInt(),
                    keyDistributionAuthenticated = true, // the announcement was opened through the E2E seal
                    keys = GroupCallFrameKeys(decoded, epoch.toInt(), emptySet()),
                    me = me,
                    replay = GroupCallReplayGuard(),
                )
            } catch (_: IllegalArgumentException) {
                null
            } finally {
                decoded.fill(0)
            }
        }

        /** The device encoding used by the core schedule; a device id outside its alphabet cannot do frame E2EE. */
        fun selfDevice(pubkey: String, deviceId: String): ParticipantDevice? =
            runCatching { ParticipantDevice(pubkey, deviceId) }.getOrNull()
    }
}
