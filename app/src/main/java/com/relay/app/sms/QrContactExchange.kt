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

        contactRepo.setPublicKeySync(contact.id, scanned.publicKeyBase64)
        contactRepo.markAsRelayUserSync(contact.id)

        if (!contactRepo.hasSentPubkeySync(contact.id)) {
            val myKey = RelayCrypto.myPublicKeyBase64(context)
            if (myKey != null) {
                val myName = RelayPreferences(context).myName
                SmsSender.sendSms(context, scanned.phone, SmsMessageParser.formatPublicKey(myKey, myName))
                contactRepo.markSentPubkeySync(contact.id)
            }
        }

        return contactRepo.getByIdSync(contact.id) ?: contact
    }
}
