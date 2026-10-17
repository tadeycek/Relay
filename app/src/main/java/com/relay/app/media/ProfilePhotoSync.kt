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
            for (contact in ContactRepository(MessagingDb.get(context)).getAllContacts()) {
                if (eligible(contact)) {
                    queue(context, contact, update)
                    queueName(context, contact)
                    requestProfile(context, contact)
                }
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

    @Synchronized private fun queueName(context: Context, contact: Contact, force: Boolean = false) {
        val self = com.relay.app.util.RelayPreferences(context)
        val update = ProfileNameBody.Update(self.profileNameRevision, self.myName.trim())
        val body = runCatching { ProfileNameBody.format(update) }.getOrNull() ?: return
        if (!force && prefs(context).getLong("name_queued_${contact.id}", -1) >= update.revision) return
        if (Outgoing.enqueue(context, contact, body) != null) {
            check(prefs(context).edit().putLong("name_queued_${contact.id}", update.revision).commit())
        }
    }

    private val outgoingRequests = CooldownLimiter(30_000L)
    fun requestProfile(context: Context, contact: Contact) {
        if (eligible(contact) && outgoingRequests.allow(contact.id, System.currentTimeMillis())) {
            Outgoing.enqueue(context, contact, ProfilePhotoBody.REQUEST)
        }
    }

    fun afterPairing(context: Context, contact: Contact) {
        if (!eligible(contact)) return
        MessagingRuntime.launchIo {
            queue(context, contact, ContactPhotos.selfUpdate(context))
            queueName(context, contact)
            requestProfile(context, contact)
        }
    }

    private fun refresh(context: Context, id: Long) {
        val fresh = ContactRepository(MessagingDb.get(context)).getByIdSync(id) ?: return
        com.relay.app.messaging.MessageNotifier.refreshContact(context, fresh)
        context.sendBroadcast(android.content.Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName)
            .putExtra("contact_id", id))
    }

    fun handle(context: Context, contact: Contact, body: String): Boolean {
        if (!ProfilePhotoBody.isControl(body) && !ProfileNameBody.isControl(body)) return false
        if (!eligible(contact)) return true
        if (body == ProfilePhotoBody.REQUEST) {
            if (requests.allow(contact.id, System.currentTimeMillis())) {
                queue(context, contact, ContactPhotos.selfUpdate(context), force = true)
                queueName(context, contact, force = true)
            }
        } else if (ProfileNameBody.isControl(body)) {
            ProfileNameBody.parse(body)?.let { update ->
                synchronized(this) {
                    if (update.revision > prefs(context).getLong("name_received_${contact.id}", -1)) {
                        ContactRepository(MessagingDb.get(context)).setProfileNameSync(contact.id, update.name)
                        check(prefs(context).edit().putLong("name_received_${contact.id}", update.revision).commit())
                        refresh(context, contact.id)
                    }
                }
            }
        } else {
            ProfilePhotoBody.parse(body)?.let {
                if (ContactPhotos.receive(context, contact.id, it)) refresh(context, contact.id)
            }
        }
        return true
    }
}
