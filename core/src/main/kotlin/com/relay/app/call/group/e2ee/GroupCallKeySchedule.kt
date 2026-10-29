package com.relay.app.call.group.e2ee

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WP17 group-call frame E2EE — versioned per-call key schedule.
 *
 * ## Frozen frame format (WP02 interop evidence, byte-for-byte)
 *
 * ```
 * frame = hdr[1] | AES-128-GCM(ciphertext || 16-byte tag) | IV[12] | ivLen[1]=12 | keyIndex[1]
 * hdr   = first byte of the Opus payload, clear, used as GCM AAD (the tag authenticates it)
 * ```
 * Overhead is +30 bytes per frame. `keyIndex` is a one-byte epoch selector in the trailer. Zero-length
 * frames carry no audio and are never emitted or accepted by this package; Android `FrameCryptor`
 * passes them through in clear at stream start and the host must drop them instead of forwarding them.
 *
 * ## Key derivation — fixed parameters, identical on Android and desktop
 *
 * Input is the 32-byte per-call `e2eeKey` distributed E2E in `group_start` (never to the SFU/server).
 * The schedule version is [SCHEDULE_VERSION] = 1 and is part of every derivation label.
 *
 * 1. `ratchetSalt = HKDF-SHA256(callKey, salt = HKDF_SALT, info = ratchetInfo(epoch), 32)`
 *    — call-wide for the epoch, because Android `FrameCryptorKeyProvider` takes the ratchet salt once
 *    at provider creation, not per participant.
 * 2. `materialRaw = HKDF-SHA256(callKey, salt = HKDF_SALT, info = materialInfo(epoch, sender, device), 32)`
 *    — per sender identity **and** per device, so two devices of one member never share a key.
 * 3. `material = lowercase hex ASCII of materialRaw` (64 bytes). The bytes handed to the providers are
 *    ASCII, not the raw digest.
 * 4. `aesKey = PBKDF2-HMAC-SHA256(password = material, salt = ratchetSalt, iterations = 100000,
 *    dkLen = 16 bytes)` — the exact parameters observed from the Android `FrameCryptor` PBKDF2 path.
 *    Always AES-128 even though the input material is 32 bytes.
 *
 * Why the ASCII-hex step: JCA `PBKDF2WithHmacSHA256` receives a `char[]` password and UTF-8-encodes it,
 * so it cannot faithfully hash arbitrary digest bytes. Android's native PBKDF2 hashes the raw password
 * bytes. Encoding the 32-byte digest as 64 ASCII hex characters makes both sides hash the same 64 bytes
 * (`setKey(..., material)` on Android receives those same ASCII bytes). Do not "optimise" this by
 * passing `materialRaw` to either side: the keys will not match.
 *
 * HKDF is RFC 5869 (extract-then-expand) over `Mac HmacSHA256`; it is implemented here rather than
 * pulled from a dependency. Exact labels are in [HKDF_SALT_TEXT], [materialInfo] and [ratchetInfo].
 *
 * ## Rotation and fail-closed epochs
 *
 * `keyIndex == epoch`, both in `0..255` (one byte in the trailer). [rotateForRemoval] requires a fresh
 * 32-byte call key distributed E2E to the remaining members; merely bumping the epoch while keeping the
 * old call key would let the removed member derive the new epoch, so that is rejected. The old epoch is
 * retained in [retiredEpochs] and [GroupCallEpochKeys] reports frames carrying it as RETIRED rather than
 * decrypting them. An index that is neither current nor retired is UNKNOWN. Both are rejected; this
 * package never falls back to plaintext.
 */
data class ParticipantDevice(val identity: String, val device: String) {
    init {
        require(HEX64.matches(identity)) { "identity must be 64 lowercase hex characters" }
        require(DEVICE.matches(device)) { "device must match [A-Za-z0-9_-]{1,64}" }
    }

    /** Stable id for Android `FrameCryptor` key-provider maps and desktop SSRC→participant maps. */
    fun frameCryptorParticipantId(): String = "$identity:$device"

    companion object {
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val DEVICE = Regex("[A-Za-z0-9_-]{1,64}")

        fun parse(participantId: String): ParticipantDevice {
            val at = participantId.indexOf(':')
            require(at > 0 && at == participantId.lastIndexOf(':')) { "invalid participant id" }
            return ParticipantDevice(participantId.substring(0, at), participantId.substring(at + 1))
        }
    }
}

