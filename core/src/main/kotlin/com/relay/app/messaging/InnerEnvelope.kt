package com.relay.app.messaging

import com.relay.app.crypto.KeyMaterial
import com.relay.app.util.SmsMessageParser
import java.util.Base64

/**
 * Relay's own end-to-end layer (Tink HPKE, with key rotation) kept *inside* the Nostr envelope.
 * Nostr's NIP-44 already encrypts and the seal signature authenticates the sender, so this layer is
 * defence in depth and preserves the existing rotation machinery; it also means a flaw in either
 * layer alone does not expose content. Internet messages are not additionally signed here (the
 * seal already proves the sender), which saves ~90 bytes per message.
 */
object InnerEnvelope {

    /** Wraps [plainBody] for [recipientPublicKeyBase64], or leaves it for the outer encrypted transport when no inner key exists. Known-key failures abort. */
    fun seal(keys: KeyMaterial, recipientPublicKeyBase64: String?, plainBody: String): String {
        if (recipientPublicKeyBase64 == null) return plainBody
        val ciphertext = checkNotNull(keys.encryptTo(recipientPublicKeyBase64, plainBody.toByteArray(Charsets.UTF_8))) {
            "Couldn't encrypt this message; review the contact's key and try again"
        }
        return SmsMessageParser.formatEncrypted(Base64.getEncoder().encodeToString(ciphertext))
    }

    /** Opens a `TYPE:ENC` body with our key(s). Null if it cannot be decoded or decrypted. */
    fun open(keys: KeyMaterial, wireBody: String): String? {
        val ct = SmsMessageParser.parseEncryptedPayload(wireBody) ?: return null
        val bytes = try {
            Base64.getDecoder().decode(ct)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val plain = keys.decryptMine(bytes) ?: return null
        return try {
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
