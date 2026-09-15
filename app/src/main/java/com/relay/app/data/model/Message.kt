package com.relay.app.data.model

data class Message(
    val id: Long = 0L,
    val contactId: Long,
    val body: String,
    val type: MessageType = MessageType.TEXT,
    val lat: Double? = null,
    val lng: Double? = null,
    val isSent: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val mediaUri: String? = null,
    val pinLabel: String? = null,
    val expiryAt: Long? = null,
    val msgId: String? = null,
    val readAt: Long? = null,
    /**
     * For a received, decrypted message: whether the sender's signature over the ciphertext
     * verified against the contact's stored signing key. False means it decrypted fine but its
     * authenticity couldn't be confirmed — either the sender/contact predates the signing-key
     * handshake, or (rarer) the signature genuinely didn't match, e.g. a spoofed sender. True for
     * every non-encrypted message and every message we sent ourselves — verification only applies
     * to something we received and decrypted.
     */
    val senderVerified: Boolean = true,
)