/** Immutable snapshot of one call epoch. Construct through [create] / [fromCallKeyHex] / [rotateForRemoval]. */
class GroupCallKeySchedule private constructor(
    val version: Int,
    val epoch: Int,
    private val callKey: ByteArray,
    ratchetSaltBytes: ByteArray,
    participants: Collection<ParticipantDevice>,
    val retiredEpochs: Set<Int>,
) {
    private val ratchetSalt: ByteArray = ratchetSaltBytes.copyOf()
    val participants: List<ParticipantDevice> = participants.distinct().sortedWith(
        compareBy({ it.identity }, { it.device }))

    /** The one-byte trailer selector for this epoch. */
    val keyIndex: Int get() = epoch

    private val materials = ConcurrentHashMap<ParticipantDevice, ByteArray>()
    private val keys = ConcurrentHashMap<ParticipantDevice, ByteArray>()

    init {
        require(version == SCHEDULE_VERSION) { "unsupported key schedule version" }
        require(epoch in 0..MAX_EPOCH) { "epoch out of range" }
        require(this.ratchetSalt.size == 32) { "ratchet salt must be 32 bytes" }
        require(this.participants.isNotEmpty()) { "a call schedule needs at least one participant device" }
        require(retiredEpochs.all { it in 0 until epoch }) { "retired epochs must precede the current epoch" }
        require(callKey.size == CALL_KEY_BYTES) { "call key must be 32 bytes" }
    }

    fun ratchetSaltCopy(): ByteArray = ratchetSalt.copyOf()

    /** Lowercase hex of the epoch salt, for cross-platform vectors and diagnostics. */
    fun ratchetSaltHex(): String = ratchetSalt.toHex()

    /**
     * ASCII-hex PBKDF2 password for [participant] (64 bytes). This is exactly what the Android host must
     * pass to `FrameCryptorKeyProvider.setKey(participantId, keyIndex, material)`.
     */
    fun materialFor(participant: ParticipantDevice): ByteArray {
        requireParticipant(participant)
        return materials.computeIfAbsent(participant) {
            hkdf(callKey, hkdfSalt(), materialInfo(epoch, it), 32).toHex().toByteArray(Charsets.US_ASCII)
        }.copyOf()
    }

    /** Derived 16-byte AES-128 key for [participant] in this epoch. Cached; PBKDF2 is never per-frame. */
    fun aesKeyFor(participant: ParticipantDevice): ByteArray {
        requireParticipant(participant)
        return keys.computeIfAbsent(participant) { pbkdf2(materialFor(it), ratchetSalt) }.copyOf()
    }

    /** Key lookup bound to this epoch and its retired history. */
    fun keyRing(): GroupCallEpochKeys = GroupCallEpochKeys(this)

    /**
     * New epoch for the members that remain after a removal. [newCallKey] MUST be freshly generated and
     * delivered E2E to the remaining devices only; it must differ from the current call key. The current
     * epoch becomes retired and is never used for decryption again.
     */
    fun rotateForRemoval(
        remaining: Collection<ParticipantDevice>,
        newCallKey: ByteArray,
    ): GroupCallKeySchedule {
        require(epoch < MAX_EPOCH) { "epoch exhausted; start a new call" }
        require(newCallKey.size == CALL_KEY_BYTES) { "new call key must be 32 bytes" }
        require(!MessageDigest.isEqual(newCallKey, callKey)) {
            "rotation requires a fresh call key; reusing it lets a removed member derive the new epoch"
        }
        val keep = remaining.distinct()
        require(keep.isNotEmpty()) { "cannot rotate a call with no remaining participants" }
        require(keep.all { it in participants }) { "remaining participants must be a subset of the current epoch" }
        require(keep.size < participants.size) { "removal rotation must actually remove a device" }
        return GroupCallKeySchedule(
            version, epoch + 1, newCallKey.copyOf(), newRatchetSalt(newCallKey, epoch + 1),
            keep, retiredEpochs + epoch)
    }

    private fun requireParticipant(p: ParticipantDevice) {
        require(p in participants) { "participant is not in this call epoch" }
    }

    internal fun materialInfoFor(p: ParticipantDevice): String = materialInfo(epoch, p)

    companion object {
        const val SCHEDULE_VERSION = 1
        const val CALL_KEY_BYTES = 32
        const val AES_KEY_BYTES = 16
        const val PBKDF2_ITERATIONS = 100_000
        const val MAX_EPOCH = 255
        const val HKDF_SALT_TEXT = "relay-call-frame-v1/extract"

        fun create(callKey: ByteArray, participants: Collection<ParticipantDevice>): GroupCallKeySchedule =
            createAtEpoch(callKey, participants, 0, emptySet())

        /**
         * A schedule for an existing [epoch], built from the call key a device received for it.
         *
         * A device that joins after a re-key holds the new call key and the new epoch but not the history that
         * produced them, so it cannot call [rotateForRemoval]. Everything the derivation needs is the call key,
         * the epoch and the device itself: the epoch only enters the HKDF labels ([ratchetInfo],
         * [materialInfo]), never the participant set. This is therefore byte-identical to the schedule the
         * members already on [epoch] hold — same ratchet salt, same per-device keys — and a late joiner can
         * adopt the current epoch instead of failing closed.
         *
         * [retiredEpochs] is deliberately empty: this device never held those keys, and an index outside the
         * current epoch is reported as UNKNOWN_KEY_INDEX, which is rejected exactly like RETIRED.
         */
        fun create(
            callKey: ByteArray,
            epoch: Int,
            participants: Collection<ParticipantDevice>,
        ): GroupCallKeySchedule = createAtEpoch(callKey, participants, epoch, emptySet())

        fun fromCallKeyHex(callKeyHex: String, participants: Collection<ParticipantDevice>): GroupCallKeySchedule {
            require(callKeyHex.length == CALL_KEY_BYTES * 2 && callKeyHex.all { it in "0123456789abcdef" }) {
                "call key must be 64 lowercase hex characters"
            }
            return create(callKeyHex.hexToBytes(), participants)
        }

        internal fun createAtEpoch(
            callKey: ByteArray,
            participants: Collection<ParticipantDevice>,
            epoch: Int,
            retired: Set<Int>,
        ): GroupCallKeySchedule {
            require(callKey.size == CALL_KEY_BYTES) { "call key must be 32 bytes" }
            return GroupCallKeySchedule(SCHEDULE_VERSION, epoch, callKey.copyOf(),
                newRatchetSalt(callKey, epoch), participants, retired)
        }

        /** Exact, documented label. Reproduce this string verbatim on any other platform. */
        fun materialInfo(epoch: Int, p: ParticipantDevice): String =
            "relay-call-frame|v=1|epoch=$epoch|sender=${p.identity}|device=${p.device}|purpose=frame-material"

        /** Exact, documented label. Reproduce this string verbatim on any other platform. */
        fun ratchetInfo(epoch: Int): String =
            "relay-call-frame|v=1|epoch=$epoch|purpose=ratchet-salt"

        fun hkdfSalt(): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(HKDF_SALT_TEXT.toByteArray(Charsets.US_ASCII))

        private fun newRatchetSalt(callKey: ByteArray, epoch: Int): ByteArray =
            hkdf(callKey, hkdfSalt(), ratchetInfo(epoch), 32)

        /** RFC 5869 HKDF-SHA256. */
        internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
            require(length in 1..(32 * 255)) { "invalid HKDF length" }
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
                mac.update(previous)
                mac.update(infoBytes)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                val take = minOf(previous.size, length - offset)
                previous.copyInto(out, offset, 0, take)
                offset += take
                counter++
            }
            return out
        }

        internal fun pbkdf2(materialAsciiHex: ByteArray, salt: ByteArray): ByteArray {
            val password = materialAsciiHex.toString(Charsets.US_ASCII).toCharArray()
            val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_BYTES * 8)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}

