package com.relay.app.sms

import android.content.Context
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.OutboxRepository
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.outbox.OutboxEntry
import com.relay.app.util.QrContactCode
import com.relay.app.util.RelayPreferences
import com.relay.app.util.SmsMessageParser

/**
 * Handles a successfully scanned QR contact code: upserts the contact, trusts the scanned public
 * key immediately, and sends our own key+name back so the other side learns us too.
 *
 * Unlike a key learned from an incoming message (which could be spoofed, hence the pending-key-change
 * hold), a QR-scanned key is physically/out-of-band verified — the scanner saw it displayed on the
 * other person's actual screen — so it's trusted directly, never held for review.
 *
 * Two flavours:
 *  - **Internet (v3 codes):** the contact is identified by its Nostr key. Our reply is queued in the
 *    outbox as a `TYPE:PUBKEY` payload and delivered by the transport, so it works even if the other
 *    phone is offline right now. Because a Nostr seal is signed by the sender's key, that reply is
 *    authenticated by construction.
 *  - **Legacy (v1/v2 codes):** the SMS-era path, kept only until SMS is removed in the final phase.
 */
object QrContactExchange {

    fun onScanned(
        context: Context,
        contactRepo: ContactRepository,
        scanned: QrContactCode.ScannedContact,
    ): Contact = if (scanned.nostrPubkeyHex != null) {
        pairOverInternet(context, contactRepo, scanned, scanned.nostrPubkeyHex)
    } else {
        pairLegacySms(context, contactRepo, scanned)
    }

    private fun pairOverInternet(
        context: Context,
        contactRepo: ContactRepository,
        scanned: QrContactCode.ScannedContact,
        nostrPubkeyHex: String,
    ): Contact {
        val contact = contactRepo.findOrCreateByNostrSync(nostrPubkeyHex, scanned.name, scanned.relayHints)

        val existingKey = contact.publicKey
        if (existingKey != null && existingKey != scanned.publicKeyBase64) {
            // Same Nostr identity but a different encryption key than the one already trusted: hold
            // it for explicit review rather than silently replacing it.
            contactRepo.setPendingPublicKeySync(contact.id, scanned.publicKeyBase64)
            return contactRepo.getByIdSync(contact.id) ?: contact
        }

        contactRepo.setPublicKeySync(contact.id, scanned.publicKeyBase64)
        contactRepo.rejectPendingPublicKeySync(contact.id)
        contactRepo.markAsRelayUserSync(contact.id)
        contactRepo.markQrVerifiedSync(contact.id) // met in person: the strongest trust signal we have
        scanned.signingPublicKeyBase64?.let { contactRepo.setSigningPublicKeyIfAbsentSync(contact.id, it) }

        if (!contactRepo.hasSentPubkeySync(contact.id)) {
            val myKey = RelayCrypto.myPublicKeyBase64(context)
            if (myKey != null) {
                val body = SmsMessageParser.formatPublicKey(
                    myKey,
                    RelayPreferences(context).myName,
                    signingKeyBase64 = RelayCrypto.mySigningPublicKeyBase64(context),
                )
                val now = System.currentTimeMillis()
                val payload = RelayPayload(id = PayloadCodec.newId(), ts = now, body = body)
                OutboxRepository(RelayDbHelper(context)).enqueueSync(
                    OutboxEntry(
                        contactId = contact.id,
                        recipientPubkeyHex = nostrPubkeyHex,
                        payloadId = payload.id,
                        payloadJson = PayloadCodec.encode(payload),
                        createdAt = now,
                    )
                )
                // Queued durably, so the handshake will be delivered; safe to mark done.
                contactRepo.markSentPubkeySync(contact.id)
            }
        }
        return contactRepo.getByIdSync(contact.id) ?: contact
    }

    private fun pairLegacySms(
        context: Context,
        contactRepo: ContactRepository,
        scanned: QrContactCode.ScannedContact,
    ): Contact {
        val phone = scanned.phone ?: error("Legacy QR code without a phone number")
        val contact = contactRepo.findOrCreateByPhoneSync(phone)
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
        // Also learn their signing key here (out-of-band verified, same as the encryption key) so
        // a later routine key rotation over SMS can be auto-verified instead of alarming the user.
        scanned.signingPublicKeyBase64?.let { contactRepo.setSigningPublicKeyIfAbsentSync(contact.id, it) }

        if (!contactRepo.hasSentPubkeySync(contact.id)) {
            val myKey = RelayCrypto.myPublicKeyBase64(context)
            if (myKey != null) {
                val myName = RelayPreferences(context).myName
                val mySigningKey = RelayCrypto.mySigningPublicKeyBase64(context)
                val sent = SmsSender.sendSms(
                    context,
                    phone,
                    SmsMessageParser.formatPublicKey(myKey, myName, signingKeyBase64 = mySigningKey),
                )
                // Only mark the handshake done if the SMS actually went out — otherwise the other
                // side never gets our key and can never decrypt us, silently and permanently.
                if (sent) contactRepo.markSentPubkeySync(contact.id)
            }
        }

        return contactRepo.getByIdSync(contact.id) ?: contact
    }
}
