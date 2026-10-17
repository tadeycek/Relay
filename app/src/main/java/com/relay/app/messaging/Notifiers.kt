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
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.relay.app.MainActivity
import com.relay.app.data.model.Contact
import com.relay.app.data.model.MessageType
import com.relay.app.util.RelayPreferences

/** Conversation previews. A tap only dismisses the preview through a private receiver. */
object MessageNotifier {

    const val CHANNEL_MESSAGES = "relay_messages"
    const val EXTRA_OPEN_CHAT = "open_chat_contact_id"
    private const val NOTIF_BASE = 5000
    private val shortcutFingerprints = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun notifyIncoming(context: Context, receivedContact: Contact, type: MessageType, body: String = "") {
        val contact = com.relay.app.data.repository.ContactRepository(MessagingDb.get(context))
            .getByIdSync(receivedContact.id) ?: receivedContact
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

        val clean = ReplyBody.preview(body).replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim().take(240)
        val text = when (type) {
            MessageType.LOCATION -> "Shared a location"
            MessageType.IMAGE -> if (clean.isEmpty()) "Photo" else "Photo: $clean"
            MessageType.VIDEO -> if (clean.isEmpty()) "Video" else "Video: $clean"
            else -> clean.ifEmpty { "New message" }
        }
        post(context, contact, text, refresh = false)
    }

    /** Refresh an already visible preview after encrypted profile data arrives, without another alert. */
    fun refreshContact(context: Context, contact: Contact) {
        if (canPostNotifications(context)) post(context, contact, null, refresh = true)
    }

    private fun post(context: Context, contact: Contact, text: String?, refresh: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val tag = "conversation:${contact.id}"
        val previousNotification = nm.activeNotifications.firstOrNull { it.id == NOTIF_BASE && it.tag == tag }?.notification
        val previous = NotificationPreview.history(previousNotification)
        if (refresh && previous.isEmpty()) return
        val photo = com.relay.app.media.ContactPhotos.load(context, contact.id)
        val badge = android.graphics.BitmapFactory.decodeResource(context.resources, com.relay.app.R.drawable.notification_sender_badge)
        val avatar = NotificationAvatar.forContact(contact, photo, badge)
        badge?.recycle()
        photo?.recycle()
        val sender = Person.Builder().setName(contact.name).setKey(contact.id.toString())
            .setIcon(IconCompat.createWithBitmap(avatar)).build()
        val shortcutId = "conversation:${contact.id}"
        val pixels = IntArray(avatar.width * avatar.height)
        avatar.getPixels(pixels, 0, avatar.width, 0, 0, avatar.width, avatar.height)
        val fingerprint = 31 * contact.name.hashCode() + pixels.contentHashCode()
        val existing = ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == shortcutId }
        val unchanged = existing && shortcutFingerprints[shortcutId] == fingerprint
        // Avoid spending Android's background shortcut-update quota on every message.
        val published = unchanged || runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(context, ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(contact.name.ifBlank { "Contact" })
                .setIcon(IconCompat.createWithBitmap(avatar))
                .setPerson(sender).setLongLived(true)
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                .build())
        }.getOrDefault(false)
        if (published) shortcutFingerprints[shortcutId] = fingerprint
        val lines = PreviewHistory.update(previous, text, refresh, System.currentTimeMillis())
        val last = lines.last()
        val dismiss = PendingIntent.getBroadcast(context, 0,
            Intent(context, NotificationDismissReceiver::class.java)
                .setAction(NotificationDismissReceiver.ACTION_DISMISS)
                .setData(android.net.Uri.parse("relay-preview://dismiss/${contact.id}"))
                .putExtra(NotificationDismissReceiver.EXTRA_CONTACT_ID, contact.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(com.relay.app.R.drawable.ic_message_notification)
            .setColor(0xFF7D519E.toInt())
            .setContentTitle(contact.name)
            .setContentText(last.text)
            .setWhen(last.timestamp)
            .setOnlyAlertOnce(refresh)
            .setShortcutId(if (published || existing) shortcutId else null)
            .addPerson(sender)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(NotificationPreview.view(context, contact.name, avatar, lines, expanded = false))
            .setCustomBigContentView(NotificationPreview.view(context, contact.name, avatar, lines, expanded = true))
            .setCustomHeadsUpContentView(NotificationPreview.view(context, contact.name, avatar, lines, expanded = false))
            .addExtras(NotificationPreview.extras(lines))
            .setAllowSystemGeneratedContextualActions(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(dismiss)
            .setDeleteIntent(dismiss)
            .build()
        nm.cancel(NOTIF_BASE + (contact.id % 1000).toInt()) // retire the previous app version's ID
        nm.notify(tag, NOTIF_BASE, notification)
    }

    /** Removes the notification for a conversation once the user has opened it. */
    fun cancel(context: Context, contactId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shortcutFingerprints.remove("conversation:$contactId")
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf("conversation:$contactId"))
        ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf("conversation:$contactId"))
        nm.cancel("conversation:$contactId", NOTIF_BASE)
        nm.cancel(NOTIF_BASE + (contactId % 1000).toInt())
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_MESSAGES) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Message previews; tap to dismiss without opening the app" }
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
