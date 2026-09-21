package com.relay.app.data.model

data class Contact(
    val id: Long = 0L,
    val name: String,
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
)
