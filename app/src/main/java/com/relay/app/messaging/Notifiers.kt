package com.relay.app.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.relay.app.MainActivity
import com.relay.app.data.model.Contact
import com.relay.app.data.model.MessageType
import com.relay.app.util.RelayPreferences

/** Notifications for incoming internet messages. Deliberately shows no message content. */
object MessageNotifier {

    const val CHANNEL_MESSAGES = "relay_messages"
    const val EXTRA_OPEN_CHAT = "open_chat_contact_id"
    private const val NOTIF_BASE = 5000

    fun notifyIncoming(context: Context, contact: Contact, type: MessageType) {
        if (contact.id == ActiveChat.contactId) return
        val prefs = RelayPreferences(context)
        val wanted = when (type) {
            MessageType.LOCATION -> prefs.incomingPinNotification
            MessageType.TEXT, MessageType.IMAGE, MessageType.VIDEO -> prefs.incomingMessageNotification
            else -> false
        }
        if (!wanted || !canPostNotifications(context)) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_CHAT, contact.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIF_BASE + (contact.id % 1000).toInt(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (type == MessageType.LOCATION) "Shared a location pin" else "New message"
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(contact.name)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        nm.notify(NOTIF_BASE + (contact.id % 1000).toInt(), notification)
    }

    /** Removes the notification for a conversation once the user has opened it. */
    fun cancel(context: Context, contactId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_BASE + (contactId % 1000).toInt())
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_MESSAGES) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "New messages and shared pins from your contacts" }
        )
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}

/** Security-relevant alerts (encryption key changed). */
object SecurityNotifier {

    private const val NOTIF_BASE = 4000

    fun showKeyChange(context: Context, contact: Contact) {
        if (!MessageNotifier.canPostNotifications(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(RelayPreferences.SECURITY_ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    RelayPreferences.SECURITY_ALERT_CHANNEL,
                    "Security alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Encryption key changes and other security-relevant events" }
            )
        }
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra("open_contacts", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val id = NOTIF_BASE + (contact.id % 1000).toInt()
        val contentIntent = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RelayPreferences.SECURITY_ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("${contact.name}'s encryption key changed")
            .setContentText("Review and confirm in Contacts before trusting it")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        nm.notify(id, notification)
    }
}

/** Notification with Share/Decline actions for an incoming location request from a non-trusted contact. */
object LocationRequestNotifier {

    private const val NOTIF_BASE = 3000

    fun show(context: Context, contact: Contact, notifyOnShare: Boolean) {
        if (!MessageNotifier.canPostNotifications(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(RelayPreferences.LOCATION_REQUEST_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    RelayPreferences.LOCATION_REQUEST_CHANNEL,
                    "Location Requests",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Notifications for location sharing requests" }
            )
        }

        fun serviceIntent(action: String, requestCode: Int, notify: Boolean): PendingIntent =
            PendingIntent.getService(
                context,
                requestCode,
                Intent(context, com.relay.app.sms.LocationShareService::class.java).apply {
                    this.action = action
                    putExtra(com.relay.app.sms.LocationShareService.EXTRA_PHONE, contact.phone)
                    putExtra(com.relay.app.sms.LocationShareService.EXTRA_CONTACT_ID, contact.id)
                    putExtra(com.relay.app.sms.LocationShareService.EXTRA_CONTACT_NAME, contact.name)
                    putExtra(com.relay.app.sms.LocationShareService.EXTRA_NOTIFY, notify)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val share = serviceIntent(com.relay.app.sms.LocationShareService.ACTION_SHARE, (contact.id * 2).toInt(), notifyOnShare)
        val decline = serviceIntent(com.relay.app.sms.LocationShareService.ACTION_DECLINE, (contact.id * 2 + 1).toInt(), false)

        val notification = NotificationCompat.Builder(context, RelayPreferences.LOCATION_REQUEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("${contact.name} is requesting your location")
            .setContentText("Tap Share or Decline to respond")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_mylocation, "Share", share)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
            .build()
        nm.notify(NOTIF_BASE + (contact.id % 1000).toInt(), notification)
    }
}
