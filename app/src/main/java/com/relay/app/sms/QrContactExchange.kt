package com.relay.app.sms

import android.content.Context
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.util.QrContactCode
import com.relay.app.util.RelayPreferences
import com.relay.app.util.SmsMessageParser

/**
 * Handles a successfully scanned QR contact code: upserts the contact, trusts the scanned public
 * key immediately, and sends our own key+name back over SMS so the other side learns us too.
 *
 * Unlike a key learned from an incoming SMS (which can be spoofed, hence the pending-key-change
 * hold in [SmsReceiver]), a QR-scanned key is physically/out-of-band verified — the scanner saw
 * it displayed on the other person's actual screen — so it's trusted directly, never held for
 * review. The reply we send back is still ordinary SMS, so the *other* side's trust in us is only
 * as strong as today's regular handshake, unless they scan our code back too.
 */
object QrContactExchange {

    fun onScanned(
        context: Context,
        contactRepo: ContactRepository,
        scanned: QrContactCode.ScannedContact,
    ): Contact {
        val contact = contactRepo.findOrCreateByPhoneSync(scanned.phone)
        if (contact.name == contact.phone) {
            contactRepo.setNameSync(contact.id, scanned.name)
        }

        val existingKey = contact.publicKey
        if (existingKey != null && existingKey != scanned.publicKeyBase64) {
            // A DIFFERENT key already trusted for this contact. The QR's phone field is
            // attacker-choosable, so a hostile QR bearing a known contact's number + a foreign key
            // must NOT silently overwrite the trusted key — route it through the same pending-key
            // review the SMS path uses (SmsReceiver.handleIncomingPublicKey) so the user confirms.
            // Don't send our key back to a possibly-hostile number until the conflict is resolved.
            contactRepo.setPendingPublicKeySync(contact.id, scanned.publicKeyBase64)
            return contactRepo.getByIdSync(contact.id) ?: contact
        }

        // New or matching key: trust it directly (QR is out-of-band verified) and clear any stale
        // pending candidate so a leftover spoofed-SMS key can't later be "accepted" over this one.
        contactRepo.setPublicKeySync(contact.id, scanned.publicKeyBase64)
        contactRepo.rejectPendingPublicKeySync(contact.id)
        contactRepo.markAsRelayUserSync(contact.id)

        if (!contactRepo.hasSentPubkeySync(contact.id)) {
            val myKey = RelayCrypto.myPublicKeyBase64(context)
            if (myKey != null) {
                val myName = RelayPreferences(context).myName
                val sent = SmsSender.sendSms(context, scanned.phone, SmsMessageParser.formatPublicKey(myKey, myName))
                // Only mark the handshake done if the SMS actually went out — otherwise the other
                // side never gets our key and can never decrypt us, silently and permanently.
                if (sent) contactRepo.markSentPubkeySync(contact.id)
            }
        }

        return contactRepo.getByIdSync(contact.id) ?: contact
    }
}
