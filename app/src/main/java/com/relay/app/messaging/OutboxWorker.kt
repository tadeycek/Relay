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
import com.relay.app.transport.outbox.OutboxDispatch
import com.relay.app.transport.outbox.OutboxEntry
import com.relay.app.transport.outbox.OutboxPolicy
import kotlinx.coroutines.CoroutineScope

/** Sends due outbox entries through the transport, recording success, retry schedule or failure. */
class OutboxWorker(
    context: Context,
    private val transport: Transport,
    scope: CoroutineScope,
    /** Called whenever a recipient's lane finishes, so newly due work is picked up without waiting. */
    private val onLaneDone: () -> Unit,
) {

    private val appContext = context.applicationContext
    private val db = MessagingDb.get(appContext)
    private val outbox = OutboxRepository(db)
    private val contacts = ContactRepository(db)
    private val messages = MessageRepository(db)
    private val dispatch = OutboxDispatch(scope)
    private var lastPurge = 0L

    /** Recipients currently being sent to; the caller's wait for the next due entry ignores them. */
    fun busy(): Set<String> = dispatch.busy()

    /**
     * Starts sending every due entry whose recipient isn't already being sent to, and returns at once:
     * a slow recipient no longer holds back messages queued meanwhile for anyone else.
     */
    fun drain() {
        val now = System.currentTimeMillis()
        dispatch.offer(outbox.dueSync(now, exclude = dispatch.busy()), { it.recipientPubkeyHex }, ::send, onLaneDone)
        if (now - lastPurge > PURGE_EVERY_MS) {
            lastPurge = now
            outbox.purgeSentBeforeSync(now - 24L * 60 * 60 * 1000)
        }
    }

    /** One entry. False only after a retryable failure, which ends that recipient's lane for this round. */
    private suspend fun send(entry: OutboxEntry): Boolean {
        val now = System.currentTimeMillis()
        if (OutboxPolicy.isExpired(entry.createdAt, now)) {
            fail(entry.id, entry.payloadId, entry.contactId)
            return true
        }
        val payload = PayloadCodec.decode(entry.payloadJson)
        if (payload == null) {
            fail(entry.id, entry.payloadId, entry.contactId)
            return true
        }
        // A retention sweep/deletion may have removed this row after dueSync took its snapshot.
        if (!db.readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE _id=? AND state=0",arrayOf(entry.id.toString())).use { it.moveToFirst() }) return true
        if (db.readableDatabase.rawQuery("""SELECT 1 FROM group_delivery d WHERE d.payload_id=? AND d.requires_source=1
            AND NOT EXISTS(SELECT 1 FROM group_messages m WHERE m.group_id=d.group_id AND m.event_id=d.event AND m.is_sent=1)""",
            arrayOf(entry.payloadId)).use { it.moveToFirst() }) {
            fail(entry.id, entry.payloadId, entry.contactId)
            return true
        }
        // A contact's current hints, plus any the group roster supplied for a member who isn't one.
        val hints = (entry.contactId?.let { contacts.getByIdSync(it)?.relayHints }.orEmpty() + entry.relayHints).distinct()
        return when (val result = transport.sendWithMedia(entry.recipientPubkeyHex, payload, com.relay.app.media.AndroidPhoneFileStore.refs(db, entry.payloadId), hints)) {
            is SendResult.Sent -> {
                outbox.markSentSync(entry.id)
                messages.setDeliveryStateSync(entry.payloadId, DeliveryState.SENT)
                com.relay.app.groups.GroupDelivery.update(appContext, entry.payloadId, DeliveryState.SENT)
                com.relay.app.sync.SentSync.confirmed(appContext, entry.payloadId)
                broadcast(entry.contactId)
                true
            }
            is SendResult.Failed -> {
                if (!result.retryable) {
                    fail(entry.id, entry.payloadId, entry.contactId)
                    true
                } else {
                    val attempts = entry.attempts + 1
                    outbox.recordAttemptSync(entry.id, attempts, System.currentTimeMillis() + OutboxPolicy.nextDelayMs(attempts))
                    false
                }
            }
        }
    }

    private fun fail(entryId: Long, payloadId: String, contactId: Long?) {
        outbox.markFailedSync(entryId)
        messages.setDeliveryStateSync(payloadId, DeliveryState.FAILED)
        com.relay.app.groups.GroupDelivery.update(appContext, payloadId, DeliveryState.FAILED)
        com.relay.app.sync.SentSync.failed(appContext, payloadId)
        broadcast(contactId)
    }

    private fun broadcast(contactId: Long?) {
        // Group legs to non-contacts are refreshed through GroupDelivery instead.
        if (contactId == null) return
        appContext.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").apply {
            putExtra("contact_id", contactId)
            setPackage(appContext.packageName)
        })
    }

    private companion object { const val PURGE_EVERY_MS = 10 * 60 * 1000L }
}
