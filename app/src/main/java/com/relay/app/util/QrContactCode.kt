package com.relay.app.util

/**
 * Encoding for the QR contact-exchange code (Contacts -> QR icon). Never sent over SMS — this is
 * a distinct format from [SmsMessageParser]'s `TYPE:` wire protocol, used only for the in-person
 * QR scan/display flow. Base64's alphabet never contains `|`, and the name is capped/stripped of
 * pipes, so this stays safely pipe-delimited just like the SMS protocol's `LABEL:` field.
 */
object QrContactCode {

    private const val VERSION = 1
    // Match the version generically (not a hardcoded "1") so bumping VERSION doesn't silently break
    // decoding of freshly-encoded codes.
    private val REGEX = Regex("""RELAYQR:(\d+)\|PHONE:([^|]+)\|NAME:([^|]{0,30})\|KEY:([A-Za-z0-9+/=]+)""")

    /** Strip control/format chars (newlines, RTL-override, etc.) that could smuggle through a name. */
    private fun sanitize(s: String): String = s.replace(Regex("""[\p{Cc}\p{Cf}]"""), "").replace("|", "")

    data class ScannedContact(
        val phone: String,
        val name: String,
        val publicKeyBase64: String,
    )

    fun encode(phone: String, name: String, publicKeyBase64: String): String {
        val safeName = sanitize(name).take(30)
        val safePhone = sanitize(phone)
        return "RELAYQR:$VERSION|PHONE:$safePhone|NAME:$safeName|KEY:$publicKeyBase64"
    }

    fun decode(raw: String): ScannedContact? {
        val match = REGEX.find(raw.trim()) ?: return null
        val phone = sanitize(match.groupValues[2]).trim()
        val name = sanitize(match.groupValues[3]).trim()
        val key = match.groupValues[4]
        if (phone.isEmpty() || key.isEmpty()) return null
        return ScannedContact(
            phone = phone,
            name = name.ifEmpty { phone },
            publicKeyBase64 = key,
        )
    }
}
