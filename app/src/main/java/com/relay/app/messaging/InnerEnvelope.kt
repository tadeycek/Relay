package com.relay.app.messaging

import android.content.Context
import android.util.Base64
import com.relay.app.crypto.RelayCrypto
import com.relay.app.util.SmsMessageParser

/**
 * Relay's own end-to-end layer (Tink HPKE, with key rotation) kept *inside* the Nostr envelope.
 * Nostr's NIP-44 already encrypts and the seal signature authenticates the sender, so this layer is
 * defence in depth and preserves the existing rotation machinery; it also means a flaw in either
 * layer alone does not expose content. Internet messages are not additionally signed here (the
 * seal already proves the sender), which saves ~90 bytes per message.
 */
object InnerEnvelope {

    /** Wraps [plainBody] for [recipientPublicKeyBase64], or returns it unchanged if there is no key or encryption fails. */
    fun seal(recipientPublicKeyBase64: String?, plainBody: String): String {
        if (recipientPublicKeyBase64 == null) return plainBody
        val ciphertext = RelayCrypto.encryptTo(recipientPublicKeyBase64, plainBody.toByteArray(Charsets.UTF_8))
            ?: return plainBody
        return SmsMessageParser.formatEncrypted(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
    }

    /** Opens a `TYPE:ENC` body with our key(s). Null if it cannot be decoded or decrypted. */
    fun open(context: Context, wireBody: String): String? {
        val ct = SmsMessageParser.parseEncryptedPayload(wireBody) ?: return null
        val bytes = try {
            Base64.decode(ct, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val plain = RelayCrypto.decryptMine(context, bytes) ?: return null
        return try {
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
