package com.relay.app.messaging

import android.content.Context
import android.content.Intent
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.data.repository.OutboxRepository
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.outbox.OutboxPolicy

/** Drains due outbox entries through the transport, recording success, retry schedule or failure. */
class OutboxWorker(context: Context, private val transport: Transport) {

    private val appContext = context.applicationContext
    private val db = MessagingDb.get(appContext)
    private val outbox = OutboxRepository(db)
    private val contacts = ContactRepository(db)
    private val messages = MessageRepository(db)

    /** Returns how many entries were delivered to at least one relay. */
    suspend fun drain(): Int {
        val now = System.currentTimeMillis()
        var delivered = 0
        for (entry in outbox.dueSync(now)) {
            if (OutboxPolicy.isExpired(entry.createdAt, now)) {
                fail(entry.id, entry.payloadId, entry.contactId)
                continue
            }
            val payload = PayloadCodec.decode(entry.payloadJson)
            if (payload == null) {
                fail(entry.id, entry.payloadId, entry.contactId)
                continue
            }
            val hints = contacts.getByIdSync(entry.contactId)?.relayHints.orEmpty()
            when (val result = transport.send(entry.recipientPubkeyHex, payload, hints)) {
                is SendResult.Sent -> {
                    outbox.markSentSync(entry.id)
                    messages.setDeliveryStateSync(entry.payloadId, DeliveryState.SENT)
                    broadcast(entry.contactId)
                    delivered++
                }
                is SendResult.Failed -> {
                    if (!result.retryable) {
                        fail(entry.id, entry.payloadId, entry.contactId)
                    } else {
                        val attempts = entry.attempts + 1
                        outbox.recordAttemptSync(entry.id, attempts, now + OutboxPolicy.nextDelayMs(attempts))
                    }
                }
            }
        }
        outbox.purgeSentBeforeSync(now - 24L * 60 * 60 * 1000)
        return delivered
    }

    private fun fail(entryId: Long, payloadId: String, contactId: Long) {
        outbox.markFailedSync(entryId)
        messages.setDeliveryStateSync(payloadId, DeliveryState.FAILED)
        broadcast(contactId)
    }

    private fun broadcast(contactId: Long) {
        appContext.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").apply {
            putExtra("contact_id", contactId)
            setPackage(appContext.packageName)
        })
    }
}
