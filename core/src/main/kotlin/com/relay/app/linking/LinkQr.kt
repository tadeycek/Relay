package com.relay.app.linking

/**
 * The QR payload a desktop shows and a phone scans (05 §4).
 *
 * The code alone is not enough to link: the phone must seal the bundle to the **desktop's**
 * ephemeral HPKE key, and must enrol the **desktop's** device-authentication key, so both public
 * keys and a device id travel with the code. Putting them in the QR keeps the server ignorant of
 * them and leaves the slot id (`sha256(code)`) unchanged.
 *
 * Wire form (all ASCII, no whitespace):
 *
 * ```
 * RELAYLINK:2:<code>:<deviceId>:<ephemeralPub>:<deviceAuthPub>
 * ```
 *
 * - `code`      64 lowercase hex ([LinkCode]).
 * - `deviceId`  32 lowercase hex (`[0-9a-f]{32}`), the id the phone enrols. The server's linked-device id
 *   pattern is exactly this; 16 hex was rejected with 400 against the real Worker.
 * - `ephemeralPub` / `deviceAuthPub` are unpadded base64url public keys (Tink keyset bytes),
 *   bounded to [MAX_PUBLIC_KEY_CHARS] each.
 *
 * Version 1 (`RELAYLINK:1:<code>`, code only) is still parsed so a stale QR gives a clear
 * "re-scan from the desktop app" failure rather than a silent bad seal: [Payload] carries nullable
 * keys and [isLinkable] is false for it.
 *
 * Pure: no I/O, no platform types, no clocks. Every decode failure returns null; nothing throws on
 * hostile input.
 */
object LinkQr {

    const val VERSION = 2
    private const val PREFIX = "RELAYLINK:"
    private const val LEGACY_PREFIX = "RELAYLINK:1:"

    /** Unpadded base64url of a public key; Tink keyset bytes are ~120 B, so this is generous. */
    const val MAX_PUBLIC_KEY_CHARS = 256
    const val DEVICE_ID_CHARS = 32

    private val DEVICE_ID = Regex("[0-9a-f]{32}")
    private val B64URL = Regex("[A-Za-z0-9_-]{16,${MAX_PUBLIC_KEY_CHARS}}")

    /**
     * A scanned desktop invitation. [ephemeralPublicKey] and [deviceAuthPublicKey] are null only for
     * a legacy version-1 QR, which cannot be linked.
     */
    data class Payload(
        val code: String,
        val deviceId: String?,
        val ephemeralPublicKey: String?,
        val deviceAuthPublicKey: String?,
    ) {
        /** True when the phone has everything it needs to seal a bundle and enrol the device. */
        val isLinkable: Boolean
            get() = deviceId != null && ephemeralPublicKey != null && deviceAuthPublicKey != null
    }

    /** Builds the QR text a desktop displays. Throws only on a programming error. */
    fun encode(code: String, deviceId: String, ephemeralPublicKey: String, deviceAuthPublicKey: String): String {
        val canonical = LinkCode.canonical(code) ?: throw IllegalArgumentException("not a link code")
        require(DEVICE_ID.matches(deviceId)) { "device id must be 32 lowercase hex" }
        require(B64URL.matches(ephemeralPublicKey)) { "ephemeral key is not base64url" }
        require(B64URL.matches(deviceAuthPublicKey)) { "device auth key is not base64url" }
        require(ephemeralPublicKey != deviceAuthPublicKey) { "the two desktop keys must differ" }
        return "$PREFIX$VERSION:$canonical:$deviceId:$ephemeralPublicKey:$deviceAuthPublicKey"
    }

    /**
     * Parses a scanned string. Returns null for anything that is not a Relay link QR, including
     * wrong prefixes, extra fields, oversize keys and non-canonical codes.
     */
    fun decode(text: String?): Payload? {
        val trimmed = text?.trim() ?: return null
        if (!trimmed.startsWith(PREFIX)) return null
        if (trimmed.length > 1024) return null

        if (trimmed.startsWith(LEGACY_PREFIX)) {
            val code = LinkCode.canonical(trimmed.substring(LEGACY_PREFIX.length)) ?: return null
            return Payload(code, null, null, null)
        }

        val fields = trimmed.split(':')
        if (fields.size != 6) return null
        if (fields[0] != "RELAYLINK") return null
        if (fields[1] != VERSION.toString()) return null
        val code = LinkCode.canonical(fields[2]) ?: return null
        val deviceId = fields[3].takeIf { DEVICE_ID.matches(it) } ?: return null
        val ephemeral = fields[4].takeIf { B64URL.matches(it) } ?: return null
        val deviceAuth = fields[5].takeIf { B64URL.matches(it) } ?: return null
        if (ephemeral == deviceAuth) return null
        return Payload(code, deviceId, ephemeral, deviceAuth)
    }
}
