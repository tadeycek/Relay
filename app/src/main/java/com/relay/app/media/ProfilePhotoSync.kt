package com.relay.app.media

import android.content.Context
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.MessagingRuntime
import com.relay.app.messaging.Outgoing
import com.relay.app.messaging.CooldownLimiter

/** Uses only the authenticated, encrypted conversation channel. Unapproved senders are ignored. */
object ProfilePhotoSync {
    private val requests = CooldownLimiter(30_000L)
    private fun eligible(contact: Contact) = contact.qrVerified && !contact.isDeleted &&
        contact.trustLevel != ContactTrustLevel.BLOCKED && contact.publicKey != null &&
        contact.pendingPublicKey == null && contact.nostrPubkey != null
    private fun prefs(context: Context) = context.getSharedPreferences("profile_photo_state", Context.MODE_PRIVATE)

    fun syncPending(context: Context) {
        MessagingRuntime.launchIo {
            val update = ContactPhotos.selfUpdate(context)
            if (update.revision <= 0) return@launchIo
            for (contact in ContactRepository(MessagingDb.get(context)).getAllContacts()) {
                if (eligible(contact)) queue(context, contact, update)
            }
        }
    }

    @Synchronized private fun queue(context: Context, contact: Contact, update: ProfilePhotoBody.Update, force: Boolean = false) {
        if (!eligible(contact)) return
        if (!force && prefs(context).getLong("queued_${contact.id}", -1) >= update.revision) return
        if (Outgoing.enqueue(context, contact, ProfilePhotoBody.format(update)) != null) {
            check(prefs(context).edit().putLong("queued_${contact.id}", update.revision).commit())
        }
    }

    fun afterPairing(context: Context, contact: Contact) {
        if (!eligible(contact)) return
        MessagingRuntime.launchIo {
            queue(context, contact, ContactPhotos.selfUpdate(context))
            Outgoing.enqueue(context, contact, ProfilePhotoBody.REQUEST)
        }
    }

    fun handle(context: Context, contact: Contact, body: String): Boolean {
        if (!ProfilePhotoBody.isControl(body)) return false
        if (!eligible(contact)) return true
        if (body == ProfilePhotoBody.REQUEST) {
            if (requests.allow(contact.id, System.currentTimeMillis())) {
                queue(context, contact, ContactPhotos.selfUpdate(context), force = true)
            }
        } else {
            ProfilePhotoBody.parse(body)?.let { ContactPhotos.receive(context, contact.id, it) }
        }
        return true
    }
}
