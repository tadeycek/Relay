package com.relay.app.util

/**
 * Encoding for the QR contact-exchange code (Contacts -> QR icon). Never sent over SMS — this is
 * a distinct format from [SmsMessageParser]'s `TYPE:` wire protocol, used only for the in-person
 * QR scan/display flow. Base64's alphabet never contains `|`, and the name is capped/stripped of
 * pipes, so this stays safely pipe-delimited just like the SMS protocol's `LABEL:` field.
 */
object QrContactCode {

    private const val VERSION = 1
    private val REGEX = Regex("""RELAYQR:1\|PHONE:([^|]+)\|NAME:([^|]{0,30})\|KEY:([A-Za-z0-9+/=]+)""")

    data class ScannedContact(
        val phone: String,
        val name: String,
        val publicKeyBase64: String,
    )

    fun encode(phone: String, name: String, publicKeyBase64: String): String {
        val safeName = name.take(30).replace("|", "")
        return "RELAYQR:$VERSION|PHONE:$phone|NAME:$safeName|KEY:$publicKeyBase64"
    }

    fun decode(raw: String): ScannedContact? {
        val match = REGEX.find(raw.trim()) ?: return null
        val phone = match.groupValues[1].trim()
        val name = match.groupValues[2].trim()
        val key = match.groupValues[3]
        if (phone.isEmpty() || key.isEmpty()) return null
        return ScannedContact(
            phone = phone,
            name = name.ifEmpty { phone },
            publicKeyBase64 = key,
        )
    }
}
