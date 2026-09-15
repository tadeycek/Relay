package com.relay.app.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.relay.app.MainActivity
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.model.PinExpiry
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.data.repository.GroupRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.util.RelayPreferences
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // SQLCipher DB opens, Tink crypto, and (on the group-routing path) further queries are
        // real work, not appropriate to run synchronously on whatever thread delivered this
        // broadcast (the main thread) — goAsync() + a background coroutine keeps the receiver
        // itself fast while still telling the system to wait for us to finish.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleMessages(context, messages)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleMessages(context: Context, messages: Array<SmsMessage>) {
        val grouped = messages.groupBy { it.originatingAddress }

        val db = RelayDbHelper(context)
        val contactRepo = ContactRepository(db)
        val messageRepo = MessageRepository(db)
        val groupRepo = GroupRepository(db)
        val groupMessageRepo = GroupMessageRepository(db)

        for ((phone, parts) in grouped) {
            if (phone == null) continue
            val rawBody = parts.joinToString("") { it.messageBody }

            // Unknown numbers still get a thread (matching how any default SMS app behaves)
            // rather than having the message silently dropped.
            val contact = contactRepo.findOrCreateByPhoneSync(phone)

            if (SmsMessageParser.isPublicKeyMessage(rawBody)) {
                handleIncomingPublicKey(context, contactRepo, contact, rawBody)
                continue
            }

            var body = rawBody
            var senderVerified = true
            if (SmsMessageParser.isEncryptedMessage(rawBody)) {
                val decrypted = decryptIncoming(context, contact, rawBody)
                if (decrypted == null) {
                    messageRepo.insertMessageSync(
                        Message(
                            contactId = contact.id,
                            body = "[Unable to decrypt message]",
                            type = MessageType.TEXT,
                            isSent = false,
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                    broadcastUpdate(context, contact.id)
                    continue
                }
                body = decrypted.body
                senderVerified = decrypted.senderVerified
            }

            if (SmsMessageParser.isLocationRequest(body)) {
                contactRepo.markAsRelayUserSync(contact.id)
                handleLocationRequest(context, contact)
                continue
            }

            if (SmsMessageParser.isReadReceipt(body)) {
                if (!allowRateLimited(lastReadReceiptAt, contact.id)) continue
                val ts = SmsMessageParser.parseReadReceipt(body) ?: continue
                messageRepo.markReadUpToSync(contact.id, ts)
                broadcastUpdate(context, contact.id)
                continue
            }

            val parsedLoc = SmsMessageParser.parseLocation(body)
            val isDeclined = SmsMessageParser.isLocationDeclined(body)

            if (parsedLoc != null) {
                contactRepo.markAsRelayUserSync(contact.id)
            }

            val type = when {
                parsedLoc != null -> MessageType.LOCATION
                isDeclined -> MessageType.LOCATION_DECLINED
                else -> MessageType.TEXT
            }

            val expiry: PinExpiry
            val pinLabel: String?
            val expiryAt: Long?
            if (parsedLoc != null) {
                expiry = SmsMessageParser.parseExpiry(body)
                pinLabel = SmsMessageParser.parseLabel(body)
                expiryAt = expiry.durationMs?.let { System.currentTimeMillis() + it }
            } else {
                expiry = PinExpiry.NEVER
                pinLabel = null
                expiryAt = null
            }

            val message = Message(
                contactId = contact.id,
                body = body,
                type = type,
                lat = parsedLoc?.lat,
                lng = parsedLoc?.lng,
                isSent = false,
                timestamp = System.currentTimeMillis(),
                pinLabel = pinLabel,
                expiryAt = expiryAt,
                senderVerified = senderVerified,
            )
            messageRepo.insertMessageSync(message)
            Log.d("SmsReceiver", "Stored message from ${contact.name}: type=${message.type}")

            // Route to group chats if the contact is a member
            if (type != MessageType.LOCATION_DECLINED) {
                val groups = runCatching {
                    // Sync call — use raw query on same thread. Reuses the RelayDbHelper already
                    // open in this function rather than opening a second SQLCipher connection
                    // (Keystore-backed passphrase derivation + DB open) per message.
                    val groupDb = db.readableDatabase
                    val cursor = groupDb.rawQuery(
                        "SELECT g._id, g.name FROM groups g INNER JOIN group_members gm ON g._id = gm.group_id WHERE gm.contact_id = ?",
                        arrayOf(contact.id.toString())
                    )
                    val list = mutableListOf<Pair<Long, String>>()
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            list.add(c.getLong(0) to c.getString(1))
                        }
                    }
                    list
                }.getOrDefault(emptyList())

                for ((groupId, _) in groups) {
                    groupMessageRepo.insertMessageSync(GroupMessage(
                        groupId = groupId,
                        contactId = contact.id,
                        body = body,
                        type = type,
                        lat = parsedLoc?.lat,
                        lng = parsedLoc?.lng,
                        isSent = false,
                        timestamp = System.currentTimeMillis(),
                        pinLabel = pinLabel,
                        expiryAt = expiryAt,
                    ))
                    broadcastGroupUpdate(context, groupId)
                }
            }

            broadcastUpdate(context, contact.id)
        }
    }

    /**
     * Learn a contact's public key and, on the first exchange, reply with ours so both sides end
     * up encrypted. Trust-on-first-use only: SMS sender addresses can be spoofed, so a key that
     * *contradicts* one we already trust for this contact is held as a pending candidate for the
     * user to explicitly accept/reject (see ContactsScreen) rather than silently swapped in —
     * otherwise an attacker could spoof a PUBKEY message and silently redirect our encryption to a
     * key they control.
     *
     * Exception: a key change carrying a valid [SmsMessageParser.parsePublicKeyRotationSignature]
     * — verified against the contact's already-trusted [Contact.signingPublicKey] — is a routine
     * key rotation (see RelayCrypto.rotateIdentityKey), not a spoof, so it's accepted immediately
     * without the pending-review dialog. The signing key itself is never updated this way; it's
     * only ever learned once (see [contact].signingPublicKey persistence in ContactRepository).
     */
    private fun handleIncomingPublicKey(
        context: Context,
        contactRepo: ContactRepository,
        contact: Contact,
        body: String,
    ) {
        val key = SmsMessageParser.parsePublicKey(body) ?: return
        val existing = contact.publicKey

        if (existing != null && existing != key) {
            val rotationSig = SmsMessageParser.parsePublicKeyRotationSignature(body)
            val signingKey = contact.signingPublicKey
            val keyBytes = if (rotationSig != null && signingKey != null) {
                try { Base64.decode(key, Base64.NO_WRAP) } catch (e: Exception) { null }
            } else null
            val isVerifiedRotation = keyBytes != null && rotationSig != null && signingKey != null &&
                RelayCrypto.verifyBytes(keyBytes, rotationSig, signingKey)
            if (isVerifiedRotation) {
                contactRepo.setPublicKeySync(contact.id, key)
                contactRepo.rejectPendingPublicKeySync(contact.id)
            } else {
                contactRepo.setPendingPublicKeySync(contact.id, key)
                showKeyChangeNotification(context, contact)
            }
            return
        }
        if (existing == null) {
            contactRepo.setPublicKeySync(contact.id, key)
        }
        contactRepo.markAsRelayUserSync(contact.id)

        // Learn the signing key once, the first time we see it — never overwritten afterward
        // (see ContactRepository.setSigningPublicKeyIfAbsentSync).
        SmsMessageParser.parsePublicKeySigningKey(body)?.let {
            contactRepo.setSigningPublicKeyIfAbsentSync(contact.id, it)
        }

        // Learn a display name from the handshake only if we don't already have a real one
        // (findOrCreateByPhoneSync defaults an unknown contact's name to their phone number).
        val incomingName = SmsMessageParser.parsePublicKeyName(body)
        if (incomingName != null && contact.name == contact.phone) {
            contactRepo.setNameSync(contact.id, incomingName)
        }

        if (!contactRepo.hasSentPubkeySync(contact.id)) {
            val myKey = RelayCrypto.myPublicKeyBase64(context) ?: return
            val mySigningKey = RelayCrypto.mySigningPublicKeyBase64(context)
            // Only mark sent if the SMS actually went out; otherwise the peer never gets our key.
            val sent = SmsSender.sendSms(
                context,
                contact.phone,
                SmsMessageParser.formatPublicKey(myKey, signingKeyBase64 = mySigningKey),
            )
            if (sent) contactRepo.markSentPubkeySync(contact.id)
        }
    }

    private fun showKeyChangeNotification(context: Context, contact: Contact) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(RelayPreferences.SECURITY_ALERT_CHANNEL) == null) {
            val channel = NotificationChannel(
                RelayPreferences.SECURITY_ALERT_CHANNEL,
                "Security alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Encryption key changes and other security-relevant events" }
            nm.createNotificationChannel(channel)
        }
        val openContactsIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_contacts", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            SECURITY_NOTIF_BASE + (contact.id % 1000).toInt(),
            openContactsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RelayPreferences.SECURITY_ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("${contact.name}'s encryption key changed")
            .setContentText("Review and confirm in Contacts before trusting it")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        nm.notify(SECURITY_NOTIF_BASE + (contact.id % 1000).toInt(), notification)
    }

    private data class DecryptedIncoming(val body: String, val senderVerified: Boolean)

    /**
     * Decrypts an incoming `TYPE:ENC` message and checks whether it's authentically from
     * [contact]. Tink's hybrid encryption here only proves the ciphertext was encrypted to *our*
     * public key — anyone who knows it can produce a decryptable ciphertext, so without a
     * separate signature check a spoofed SMS sender ID could deliver a forged "encrypted" message
     * that looks identical to a real one. [DecryptedIncoming.senderVerified] is false whenever
     * that check can't be done or fails; callers still show the message (see onReceive) but mark
     * it distinctly rather than silently trusting it.
     */
    private fun decryptIncoming(context: Context, contact: Contact, body: String): DecryptedIncoming? {
        val ct = SmsMessageParser.parseEncryptedPayload(body) ?: return null
        val bytes = try {
            Base64.decode(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
        val plain = RelayCrypto.decryptMine(context, bytes) ?: return null
        val plainText = try {
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        val signature = SmsMessageParser.parseEncryptedSignature(body)
        val signingKey = contact.signingPublicKey
        val verified = signature != null && signingKey != null &&
            RelayCrypto.verifyBytes(bytes, signature, signingKey)
        return DecryptedIncoming(plainText, verified)
    }

    /** Simple per-contact cooldown so a malicious/misbehaving sender can't flood location requests or receipts. */
    private fun allowRateLimited(lastAt: MutableMap<Long, Long>, contactId: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAt[contactId]
        if (last != null && now - last < RATE_LIMIT_WINDOW_MS) return false
        lastAt[contactId] = now
        return true
    }

    private fun broadcastUpdate(context: Context, contactId: Long) {
        val update = Intent("com.relay.app.NEW_MESSAGE").apply {
            putExtra("contact_id", contactId)
            setPackage(context.packageName)
        }
        context.sendBroadcast(update)
    }

    private fun broadcastGroupUpdate(context: Context, groupId: Long) {
        val update = Intent("com.relay.app.NEW_GROUP_MESSAGE").apply {
            putExtra("group_id", groupId)
            setPackage(context.packageName)
        }
        context.sendBroadcast(update)
    }

    private fun handleLocationRequest(context: Context, contact: Contact) {
        if (!allowRateLimited(lastLocationRequestAt, contact.id)) return

        val prefs = RelayPreferences(context)

        if (prefs.locationRequestFrom == RelayPreferences.FROM_NOBODY) return
        if (contact.trustLevel == ContactTrustLevel.BLOCKED) return
        if (isWithinDndWindow(prefs)) return

        if (prefs.autoApproveLocationRequests || contact.trustLevel == ContactTrustLevel.TRUSTED) {
            val serviceIntent = Intent(context, LocationShareService::class.java).apply {
                action = LocationShareService.ACTION_SHARE
                putExtra(LocationShareService.EXTRA_PHONE, contact.phone)
                putExtra(LocationShareService.EXTRA_CONTACT_ID, contact.id)
                putExtra(LocationShareService.EXTRA_CONTACT_NAME, contact.name)
                putExtra(LocationShareService.EXTRA_NOTIFY, prefs.notifyOnAutoShare)
            }
            context.startService(serviceIntent)
        } else {
            showLocationRequestNotification(context, contact, prefs.notifyOnAutoShare)
        }
    }

    private fun showLocationRequestNotification(
        context: Context,
        contact: Contact,
        notifyOnShare: Boolean,
    ) {
        ensureChannel(context)

        val shareIntent = PendingIntent.getService(
            context,
            (contact.id * 2).toInt(),
            Intent(context, LocationShareService::class.java).apply {
                action = LocationShareService.ACTION_SHARE
                putExtra(LocationShareService.EXTRA_PHONE, contact.phone)
                putExtra(LocationShareService.EXTRA_CONTACT_ID, contact.id)
                putExtra(LocationShareService.EXTRA_CONTACT_NAME, contact.name)
                putExtra(LocationShareService.EXTRA_NOTIFY, notifyOnShare)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val declineIntent = PendingIntent.getService(
            context,
            (contact.id * 2 + 1).toInt(),
            Intent(context, LocationShareService::class.java).apply {
                action = LocationShareService.ACTION_DECLINE
                putExtra(LocationShareService.EXTRA_PHONE, contact.phone)
                putExtra(LocationShareService.EXTRA_CONTACT_ID, contact.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, RelayPreferences.LOCATION_REQUEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("${contact.name} is requesting your location")
            .setContentText("Tap Share or Decline to respond")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_mylocation, "Share", shareIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declineIntent)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(REQUEST_NOTIF_BASE + (contact.id % 1000).toInt(), notification)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(RelayPreferences.LOCATION_REQUEST_CHANNEL) != null) return
        val channel = NotificationChannel(
            RelayPreferences.LOCATION_REQUEST_CHANNEL,
            "Location Requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifications for location sharing requests"
        }
        nm.createNotificationChannel(channel)
    }

    private fun isWithinDndWindow(prefs: RelayPreferences): Boolean {
        if (!prefs.dndEnabled) return false
        val start = prefs.dndStartHour
        val end = prefs.dndEndHour
        val hour = java.time.LocalTime.now().hour
        return if (start == end) {
            true
        } else if (start < end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    companion object {
        private const val REQUEST_NOTIF_BASE = 3000
        private const val SECURITY_NOTIF_BASE = 4000
        private const val RATE_LIMIT_WINDOW_MS = 30_000L
        private val lastLocationRequestAt = mutableMapOf<Long, Long>()
        private val lastReadReceiptAt = mutableMapOf<Long, Long>()
    }
}
