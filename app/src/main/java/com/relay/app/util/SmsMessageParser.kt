package com.relay.app.util

import com.relay.app.data.model.PinExpiry

object SmsMessageParser {

    private val LOCATION_REGEX = Regex(
        """TYPE:LOCATION\|LAT:(-?\d+\.\d+)\|LNG:(-?\d+\.\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val EXPIRY_REGEX = Regex("""EXPIRY:(\w+)""", RegexOption.IGNORE_CASE)
    private val LABEL_REGEX = Regex("""LABEL:([^|]{1,30})""", RegexOption.IGNORE_CASE)
    private val READ_RECEIPT_REGEX = Regex("""TYPE:READ_RECEIPT\|MSG_ID:(\d+)""", RegexOption.IGNORE_CASE)
    private val PUBKEY_REGEX = Regex(
        """TYPE:PUBKEY\|KEY:([A-Za-z0-9+/=]+)(?:\|SIG:([A-Za-z0-9+/=]+))?(?:\|ROT:([A-Za-z0-9+/=]+))?(?:\|NAME:([^|]{0,30}))?""",
        RegexOption.IGNORE_CASE
    )
    private val ENC_REGEX = Regex(
        """TYPE:ENC\|CT:([A-Za-z0-9+/=]+)(?:\|SIG:([A-Za-z0-9+/=]+))?""",
        RegexOption.IGNORE_CASE
    )

    const val LOCATION_REQUEST_MSG = "TYPE:LOCATION_REQUEST"
    const val LOCATION_DECLINED_MSG = "TYPE:LOCATION_DECLINED"

    data class ParsedLocation(val lat: Double, val lng: Double)

    fun parseLocation(body: String): ParsedLocation? {
        val match = LOCATION_REGEX.find(body.trim()) ?: return null
        return ParsedLocation(
            lat = match.groupValues[1].toDoubleOrNull() ?: return null,
            lng = match.groupValues[2].toDoubleOrNull() ?: return null,
        )
    }

    fun formatLocation(
        lat: Double,
        lng: Double,
        expiry: PinExpiry = PinExpiry.NEVER,
        label: String? = null,
    ): String {
        val base = "TYPE:LOCATION|LAT:${"%.6f".format(lat)}|LNG:${"%.6f".format(lng)}"
        val withExpiry = if (expiry != PinExpiry.NEVER) "$base|EXPIRY:${expiry.smsCode}" else base
        val trimmedLabel = label?.trim()?.take(30)
        return if (!trimmedLabel.isNullOrEmpty()) "$withExpiry|LABEL:$trimmedLabel" else withExpiry
    }

    fun parseExpiry(body: String): PinExpiry =
        PinExpiry.fromCode(EXPIRY_REGEX.find(body)?.groupValues?.get(1))

    fun parseLabel(body: String): String? =
        LABEL_REGEX.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    fun isLocationMessage(body: String): Boolean = parseLocation(body) != null

    fun isLocationRequest(body: String): Boolean =
        body.trim().equals(LOCATION_REQUEST_MSG, ignoreCase = true)

    fun isLocationDeclined(body: String): Boolean =
        body.trim().equals(LOCATION_DECLINED_MSG, ignoreCase = true)

    fun isReadReceipt(body: String): Boolean = READ_RECEIPT_REGEX.containsMatchIn(body)

    fun parseReadReceipt(body: String): Long? =
        READ_RECEIPT_REGEX.find(body)?.groupValues?.get(1)?.toLongOrNull()

    fun formatReadReceipt(timestamp: Long): String = "TYPE:READ_RECEIPT|MSG_ID:$timestamp"

    fun isPublicKeyMessage(body: String): Boolean = PUBKEY_REGEX.containsMatchIn(body)

    fun parsePublicKey(body: String): String? = PUBKEY_REGEX.find(body)?.groupValues?.get(1)

    /** Sender's long-term signing public key, learned once at pairing time. */
    fun parsePublicKeySigningKey(body: String): String? =
        PUBKEY_REGEX.find(body)?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }

    /** Signature over [parsePublicKey] by the sender's signing key, present only on a rotation
     *  broadcast (not the initial pairing handshake). */
    fun parsePublicKeyRotationSignature(body: String): String? =
        PUBKEY_REGEX.find(body)?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }

    /** Optional display name carried alongside the key (e.g. from a QR-triggered exchange). */
    fun parsePublicKeyName(body: String): String? =
        PUBKEY_REGEX.find(body)?.groupValues?.get(4)
            ?.replace(Regex("""[\p{Cc}\p{Cf}]"""), "")?.trim()?.takeIf { it.isNotEmpty() }

    fun formatPublicKey(
        base64Key: String,
        name: String? = null,
        signingKeyBase64: String? = null,
        rotationSignatureBase64: String? = null,
    ): String {
        var msg = "TYPE:PUBKEY|KEY:$base64Key"
        if (!signingKeyBase64.isNullOrEmpty()) msg += "|SIG:$signingKeyBase64"
        if (!rotationSignatureBase64.isNullOrEmpty()) msg += "|ROT:$rotationSignatureBase64"
        val safeName = name?.replace(Regex("""[\p{Cc}\p{Cf}]"""), "")?.replace("|", "")?.trim()?.take(30)
        if (!safeName.isNullOrEmpty()) msg += "|NAME:$safeName"
        return msg
    }

    fun isEncryptedMessage(body: String): Boolean = ENC_REGEX.find(body.trim()) != null

    fun parseEncryptedPayload(body: String): String? = ENC_REGEX.find(body.trim())?.groupValues?.get(1)

    /** Signature over the ciphertext bytes by the sender's long-term signing key — present only
     *  from a sender who has completed a pairing that exchanged a signing key. Absent for
     *  messages from a contact paired before this feature shipped (treated as unverified, not
     *  rejected — see SmsReceiver). */
    fun parseEncryptedSignature(body: String): String? =
        ENC_REGEX.find(body.trim())?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }

    fun formatEncrypted(base64Ciphertext: String, signatureBase64: String? = null): String {
        val base = "TYPE:ENC|CT:$base64Ciphertext"
        return if (!signatureBase64.isNullOrEmpty()) "$base|SIG:$signatureBase64" else base
    }
}
