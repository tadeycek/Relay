package com.relay.app.call.group.e2ee

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Key lookup used by [GroupFrameCrypto.open]; [GroupCallEpochKeys] is the production implementation. */
interface FrameKeyLookup {
    fun classify(sender: ParticipantDevice, keyIndex: Int): FrameKeyStatus

    /** Must return null for every index that is not CURRENT. */
    fun keyFor(sender: ParticipantDevice, keyIndex: Int): ByteArray?
}

enum class FrameSealReject {
    EMPTY_FRAME,
    TOO_LARGE,
    INVALID_KEY,
    KEY_INDEX_OUT_OF_RANGE,
    INVALID_IV,
}

enum class FrameReject {
    TOO_SHORT,
    TOO_LARGE,
    BAD_IV_LENGTH,
    UNKNOWN_KEY_INDEX,
    RETIRED_KEY_INDEX,
    UNKNOWN_SENDER,
    KEY_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    MALFORMED,
}

sealed interface FrameSealResult {
    class Sealed(val frame: ByteArray, val keyIndex: Int) : FrameSealResult
    data class Rejected(val reason: FrameSealReject) : FrameSealResult
}

sealed interface FrameOpenResult {
    class Opened(val plaintext: ByteArray, val keyIndex: Int) : FrameOpenResult
    data class Rejected(val reason: FrameReject) : FrameOpenResult
}

/**
 * WP17 frame seal/open for one Opus payload. Pure JCA: `AES/GCM/NoPadding` with a 128-bit tag.
 *
 * The layout is frozen by the WP02 interop evidence and must match Android `FrameCryptor` byte for byte:
 * `hdr[1] (clear, AAD) | ciphertext | tag[16] | IV[12] | ivLen[1]=12 | keyIndex[1]`.
 *
 * Fail-closed rules enforced here:
 * - a missing, wrong-sized, retired or unknown key never produces or returns plaintext;
 * - a frame shorter than 31 bytes (1 clear + 16 tag + 14 trailer) is rejected, including the zero-length
 *   frames `FrameCryptor` passes through at stream start — those contain no audio and the host must drop
 *   them, never forward them;
 * - a frame larger than [MAX_SEALED_BYTES] is rejected rather than buffered;
 * - `ivLen` in the trailer must be exactly 12;
 * - AES-GCM tag failure is reported as AUTHENTICATION_FAILED and the plaintext is discarded.
 *
 * IV uniqueness is the sender's responsibility. Android `FrameCryptor` generates its own. On desktop use
 * [newIv] (SecureRandom, 96 bits) per frame; do not reuse an IV under one key. [ivFor] is provided for
 * deterministic tests/hosts that already have a per-frame unique SSRC+timestamp pair.
 */
object GroupFrameCrypto {
    const val VERSION = 1
    const val HEADER_BYTES = 1
    const val TAG_BYTES = 16
    const val IV_BYTES = 12
    const val TRAILER_BYTES = 14
    const val OVERHEAD_BYTES = 30

    /** Opus payload upper bound (RFC 6716). */
    const val MAX_PLAINTEXT_BYTES = 1275
    const val MAX_SEALED_BYTES = MAX_PLAINTEXT_BYTES + OVERHEAD_BYTES
    const val MIN_SEALED_BYTES = HEADER_BYTES + TAG_BYTES + TRAILER_BYTES

    private val random = SecureRandom()

    fun newIv(): ByteArray = ByteArray(IV_BYTES).also { random.nextBytes(it) }

    /** 4-byte SSRC + 8-byte RTP timestamp; unique per packet as long as the host timestamp is. */
    fun ivFor(ssrc: Long, rtpTimestamp: Long): ByteArray {
        require(ssrc in 0..0xFFFF_FFFFL) { "ssrc must be a 32-bit unsigned value" }
        require(rtpTimestamp >= 0) { "timestamp must be non-negative" }
        val iv = ByteArray(IV_BYTES)
        for (i in 0..3) iv[i] = (ssrc ushr (24 - 8 * i)).toByte()
        for (i in 0..7) iv[4 + i] = (rtpTimestamp ushr (56 - 8 * i)).toByte()
        return iv
    }

