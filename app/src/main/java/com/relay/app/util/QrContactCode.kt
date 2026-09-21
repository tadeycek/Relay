package com.relay.app.util

/**
 * Encoding for the QR contact-exchange code (Contacts -> QR icon). Never sent over any messaging
 * channel — used only for the in-person QR scan/display flow, which is what makes the key it carries
 * trustworthy (the scanner saw it on the other person's actual screen).
 *
 * Format: `RELAYQR:<version>|FIELD:value|FIELD:value...` — pipe-delimited, base64/hex values never
 * contain `|`, and free text is sanitised. Versions:
 *  - v1/v2 (legacy, SMS era): `PHONE`, `NAME`, `KEY` (Tink HPKE public key), optional `SIGKEY`.
 *  - v3 (internet transport): `NAME`, `NPUB` (64-char hex Nostr public key), `KEY`, optional
 *    `SIGKEY`, optional `RELAYS` (comma-separated `wss://` inbox relay hints). No phone number is
 *    carried — the Nostr key is the address, and leaving the number out keeps it private.
 *
 * Legacy codes still decode (they yield a contact with a phone and no Nostr key, which can be shown
 * as "legacy, cannot receive new messages").
 */
object QrContactCode {

    private const val VERSION = 3
    private const val MAX_RELAY_HINTS = 5
    private const val MAX_RELAY_LENGTH = 100
    private val BASE64 = Regex("""[A-Za-z0-9+/=]+""")
    private val HEX64 = Regex("""[0-9a-f]{64}""")

    /** Strip control/format chars (newlines, RTL-override, etc.) that could smuggle through a name. */
    private fun sanitize(s: String): String = s.replace(Regex("""[\p{Cc}\p{Cf}]"""), "").replace("|", "")

    data class ScannedContact(
        /** Only present in legacy (v1/v2) codes. */
        val phone: String?,
        val name: String,
        val publicKeyBase64: String,
        val signingPublicKeyBase64: String? = null,
        /** 64-char lowercase hex Nostr public key. Present in v3 codes. */
        val nostrPubkeyHex: String? = null,
        val relayHints: List<String> = emptyList(),
    )

    fun encode(
        name: String,
        nostrPubkeyHex: String,
        publicKeyBase64: String,
        signingPublicKeyBase64: String? = null,
        relayHints: List<String> = emptyList(),
    ): String {
        val safeName = sanitize(name).take(30)
        val sb = StringBuilder("RELAYQR:$VERSION|NAME:$safeName|NPUB:${nostrPubkeyHex.lowercase()}|KEY:$publicKeyBase64")
        if (!signingPublicKeyBase64.isNullOrEmpty()) sb.append("|SIGKEY:$signingPublicKeyBase64")
        val hints = cleanRelayHints(relayHints)
        if (hints.isNotEmpty()) sb.append("|RELAYS:").append(hints.joinToString(","))
        return sb.toString()
    }

    fun decode(raw: String): ScannedContact? {
        val parts = raw.trim().split('|')
        val head = parts.firstOrNull() ?: return null
        if (!head.startsWith("RELAYQR:")) return null
        val version = head.removePrefix("RELAYQR:").toIntOrNull() ?: return null
        if (version < 1) return null

        val fields = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val idx = p.indexOf(':')
            if (idx <= 0) return null
            fields[p.substring(0, idx).uppercase()] = p.substring(idx + 1)
        }

        val key = fields["KEY"] ?: return null
        if (key.isEmpty() || !BASE64.matches(key)) return null
        val signingKey = fields["SIGKEY"]?.takeIf { it.isNotEmpty() }
        if (signingKey != null && !BASE64.matches(signingKey)) return null
        val name = sanitize(fields["NAME"] ?: "").trim().take(30)

        if (version >= 3) {
            val npub = fields["NPUB"]?.lowercase() ?: return null
            if (!HEX64.matches(npub)) return null
            return ScannedContact(
                phone = null,
                name = name.ifEmpty { "Contact ${npub.take(8)}" },
                publicKeyBase64 = key,
                signingPublicKeyBase64 = signingKey,
                nostrPubkeyHex = npub,
                relayHints = cleanRelayHints(fields["RELAYS"]?.split(',').orEmpty()),
            )
        }

        val phone = sanitize(fields["PHONE"] ?: "").trim()
        if (phone.isEmpty()) return null
        return ScannedContact(
            phone = phone,
            name = name.ifEmpty { phone },
            publicKeyBase64 = key,
            signingPublicKeyBase64 = signingKey,
        )
    }

    /** Keeps only well-formed `wss://` URLs, bounded in count and length, without duplicates. */
    fun cleanRelayHints(hints: List<String>): List<String> = hints
        .map { sanitize(it).trim() }
        .filter { it.startsWith("wss://") && it.length in 8..MAX_RELAY_LENGTH && !it.contains(',') && !it.contains(' ') }
        .distinct()
        .take(MAX_RELAY_HINTS)
}
