package com.relay.app.messaging

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.data.repository.SeenPayloadRepository
import com.relay.app.media.MediaReceiver
import com.relay.app.sms.LocationShareService
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.util.RelayPreferences
import com.relay.app.util.SmsMessageParser

/**
 * Turns a decrypted, transport-authenticated [IncomingEnvelope] into stored messages and side
 * effects. This is the internet-transport counterpart of what SmsReceiver did: same body protocol,
 * same trust rules, but the sender is identified by a cryptographically verified Nostr key instead
 * of a spoofable phone number.
 *
 * Order of checks matters and is deliberate:
 *  1. Idempotency first ([SeenPayloadRepository]) — relays re-deliver, and the transport replays a
 *     multi-day window on each start; a replayed location request must never re-trigger a share and
 *     a deleted contact must not be resurrected by a replayed message.
 *  2. Unknown senders are admitted as `ASK`-trust contacts but rate-limited.
 *  3. Blocked contacts are dropped silently.
 */
class IncomingMessageHandler(context: Context) {

    private val appContext = context.applicationContext
    private val db = MessagingDb.get(appContext)
    private val contactRepo = ContactRepository(db)
    private val messageRepo = MessageRepository(db)
    private val groupMessageRepo = GroupMessageRepository(db)
    private val seenRepo = SeenPayloadRepository(db)

    private val unknownSenders = SlidingWindowLimiter(maxEvents = 10, windowMs = 24L * 60 * 60 * 1000)
    private val locationRequestCooldown = CooldownLimiter(30_000L)
    private val readReceiptCooldown = CooldownLimiter(30_000L)

    fun handle(envelope: IncomingEnvelope) {
        val now = System.currentTimeMillis()
        val payload = envelope.payload

        var contact = contactRepo.findByNostrPubkeySync(envelope.senderPubkeyHex)

        if (!seenRepo.markIfNewSync(payload.id, now)) return

        if (contact == null) {
            if (!unknownSenders.tryAcquire(now)) {
                Log.w(TAG, "unknown-sender flood limit hit; dropping")
                return
            }
            val announcedName = SmsMessageParser.parsePublicKeyName(payload.body)
            contact = contactRepo.findOrCreateByNostrSync(
                envelope.senderPubkeyHex,
                announcedName ?: "Contact ${envelope.senderPubkeyHex.take(8)}",
            )
        }
        if (contact.trustLevel == ContactTrustLevel.BLOCKED) return

        var body = payload.body
        if (SmsMessageParser.isEncryptedMessage(body)) {
            val opened = InnerEnvelope.open(appContext, body)
            if (opened == null) {
                storeReceived(contact, "[Unable to decrypt message]", MessageType.TEXT, payload.id, now, now)
                notifyUpdated(contact.id)
                return
            }
            body = opened
        }

        val sentAt = TimestampPolicy.resolve(payload.ts, now)

        when (val kind = IncomingClassifier.classify(body)) {
            Classified.PublicKey -> handlePublicKey(contact, body)

            Classified.LocationRequest -> {
                contactRepo.markAsRelayUserSync(contact.id)
                if (!TimestampPolicy.isStaleRequest(payload.ts, now)) handleLocationRequest(contact, now)
            }

            is Classified.ReadReceipt -> {
                if (!readReceiptCooldown.allow(contact.id, now)) return
                messageRepo.markReadUpToSync(contact.id, kind.upToTimestamp)
                notifyUpdated(contact.id)
            }

            Classified.Ignore -> Unit

            is Classified.Media -> {
                contactRepo.markAsRelayUserSync(contact.id)
                // Downloading can take a while: do it off the receive loop so other messages keep flowing.
                val ref = kind.ref
                val sender = contact
                MessagingRuntime.launchIo { MediaReceiver.receive(appContext, sender, payload.id, ref, sentAt) }
            }

            is Classified.Pin -> {
                contactRepo.markAsRelayUserSync(contact.id)
                val expiryAt = kind.expiry.durationMs?.let { now + it }
                storeReceived(
                    contact, body, MessageType.LOCATION, payload.id, sentAt, now,
                    lat = kind.lat, lng = kind.lng, pinLabel = kind.label, expiryAt = expiryAt,
                )
                routeToGroups(contact, body, MessageType.LOCATION, sentAt, kind.lat, kind.lng, kind.label, expiryAt)
                notifyUpdated(contact.id)
                MessageNotifier.notifyIncoming(appContext, contact, MessageType.LOCATION)
            }

            Classified.LocationDeclined -> {
                storeReceived(contact, body, MessageType.LOCATION_DECLINED, payload.id, sentAt, now)
                notifyUpdated(contact.id)
            }

            Classified.Text -> {
                storeReceived(contact, body, MessageType.TEXT, payload.id, sentAt, now)
                routeToGroups(contact, body, MessageType.TEXT, sentAt, null, null, null, null)
                notifyUpdated(contact.id)
                MessageNotifier.notifyIncoming(appContext, contact, MessageType.TEXT)
            }
        }
    }

