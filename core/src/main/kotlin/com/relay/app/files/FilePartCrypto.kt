package com.relay.app.files

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned raw file parts; legacy photo MediaContainer is deliberately unchanged.
 * One-shot ByteBuffer calls are copy-safe for overlapping input/output and avoid incremental provider buffer growth.
 * Providers may allocate direct/native scratch buffers; thread allocation is NOT a heap/native peak bound.
 * One app message array is reused in place for ciphertext/plaintext; provider scratch is additional. Decryption stages privately until hash+tag pass.
 */
/** What the encryptor produced for the body: SHA-256 (lowercase hex) of the stored blob and its byte length. */
data class StoredPart(val sha256Hex: String, val storedBytes: Int)

class FilePartEncryptor(
    key: ByteArray,
    /** Fills the given 12-byte array with a fresh IV. Tests inject a fixed source; production uses [SecureRandom]. */
    private val ivSource: (ByteArray) -> Unit = SECURE::nextBytes,
) {
    private val keySpec: SecretKeySpec
    private val usedIvs = HashSet<String>()

    init {
        require(key.size == 32) { "File key must be 32 bytes" }
        keySpec = SecretKeySpec(key.copyOf(), "AES")
    }

    /** Reads exactly plainLength. Partial ciphertext on failure is unusable and must be discarded.
     * Persist completed ciphertext and reuse it on retry; do not re-encrypt with the same IV.
     */
    fun encryptPart(plain: InputStream, plainLength: Int, sink: OutputStream): StoredPart {
        require(plainLength in 0..FilePartPlan.PART_PLAIN_BYTES) { "Part length out of range" }
        val iv = freshIv()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val payloadLength = FilePartPlan.CRYPTO_OVERHEAD + plainLength
        val out = RawPartSink(sink, payloadLength)
        // One bounded, reusable message array. One-shot JCA avoids provider update buffering/growth.
        // Input and output overlap intentionally: JCA's ByteBuffer API guarantees copy-safe overlap (distinct views).
        val message = ByteArray(plainLength + 16)
        try {
            var offset = 0
            while (offset < plainLength) {
                val n = plain.read(message, offset, minOf(CHUNK_BYTES, plainLength - offset))
                if (n < 0) throw EOFException("Source ended early")
                if (n > 0) offset += n
            }
            val written = cipher.doFinal(ByteBuffer.wrap(message, 0, plainLength).slice(), ByteBuffer.wrap(message).slice())
            check(written == plainLength + 16)
            out.header(); out.payload(iv, 0, iv.size); out.payload(message, 0, written)
            return out.finish(FilePartPlan.storedSize(plainLength))
        } finally { message.fill(0) }
    }

    private fun freshIv(): ByteArray {
        val iv = ByteArray(12)
        repeat(8) {
            ivSource(iv)
            if (synchronized(usedIvs) { usedIvs.add(iv.joinToString("") { b -> "%02x".format(b) }) }) return iv
        }
        throw IllegalStateException("IV source repeated; refusing to reuse an IV with this key")
    }

    private class RawPartSink(private val sink: OutputStream, private val payloadLength: Int) {
        private val md = MessageDigest.getInstance("SHA-256")
        private var count = 0
        private var payloadLeft = payloadLength
        private fun raw(bytes: ByteArray, offset: Int, length: Int) {
            var off = offset; var left = length
            while (left > 0) { val n = minOf(CHUNK_BYTES, left); sink.write(bytes, off, n); md.update(bytes, off, n); count += n; off += n; left -= n }
        }
        fun header() = raw(RAW_HEADER, 0, RAW_HEADER.size)
        fun payload(bytes: ByteArray, off: Int, len: Int) { check(len <= payloadLeft); raw(bytes, off, len); payloadLeft -= len }
        fun finish(expectedStored: Int): StoredPart {
            check(payloadLeft == 0 && count == expectedStored)
            sink.flush(); return StoredPart(fileHex(md.digest()), count)
        }
    }
    companion object {
        private val SECURE = SecureRandom()
        const val CHUNK_BYTES = 64 * 1024
        const val UPLOAD_CONTENT_TYPE = "application/octet-stream"
        internal val RAW_HEADER = byteArrayOf(82, 76, 89, 70, 1) // ASCII RLYF, version 1
    }

}

sealed interface PartDecryptResult {
    data class Ok(val plainBytes: Int) : PartDecryptResult
    data class Failed(val reason: PartFailure) : PartDecryptResult
}

enum class PartFailure {
    /** Stream length (or the declared size / plaintext length) is not what the body and plan say. */
    BAD_SIZE,
    /** SHA-256 of the received blob is not the one the body declares. Checked before anything else is trusted. */
    HASH_MISMATCH,
    /** The raw magic/version is not supported. */
    BAD_CONTAINER,
    /** Wrong key or modified IV/ciphertext/tag. */
    AUTH_FAILED,
    /** Reading the stored blob failed (network etc.). */
    IO_ERROR,
}

