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
    fun enqueue(context: Context, contact: Contact, plainBody: String, seal: Boolean = true): String? {
        val recipient = contact.nostrPubkey ?: return null
        val now = System.currentTimeMillis()
        val body = if (seal) InnerEnvelope.seal(contact.publicKey, plainBody) else plainBody
        val payload = RelayPayload(id = PayloadCodec.newId(), ts = now, body = body)
        OutboxRepository(MessagingDb.get(context)).enqueueSync(
            OutboxEntry(
                contactId = contact.id,
                recipientPubkeyHex = recipient,
                payloadId = payload.id,
                payloadJson = PayloadCodec.encode(payload),
                createdAt = now,
            )
        )
        MessagingRuntime.kick()
        return payload.id
    }
}
