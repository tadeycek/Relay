package com.relay.app.data.model

data class Contact(
    val id: Long = 0L,
    val name: String,
    /**
     * Legacy SMS-era routing address. Contacts paired over the internet transport have none; for
     * those the column holds a synthetic unique placeholder (see [NOSTR_PHONE_PREFIX]) because the
     * column is NOT NULL UNIQUE and SQLite cannot relax that without rebuilding the table (which
     * would cascade-delete messages). Use [hasPhone] before showing or using this as a number.
     */
    val phone: String,
    val hasRelay: Boolean = false,
    val trustLevel: ContactTrustLevel = ContactTrustLevel.ASK,
    /** Base64 Tink hybrid public key, once learned via a TYPE:PUBKEY exchange. Null = not yet encrypted. */
    val publicKey: String? = null,
    /**
     * A *different* key claimed for this contact after we already trusted one — held here rather
     * than auto-replacing [publicKey], since SMS sender addresses can be spoofed. Null unless a
     * key-change is awaiting the user's explicit accept/reject.
     */
    val pendingPublicKey: String? = null,
    /**
     * Base64 Ed25519 signing public key, learned once at pairing time and never overwritten
     * afterward. Used to verify that a later [publicKey] rotation really came from this same
     * contact (see RelayCrypto/SmsReceiver) so routine key rotation doesn't need to go through
     * the scary pending-key-change dialog. A change to *this* key is always treated as suspicious.
     */
    val signingPublicKey: String? = null,
    /** 64-char hex Nostr public key: the contact's network address. Null for legacy SMS-only contacts. */
    val nostrPubkey: String? = null,
    /** Extra `wss://` inbox relays learned from the contact's QR code, tried besides our own list. */
    val relayHints: List<String> = emptyList(),
) {
    /** True when [phone] is a real number rather than the placeholder used for internet-only contacts. */
    val hasPhone: Boolean get() = !phone.startsWith(NOSTR_PHONE_PREFIX)

    /** True when messages to this contact can be delivered over the internet transport. */
    val canUseInternetTransport: Boolean get() = nostrPubkey != null

    /** Secondary line for lists: the phone number if there is one, else a short key fingerprint. */
    val subtitle: String
        get() = when {
            hasPhone -> phone
            nostrPubkey != null && publicKey == null ->
                "Relay ID ${nostrPubkey.take(8)}…${nostrPubkey.takeLast(4)} · unverified"
            nostrPubkey != null -> "Relay ID ${nostrPubkey.take(8)}…${nostrPubkey.takeLast(4)}"
            else -> ""
        }

    companion object {
        const val NOSTR_PHONE_PREFIX = "nostr:"

        fun placeholderPhoneFor(nostrPubkeyHex: String): String = NOSTR_PHONE_PREFIX + nostrPubkeyHex
    }
}