    private fun storeReceived(
        contact: Contact,
        body: String,
        type: MessageType,
        payloadId: String,
        timestamp: Long,
        @Suppress("UNUSED_PARAMETER") receivedAt: Long,
        lat: Double? = null,
        lng: Double? = null,
        pinLabel: String? = null,
        expiryAt: Long? = null,
    ) {
        messageRepo.insertMessageSync(
            Message(
                contactId = contact.id,
                body = body,
                type = type,
                lat = lat,
                lng = lng,
                isSent = false,
                timestamp = timestamp,
                pinLabel = pinLabel,
                expiryAt = expiryAt,
                msgId = payloadId,
                // Authenticated by the Nostr seal signature, so no separate "unverified sender" state.
                senderVerified = true,
            )
        )
    }

    /** Mirrors a message into every group the sender belongs to, as SmsReceiver did. */
    private fun routeToGroups(
        contact: Contact,
        body: String,
        type: MessageType,
        timestamp: Long,
        lat: Double?,
        lng: Double?,
        pinLabel: String?,
        expiryAt: Long?,
    ) {
        val groupIds = runCatching {
            val cursor = db.readableDatabase.rawQuery(
                "SELECT g._id FROM groups g INNER JOIN group_members gm ON g._id = gm.group_id WHERE gm.contact_id = ?",
                arrayOf(contact.id.toString()),
            )
            cursor.use { c ->
                val ids = mutableListOf<Long>()
                while (c.moveToNext()) ids.add(c.getLong(0))
                ids
            }
        }.getOrDefault(emptyList())

        for (groupId in groupIds) {
            groupMessageRepo.insertMessageSync(
                GroupMessage(
                    groupId = groupId,
                    contactId = contact.id,
                    body = body,
                    type = type,
                    lat = lat,
                    lng = lng,
                    isSent = false,
                    timestamp = timestamp,
                    pinLabel = pinLabel,
                    expiryAt = expiryAt,
                )
            )
            appContext.sendBroadcast(Intent("com.relay.app.NEW_GROUP_MESSAGE").apply {
                putExtra("group_id", groupId)
                setPackage(appContext.packageName)
            })
        }
    }

