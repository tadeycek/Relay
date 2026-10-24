package com.relay.app.calls

import android.content.Context
import android.content.Intent
import android.util.Log
import com.relay.app.call.CallEffect
import com.relay.app.data.model.Message
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.messaging.MessagingDb

/**
 * Writes a locally observed call event into the conversation (WP13: missed/declined/completed rows).
 * Never sent: the row carries the `TYPE:CALLLOG` marker, is only written once per call id, and is
 * rendered by the timeline as a centred note rather than a message.
 */
object CallRecords {

    private const val TAG = "RelayCallRecords"

    fun store(context: Context, event: CallEffect.RecordCallEvent): Boolean {
        val app = context.applicationContext
        return try {
            val contact = ContactRepository(MessagingDb.get(app)).findByNostrPubkeySync(event.peer)
                ?: return false.also { Log.i(TAG, "no contact for ${event.peer}; call event not stored") }
            val repo = MessageRepository(MessagingDb.get(app))
            val msgId = CallRecord.messageId(event.callId)
            if (repo.hasMsgIdSync(contact.id, msgId)) return false
            val body = CallRecord.encode(
                callId = event.callId,
                incoming = event.incoming,
                outcome = event.outcome,
                reason = event.reason,
                durationMs = event.durationMs,
                atMs = System.currentTimeMillis(),
            )
            val now = System.currentTimeMillis()
            repo.insertMessageSync(
                Message(
                    contactId = contact.id,
                    body = body,
                    type = com.relay.app.data.model.MessageType.TEXT,
                    isSent = !event.incoming,
                    timestamp = now,
                    msgId = msgId,
                    deliveryState = CallRecord.LOCAL_DELIVERY_STATE,
                    senderVerified = true,
                    // A missed call is worth a badge; "call ended" is not.
                    unread = event.incoming && event.outcome == com.relay.app.call.CallOutcome.MISSED,
                ),
            )
            app.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(app.packageName).putExtra("contact_id", contact.id))
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't store the call event", e)
            false
        }
    }
}
