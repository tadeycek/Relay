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
 * Handles a successfully scanned QR contact code: creates or finds the contact by its Nostr key,
 * trusts the scanned keys immediately, marks the contact as met in person, and queues our own
 * key+name back so the other side learns us too.
 *
 * Unlike a key learned from an incoming message (which any stranger who knows our key could send),
 * a QR-scanned key is physically/out-of-band verified — the scanner saw it displayed on the other
 * person's actual screen — so it is trusted directly, never held for review. The reply is queued in
 * the durable outbox and delivered by the transport, so it works even if the other phone is
 * offline right now. The Nostr seal is signed by the sender's key, so that reply is authenticated.
 *
 * Old-style (v1/v2) codes carry only a phone number and cannot be paired over the internet; callers
 * should reject them before getting here (see [canPair]).
 */
object QrContactExchange {

    /** Only v3 codes (which carry a Nostr key) can be paired. */
    fun canPair(scanned: QrContactCode.ScannedContact): Boolean = scanned.nostrPubkeyHex != null

    fun onScanned(
        context: Context,
        contactRepo: ContactRepository,
        scanned: QrContactCode.ScannedContact,
    ): Contact {
        val nostrPubkeyHex = requireNotNull(scanned.nostrPubkeyHex) { "Old-style QR code cannot be paired" }
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
                com.relay.app.messaging.MessagingRuntime.kick()
            }
        }
        return contactRepo.getByIdSync(contact.id) ?: contact
    }
}
