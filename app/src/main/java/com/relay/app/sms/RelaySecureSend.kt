package com.relay.app.sms

import android.content.Context
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.Outgoing

/**
 * Entry point the UI uses to send a message body to a contact. Since the move to the internet
 * transport this only queues the message in the durable outbox (sealed with the contact's Tink
 * key when known, then wrapped by the transport); delivery is asynchronous, so `ok` means
 * "accepted for delivery", not "delivered".
 *
 * A contact without a Nostr address (created in the SMS era and never re-paired by QR) cannot be
 * reached: `ok` is false and the UI shows that the message did not send.
 */
object RelaySecureSend {

    /** Outcome of queuing a message. [msgId] is the payload id that ties the stored row to later delivery-state updates. */
    data class SendHandle(val ok: Boolean, val msgId: String? = null)

    fun sendWithId(context: Context, @Suppress("UNUSED_PARAMETER") contactRepo: ContactRepository, contact: Contact, plainBody: String): SendHandle {
        val id = Outgoing.enqueue(context, contact, plainBody)
        return SendHandle(ok = id != null, msgId = id)
    }

    fun send(context: Context, contactRepo: ContactRepository, contact: Contact, plainBody: String): Boolean =
        sendWithId(context, contactRepo, contact, plainBody).ok
}