    fun seal(plaintext: ByteArray, keyIndex: Int, key: ByteArray?, iv: ByteArray): FrameSealResult {
        if (plaintext.isEmpty()) return FrameSealResult.Rejected(FrameSealReject.EMPTY_FRAME)
        if (plaintext.size > MAX_PLAINTEXT_BYTES) return FrameSealResult.Rejected(FrameSealReject.TOO_LARGE)
        if (keyIndex !in 0..GroupCallKeySchedule.MAX_EPOCH) {
            return FrameSealResult.Rejected(FrameSealReject.KEY_INDEX_OUT_OF_RANGE)
        }
        if (key == null || key.size != GroupCallKeySchedule.AES_KEY_BYTES) {
            return FrameSealResult.Rejected(FrameSealReject.INVALID_KEY)
        }
        if (iv.size != IV_BYTES) return FrameSealResult.Rejected(FrameSealReject.INVALID_IV)

        val aad = byteArrayOf(plaintext[0])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(aad)
        val ciphertext = try {
            // Encrypt everything after the clear TOC byte; the output already carries the 16-byte tag.
            cipher.doFinal(plaintext, HEADER_BYTES, plaintext.size - HEADER_BYTES)
        } catch (e: Exception) {
            return FrameSealResult.Rejected(FrameSealReject.INVALID_KEY)
        }

        val out = ByteArray(plaintext.size + OVERHEAD_BYTES)
        out[0] = plaintext[0]
        ciphertext.copyInto(out, HEADER_BYTES)
        iv.copyInto(out, HEADER_BYTES + ciphertext.size)
        out[out.size - 2] = IV_BYTES.toByte()
        out[out.size - 1] = keyIndex.toByte()
        return FrameSealResult.Sealed(out, keyIndex)
    }

    fun open(frame: ByteArray, sender: ParticipantDevice, keys: FrameKeyLookup): FrameOpenResult {
        if (frame.size < MIN_SEALED_BYTES) return FrameOpenResult.Rejected(FrameReject.TOO_SHORT)
        if (frame.size > MAX_SEALED_BYTES) return FrameOpenResult.Rejected(FrameReject.TOO_LARGE)
        val ivLen = frame[frame.size - 2].toInt() and 0xFF
        if (ivLen != IV_BYTES) return FrameOpenResult.Rejected(FrameReject.BAD_IV_LENGTH)
        val keyIndex = frame[frame.size - 1].toInt() and 0xFF

        when (keys.classify(sender, keyIndex)) {
            FrameKeyStatus.RETIRED -> return FrameOpenResult.Rejected(FrameReject.RETIRED_KEY_INDEX)
            FrameKeyStatus.UNKNOWN_KEY_INDEX -> return FrameOpenResult.Rejected(FrameReject.UNKNOWN_KEY_INDEX)
            FrameKeyStatus.UNKNOWN_SENDER -> return FrameOpenResult.Rejected(FrameReject.UNKNOWN_SENDER)
            FrameKeyStatus.CURRENT -> Unit
        }
        val key = keys.keyFor(sender, keyIndex)
            ?: return FrameOpenResult.Rejected(FrameReject.KEY_UNAVAILABLE)

        val ciphertextEnd = frame.size - TRAILER_BYTES
        val iv = frame.copyOfRange(ciphertextEnd, ciphertextEnd + IV_BYTES)
        val aad = byteArrayOf(frame[0])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, iv))
        cipher.updateAAD(aad)
        val body = try {
            cipher.doFinal(frame, HEADER_BYTES, ciphertextEnd - HEADER_BYTES)
        } catch (e: AEADBadTagException) {
            return FrameOpenResult.Rejected(FrameReject.AUTHENTICATION_FAILED)
        } catch (e: Exception) {
            return FrameOpenResult.Rejected(FrameReject.MALFORMED)
        }

        val plaintext = ByteArray(HEADER_BYTES + body.size)
        plaintext[0] = frame[0]
        body.copyInto(plaintext, HEADER_BYTES)
        return FrameOpenResult.Opened(plaintext, keyIndex)
    }
}
