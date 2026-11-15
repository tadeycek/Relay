package com.relay.app.media

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.InputStream
import java.io.OutputStream
import java.io.File

/**
 * One-shot AES-256-GCM encryption of a media file with a fresh random key per file. The blob layout
 * is `iv(12) || ciphertext || tag(16)`. Pure JVM crypto (no Android types) so it is unit-testable.
 */
object MediaCrypto {

    const val PART_BYTES = 1024 * 1024
    const val MAX_CIPHER_BYTES = 3L * 1024 * 1024 * 1024 + PART_BYTES
    private val STREAM_CONTEXT = "Relay media v2".toByteArray(Charsets.UTF_8)

    /** Tink authenticates segment order, the final segment, and the stream's associated data. */
    fun streaming(key: ByteArray): StreamingAead {
        require(key.size == KEY_BYTES) { "Invalid media key" }
        return AesGcmHkdfStreaming(key, "HmacSha256", KEY_BYTES, PART_BYTES, 0)
    }

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun cipherSize(plainSize: Long): Long {
        require(plainSize in 0..MediaBody.MAX_MEDIA_BYTES)
        return AesGcmHkdfStreaming(ByteArray(KEY_BYTES),"HmacSha256",KEY_BYTES,PART_BYTES,0).expectedCiphertextSize(plainSize)
    }

    /** Preserve bulk writes while allowing the caller to fsync the underlying file after finalizing. */
    fun nonClosing(output: OutputStream): OutputStream = object : java.io.FilterOutputStream(output) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes,offset,length)
        override fun write(bytes: ByteArray) = output.write(bytes)
        override fun close() { flush() }
    }

    fun encryptStream(input: InputStream, output: OutputStream, key: ByteArray, associatedData: ByteArray = STREAM_CONTEXT,
                      progress: (Long) -> Unit = {}): Long {
        var count = 0L
        streaming(key).newEncryptingStream(output, associatedData).use { encrypted ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                count += n
                require(count <= MediaBody.MAX_MEDIA_BYTES) { "File exceeds 3 GiB" }
                encrypted.write(buffer, 0, n)
                progress(count)
            }
        }
        return count
    }

    fun decryptingStream(input: InputStream, keyBase64: String): InputStream =
        streaming(Base64.getDecoder().decode(keyBase64)).newDecryptingStream(input, STREAM_CONTEXT)

    fun sha256Hex(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

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
