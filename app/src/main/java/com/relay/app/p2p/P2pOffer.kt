package com.relay.app.p2p

/**
 * The metadata for a direct phone-to-phone transfer — everything except the ciphertext bytes
 * themselves. Sent over the **normal end-to-end encrypted message channel** (sealed, like any other
 * message), never over the raw transfer socket: the socket only ever carries [P2pWire.Header] (just a
 * nonce) plus ciphertext, so the AES key that decrypts it is never exposed on the same channel as the
 * thing it decrypts. That would make the encryption pointless — anyone who can see the socket's bytes
 * would see the key right next to what it opens.
 *
 * Reuses the same nonce the presence ping/pong for this contact already established, so the offer, the
 * [PresenceCoordinator]'s record of who was ponged, and the eventual socket connection all tie back to
 * one proof: only the phone that was actually pinged could have received this nonce at all.
 */
object P2pOfferMessages {
    private val NONCE = Regex("""[0-9a-f]{32}""")
    private val SHA256 = Regex("""[0-9a-f]{64}""")
    private val BASE64 = Regex("""[A-Za-z0-9+/=]+""")

    data class Offer(
        val nonce: String,
        val sha256Hex: String,
        val keyBase64: String,
        val mime: String,
        val size: Long,
        val caption: String,
    )

    fun format(offer: Offer): String {
        val safeCaption = offer.caption.replace(Regex("""[\p{Cc}\p{Cf}]"""), "").replace("|", "").take(200)
        return "TYPE:P2P_OFFER|NONCE:${offer.nonce}|SHA:${offer.sha256Hex}|KEY:${offer.keyBase64}" +
            "|MIME:${offer.mime}|SIZE:${offer.size}|CAP:$safeCaption"
    }

    /** Null unless [body] is a well-formed offer. Never throws on hostile input. */
    fun parse(body: String): Offer? {
        val parts = body.trim().split('|')
        if (parts.firstOrNull() != "TYPE:P2P_OFFER") return null
        val fields = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val i = p.indexOf(':')
            if (i <= 0) return null
            fields[p.substring(0, i)] = p.substring(i + 1)
        }
        val nonce = fields["NONCE"]?.takeIf { NONCE.matches(it) } ?: return null
        val sha = fields["SHA"]?.lowercase()?.takeIf { SHA256.matches(it) } ?: return null
        val key = fields["KEY"]?.takeIf { it.isNotEmpty() && BASE64.matches(it) } ?: return null
        val mime = fields["MIME"]?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return null
        val size = fields["SIZE"]?.toLongOrNull()?.takeIf { it in 1..MAX_SIZE } ?: return null
        val caption = fields["CAP"] ?: ""
        return Offer(nonce, sha, key, mime, size, caption)
    }

    fun isOfferMessage(body: String): Boolean = parse(body) != null

    /** Comfortably above anything MediaCompressor would ever hand to a sender. */
    private const val MAX_SIZE = 50L * 1024 * 1024
}
