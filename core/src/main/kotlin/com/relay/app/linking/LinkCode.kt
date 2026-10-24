package com.relay.app.linking

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Random

/**
 * The one-time linking code (05 §4, G1).
 *
 * The code is 32 random bytes (256 bits) written as 64 lowercase hex characters. That is exactly the
 * shape the server slice emits and hashes: `slot = sha256_hex(utf8(code))` and the desktop sends the
 * code itself as `x-relay-link-proof`. The code is **not** an encryption key: the bundle is
 * HPKE-sealed to the desktop's own ephemeral link public key, and the code only proves to the
 * unauthenticated `GET /v1/link/<slot>` that the caller scanned the QR/read the code.
 *
 * Human form groups the hex in eight groups of eight (`00010203-04050607-…`) and the QR form adds
 * `RELAYLINK:1:`. Both normalise back to the canonical lowercase hex, so the server always sees one
 * spelling. Pure and deterministic: no clocks, no storage, no platform types.
 */
object LinkCode {

    /** Raw entropy of a code. */
    const val BYTES = 32

    /** Canonical length: 32 bytes as lowercase hex. */
    const val LENGTH = 64

    const val QR_PREFIX = "RELAYLINK:1:"

    /**
     * Header the desktop sends on `GET /v1/link/<slot>`. The server's header allowlist is
     * case-insensitive; this is the spelling the server slice uses.
     */
    const val PROOF_HEADER = "x-relay-link-proof"

    private val HEX = Regex("[0-9a-f]{64}")
    private val RANDOM = SecureRandom()

    /** A fresh code from [random]. Pass an injectable source only for tests/DRBG integration. */
    fun generate(random: Random = RANDOM): String {
        val bytes = ByteArray(BYTES)
        random.nextBytes(bytes)
        return fromBytes(bytes)
    }

    /** Canonical lowercase hex for exactly [BYTES] bytes. Throws only on a programming error. */
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size == BYTES) { "link code needs $BYTES bytes, got ${bytes.size}" }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Normalises a human/QR code: strips separators and whitespace, lowercases, and requires the
     * canonical 64-hex form. Returns null otherwise, so two different strings can never be mistaken
     * for the same slot.
     */
    fun canonical(input: String?): String? {
        if (input == null) return null
        val cleaned = buildString {
            for (raw in input.trim()) {
                when (raw) {
                    '-', ' ', '\t', '\n', '\r', ':' -> Unit
                    else -> {
                        val c = raw.lowercaseChar()
                        if (c !in "0123456789abcdef") return null
                        append(c)
                    }
                }
            }
        }
        return cleaned.takeIf { HEX.matches(it) }
    }

    fun isValid(code: String?): Boolean = canonical(code) != null

    /** `00010203-04050607-…`, eight groups of eight, for reading out and typing. */
    fun human(code: String): String {
        val canonical = canonical(code) ?: throw IllegalArgumentException("not a link code")
        return canonical.chunked(8).joinToString("-")
    }

    /** QR payload. A scanner gets the canonical code without separators. */
    fun qr(code: String): String {
        val canonical = canonical(code) ?: throw IllegalArgumentException("not a link code")
        return QR_PREFIX + canonical
    }

    /** Extracts a code from a scanned QR string, or null if this is not a Relay link QR. */
    fun fromQr(text: String?): String? {
        val trimmed = text?.trim() ?: return null
        if (!trimmed.startsWith(QR_PREFIX)) return null
        return canonical(trimmed.substring(QR_PREFIX.length))
    }

    /** Raw 32 bytes, or null for an invalid code. */
    fun rawBytes(code: String?): ByteArray? {
        val canonical = canonical(code) ?: return null
        return ByteArray(BYTES) { canonical.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * `sha256(utf8(canonical code))` as 64 lowercase hex — the server's slot id and storage key.
     * (The server computes `sha256Hex(code)` over the hex text, not over the raw bytes.)
     */
    fun slotId(code: String): String {
        val canonical = canonical(code) ?: throw IllegalArgumentException("not a link code")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Value of [PROOF_HEADER]: the canonical code text (64 lowercase hex). URL-safe and
     * header-safe; the server hashes this string and compares it with the slot id.
     */
    fun proofValue(code: String): String =
        canonical(code) ?: throw IllegalArgumentException("not a link code")
}
