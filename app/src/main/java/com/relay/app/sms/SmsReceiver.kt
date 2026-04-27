package com.relay.app.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
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

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val grouped = messages.groupBy { it.originatingAddress }

        val db = RelayDbHelper(context)
        val contactRepo = ContactRepository(db)
        val messageRepo = MessageRepository(db)
        val groupRepo = GroupRepository(db)
        val groupMessageRepo = GroupMessageRepository(db)

        for ((phone, parts) in grouped) {
            if (phone == null) continue
            val body = parts.joinToString("") { it.messageBody }

            val contact = contactRepo.findByPhoneSync(phone) ?: continue

            if (SmsMessageParser.isLocationRequest(body)) {
                contactRepo.markAsRelayUserSync(contact.id)
                handleLocationRequest(context, contact)
                continue
            }

            if (SmsMessageParser.isReadReceipt(body)) {
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

            val prefs = RelayPreferences(context)
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
            )
            messageRepo.insertMessageSync(message)
            Log.d("SmsReceiver", "Stored message from ${contact.name}: type=${message.type}")

            // Route to group chats if the contact is a member
            if (type != MessageType.LOCATION_DECLINED) {
                val groups = runCatching {
                    // Sync call — use raw query on same thread
                    val groupsDb = RelayDbHelper(context)
                    val repo = GroupRepository(groupsDb)
                    // We can't call suspend here, use a blocking approach via rawQuery
                    val groupDb = groupsDb.readableDatabase
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
    }
}