class FilePartDecryptor(key: ByteArray, private val staging: FileStaging = FileStaging.DEFAULT) {
    private val keySpec: SecretKeySpec
    init { require(key.size == 32) { "File key must be 32 bytes" }; keySpec = SecretKeySpec(key.copyOf(), "AES") }

    /** Bounded streams to private disk, with one reused message array. JCA provider scratch is provider-dependent.
     * Hash and GCM tag must both pass; both stages must be removed before any final sink write.
     * Source/auth failures return Failed. Sink and cleanup exceptions propagate, including suppressed cleanup errors.
     */
    fun decryptPart(stored: InputStream, expected: FilePart, plainLength: Int, sink: OutputStream): PartDecryptResult {
        if (plainLength !in 0..FilePartPlan.PART_PLAIN_BYTES || expected.storedBytes != FilePartPlan.storedSize(plainLength))
            return PartDecryptResult.Failed(PartFailure.BAD_SIZE)
        staging.create().use { encrypted ->
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(WRITE_CHUNK_BYTES)
            try {
                encrypted.output().use { out ->
                    var left = expected.storedBytes
                    while (left > 0) {
                        val n = stored.read(buffer, 0, minOf(left, buffer.size))
                        if (n < 0) return PartDecryptResult.Failed(PartFailure.BAD_SIZE)
                        if (n == 0) continue
                        md.update(buffer, 0, n); out.write(buffer, 0, n); left -= n
                    }
                    if (stored.read() != -1) return PartDecryptResult.Failed(PartFailure.BAD_SIZE)
                }
            } catch (e: IOException) { return PartDecryptResult.Failed(PartFailure.IO_ERROR) }
            if (!MessageDigest.isEqual(md.digest(), hexToBytes(expected.sha256Hex)))
                return PartDecryptResult.Failed(PartFailure.HASH_MISMATCH)
            staging.create().use { plaintext ->
                try {
                    encrypted.input().use { input ->
                        fun readExact(n: Int): ByteArray {
                            val bytes = ByteArray(n); var offset = 0
                            while (offset < n) { val got = input.read(bytes, offset, n - offset); if (got < 0) throw EOFException(); if (got > 0) offset += got }
                            return bytes
                        }
                        if (!readExact(FilePartEncryptor.RAW_HEADER.size).contentEquals(FilePartEncryptor.RAW_HEADER))
                            return PartDecryptResult.Failed(PartFailure.BAD_CONTAINER)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, readExact(12)))
                        // A single app array is first ciphertext, then authenticated plaintext in place.
                        // Do NOT use update: Conscrypt and SunJCE may buffer full messages internally there.
                        val message = ByteArray(plainLength + 16)
                        try {
                            var offset = 0
                            while (offset < message.size) {
                                val n = input.read(message, offset, minOf(WRITE_CHUNK_BYTES, message.size - offset))
                                if (n < 0) return PartDecryptResult.Failed(PartFailure.BAD_SIZE)
                                if (n > 0) offset += n
                            }
                            if (input.read() != -1) return PartDecryptResult.Failed(PartFailure.BAD_SIZE)
                            val written = cipher.doFinal(ByteBuffer.wrap(message).slice(), ByteBuffer.wrap(message).slice())
                            if (written != plainLength) return PartDecryptResult.Failed(PartFailure.AUTH_FAILED)
                            plaintext.output().use { out ->
                                var off = 0
                                while (off < written) { val n = minOf(WRITE_CHUNK_BYTES, written - off); out.write(message, off, n); off += n }
                            }
                        } finally { message.fill(0) }

                    }
                } catch (e: GeneralSecurityException) { return PartDecryptResult.Failed(PartFailure.AUTH_FAILED) }
                catch (e: IOException) { return PartDecryptResult.Failed(PartFailure.IO_ERROR) }
                finally { buffer.fill(0) }
                encrypted.close()
                plaintext.releaseForRead().use { copyFileBytes(it, sink) }
                sink.flush()
                return PartDecryptResult.Ok(plainLength)
            }
        }
    }

    private fun hexToBytes(s: String): ByteArray {
        if (s.length != 64 || s.any { it !in '0'..'9' && it !in 'a'..'f' }) return ByteArray(0)
        return ByteArray(32) { ((Character.digit(s[2 * it], 16) shl 4) or Character.digit(s[2 * it + 1], 16)).toByte() }
    }

    companion object {
        const val WRITE_CHUNK_BYTES = 64 * 1024


    }
}
