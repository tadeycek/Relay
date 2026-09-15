package com.relay.app.sms

import android.content.Context
import android.util.Base64
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.util.SmsMessageParser

/**
 * Wraps [SmsSender] with opportunistic end-to-end encryption: if we already
 * know a contact's public key, the structured Relay payload is hybrid
 * -encrypted before it becomes the SMS body. Otherwise it goes out as plain
 * text (today's behavior) and, on the first message ever sent to that
 * contact, we also send them our own public key so a future reply can
 * complete the handshake and switch both directions to ciphertext.
 */
object RelaySecureSend {

    fun send(context: Context, contactRepo: ContactRepository, contact: Contact, plainBody: String): Boolean {
        maybeBootstrapKeyExchange(context, contactRepo, contact)

        val publicKey = contact.publicKey
        val wireBody = if (publicKey != null) {
            val ciphertext = RelayCrypto.encryptTo(publicKey, plainBody.toByteArray(Charsets.UTF_8))
            if (ciphertext != null) {
                // Sign the ciphertext with our long-term signing key so the receiver can confirm
                // it really came from us — Tink's hybrid encryption alone only proves the message
                // was encrypted to the recipient's key, not who encrypted it (HPKE base mode has
                // no sender authentication), and SMS sender IDs are trivially spoofable.
                val signature = RelayCrypto.signBytes(context, ciphertext)
                SmsMessageParser.formatEncrypted(Base64.encodeToString(ciphertext, Base64.NO_WRAP), signature)
            } else {
                plainBody
            }
        } else {
            plainBody
        }
        return SmsSender.sendSms(context, contact.phone, wireBody)
    }

    private fun maybeBootstrapKeyExchange(context: Context, contactRepo: ContactRepository, contact: Contact) {
        if (contactRepo.hasSentPubkeySync(contact.id)) return
        val myKey = RelayCrypto.myPublicKeyBase64(context) ?: return
        val mySigningKey = RelayCrypto.mySigningPublicKeyBase64(context)
        SmsSender.sendSms(context, contact.phone, SmsMessageParser.formatPublicKey(myKey, signingKeyBase64 = mySigningKey))
        contactRepo.markSentPubkeySync(contact.id)
    }
}
