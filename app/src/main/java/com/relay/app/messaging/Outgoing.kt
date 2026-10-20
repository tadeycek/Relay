package com.relay.app.messaging

import android.content.Context
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.OutboxRepository
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.outbox.OutboxEntry

/** Queues an outgoing body for delivery over the internet transport. */
object Outgoing {

    /**
     * Seals [plainBody] with the contact's Tink key (if known), wraps it in a payload and stores it in
     * the durable outbox. Returns the payload id (also used as the stored message's `msg_id`), or
     * null if the contact has no Nostr address. Delivery is asynchronous: the outbox worker sends it
     * and retries with backoff until it is accepted or gives up.
     */
    fun enqueue(context: Context, contact: Contact, plainBody: String, seal: Boolean = true, payloadId: String = PayloadCodec.newId()): String? {
        val recipient = contact.nostrPubkey ?: return null
        return enqueueTo(context, recipient, if (seal) contact.publicKey else null, emptyList(), contact.id, plainBody, payloadId)
    }

    /**
     * Like [enqueue], for a recipient who may not be a contact (a group member): the group roster supplies
     * the Tink key ([innerKey], null sends without the inner layer) and [relayHints]. [contactId] links the
     * entry to a contact when there is one.
     */
    fun enqueueTo(
        context: Context,
        recipient: String,
        innerKey: String?,
        relayHints: List<String>,
        contactId: Long?,
        plainBody: String,
        payloadId: String = PayloadCodec.newId(),
    ): String? {
        return enqueuePrepared(context, prepareTo(recipient, innerKey, relayHints, contactId, plainBody, payloadId))
    }

    /** Prepares an encrypted entry without acquiring a database writer transaction. */
    fun prepareTo(recipient: String, innerKey: String?, relayHints: List<String>, contactId: Long?,
        plainBody: String, payloadId: String = PayloadCodec.newId(),
        sealBody: (String?, String) -> String = InnerEnvelope::seal): OutboxEntry {
        val now = System.currentTimeMillis()
        val body = sealBody(innerKey, plainBody)
        require(body.length <= PayloadCodec.MAX_BODY_LENGTH) { "Message is too large; shorten it and try again" }
        val payload = RelayPayload(id = payloadId, ts = now, body = body)
        return OutboxEntry(contactId = contactId, recipientPubkeyHex = recipient, payloadId = payload.id,
            payloadJson = PayloadCodec.encode(payload), createdAt = now, relayHints = relayHints)
    }

    fun enqueuePrepared(context: Context, entry: OutboxEntry): String? {
        val stored = OutboxRepository(MessagingDb.get(context)).enqueueSync(entry)
        if (stored == -1L) return null
        MessagingRuntime.kick()
        return entry.payloadId
    }
}