    /**
     * Handshake / rotation. The sender is already authenticated (Nostr seal), so the only question is
     * whether a *different* Tink key than the trusted one is legitimate: a rotation signed by the
     * contact's long-term signing key is auto-accepted, anything else is held for explicit review.
     */
    private fun handlePublicKey(contact: Contact, body: String) {
        val key = SmsMessageParser.parsePublicKey(body) ?: return
        val existing = contact.publicKey

        if (existing != null && existing != key) {
            val rotationSig = SmsMessageParser.parsePublicKeyRotationSignature(body)
            val signingKey = contact.signingPublicKey
            val keyBytes = if (rotationSig != null && signingKey != null) {
                try { Base64.decode(key, Base64.NO_WRAP) } catch (e: IllegalArgumentException) { null }
            } else null
            val verifiedRotation = keyBytes != null && rotationSig != null && signingKey != null &&
                RelayCrypto.verifyBytes(keyBytes, rotationSig, signingKey)
            if (verifiedRotation) {
                contactRepo.setPublicKeySync(contact.id, key)
                contactRepo.rejectPendingPublicKeySync(contact.id)
            } else {
                contactRepo.setPendingPublicKeySync(contact.id, key)
                SecurityNotifier.showKeyChange(appContext, contact)
            }
            notifyUpdated(contact.id)
            return
        }
        if (existing == null) contactRepo.setPublicKeySync(contact.id, key)
        contactRepo.markAsRelayUserSync(contact.id)
        SmsMessageParser.parsePublicKeySigningKey(body)?.let {
            contactRepo.setSigningPublicKeyIfAbsentSync(contact.id, it)
        }
        val announcedName = SmsMessageParser.parsePublicKeyName(body)
        if (announcedName != null && contact.name.startsWith("Contact ")) {
            contactRepo.setNameSync(contact.id, announcedName)
        }
        notifyUpdated(contact.id)

        // Complete the handshake: reply with our keys once, so they can encrypt to us too.
        if (!contactRepo.hasSentPubkeySync(contact.id)) {
            val myKey = RelayCrypto.myPublicKeyBase64(appContext) ?: return
            val reply = SmsMessageParser.formatPublicKey(
                myKey,
                RelayPreferences(appContext).myName,
                signingKeyBase64 = RelayCrypto.mySigningPublicKeyBase64(appContext),
            )
            val fresh = contactRepo.getByIdSync(contact.id) ?: contact
            if (Outgoing.enqueue(appContext, fresh, reply, seal = false) != null) {
                contactRepo.markSentPubkeySync(contact.id)
                MessagingRuntime.kick()
            }
        }
    }

    private fun handleLocationRequest(contact: Contact, now: Long) {
        if (!locationRequestCooldown.allow(contact.id, now)) return
        val prefs = RelayPreferences(appContext)
        if (prefs.locationRequestFrom == RelayPreferences.FROM_NOBODY) return
        if (contact.trustLevel == ContactTrustLevel.BLOCKED) return
        if (isWithinDndWindow(prefs)) return

        if (prefs.autoApproveLocationRequests || contact.trustLevel == ContactTrustLevel.TRUSTED) {
            appContext.startService(
                Intent(appContext, LocationShareService::class.java).apply {
                    action = LocationShareService.ACTION_SHARE
                    putExtra(LocationShareService.EXTRA_PHONE, contact.phone)
                    putExtra(LocationShareService.EXTRA_CONTACT_ID, contact.id)
                    putExtra(LocationShareService.EXTRA_CONTACT_NAME, contact.name)
                    putExtra(LocationShareService.EXTRA_NOTIFY, prefs.notifyOnAutoShare)
                }
            )
        } else {
            LocationRequestNotifier.show(appContext, contact, prefs.notifyOnAutoShare)
        }
    }

    private fun isWithinDndWindow(prefs: RelayPreferences): Boolean {
        if (!prefs.dndEnabled) return false
        return DndWindow.contains(prefs.dndStartHour, prefs.dndEndHour, java.time.LocalTime.now().hour)
    }

    private fun notifyUpdated(contactId: Long) {
        appContext.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").apply {
            putExtra("contact_id", contactId)
            setPackage(appContext.packageName)
        })
    }

    private companion object {
        const val TAG = "IncomingHandler"
    }
}

/** Do-not-disturb window arithmetic, with wraparound (e.g. 22 -> 7). Pure, so unit-tested. */
object DndWindow {
    fun contains(startHour: Int, endHour: Int, hour: Int): Boolean = when {
        startHour == endHour -> true
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }
}
