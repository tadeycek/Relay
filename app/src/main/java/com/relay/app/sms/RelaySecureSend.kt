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
                SmsMessageParser.formatEncrypted(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
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
        SmsSender.sendSms(context, contact.phone, SmsMessageParser.formatPublicKey(myKey))
        contactRepo.markSentPubkeySync(contact.id)
    }
}
