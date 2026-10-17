package com.relay.app.media

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-shot AES-256-GCM encryption of a media file with a fresh random key per file. The blob layout
 * is `iv(12) || ciphertext || tag(16)`. Pure JVM crypto (no Android types) so it is unit-testable.
 */
object MediaCrypto {

    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    class Encrypted(val blob: ByteArray, val keyBase64: String) {
        /** SHA-256 of [blob] in lowercase hex: the blob's address on a Blossom server. */
        val sha256Hex: String get() = sha256Hex(blob)
    }

    fun encrypt(plain: ByteArray): Encrypted {
        val key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return Encrypted(iv + ct, Base64.getEncoder().encodeToString(key))
    }

    /** Returns the plaintext, or null if the blob is malformed, the key is wrong, or it was tampered with. */
    fun decrypt(blob: ByteArray, keyBase64: String): ByteArray? {
        if (blob.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val key = Base64.getDecoder().decode(keyBase64)
            if (key.size != KEY_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_BYTES)),
            )
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (e: Exception) {
            null
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