enum class FrameKeyStatus { CURRENT, RETIRED, UNKNOWN_KEY_INDEX, UNKNOWN_SENDER }

/** Per-epoch view used by [GroupFrameCrypto.open]. Never returns a key for a retired or unknown index. */
class GroupCallEpochKeys internal constructor(val schedule: GroupCallKeySchedule) : FrameKeyLookup {
    val epoch: Int get() = schedule.epoch
    val keyIndex: Int get() = schedule.keyIndex

    override fun classify(sender: ParticipantDevice, keyIndex: Int): FrameKeyStatus = when {
        keyIndex !in 0..GroupCallKeySchedule.MAX_EPOCH -> FrameKeyStatus.UNKNOWN_KEY_INDEX
        sender !in schedule.participants -> FrameKeyStatus.UNKNOWN_SENDER
        keyIndex == schedule.epoch -> FrameKeyStatus.CURRENT
        keyIndex in schedule.retiredEpochs -> FrameKeyStatus.RETIRED
        else -> FrameKeyStatus.UNKNOWN_KEY_INDEX
    }

    override fun keyFor(sender: ParticipantDevice, keyIndex: Int): ByteArray? =
        if (classify(sender, keyIndex) == FrameKeyStatus.CURRENT) schedule.aesKeyFor(sender) else null
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0 && all { it in "0123456789abcdefABCDEF" }) { "invalid hex" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
