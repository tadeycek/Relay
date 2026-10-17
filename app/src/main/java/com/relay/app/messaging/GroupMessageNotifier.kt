package com.relay.app.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.relay.app.R
import com.relay.app.groups.GroupProtocol
import com.relay.app.util.RelayPreferences

object GroupMessageNotifier {
    private const val ID = 6000
    fun cancel(context: Context, groupId: Long) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel("group:$groupId", ID)
    }
    fun show(context: Context, groupId: Long, packet: GroupProtocol.Packet) {
        if (ActiveChat.groupId == groupId || !RelayPreferences(context).incomingMessageNotification || !MessageNotifier.canPostNotifications(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(MessageNotifier.CHANNEL_MESSAGES) == null)
            manager.createNotificationChannel(NotificationChannel(MessageNotifier.CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        val person = Person.Builder().setKey(packet.author).setName(packet.state.members.first { it.key == packet.author }.name).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setGroupConversation(true).setConversationTitle(packet.state.name)
        manager.activeNotifications.firstOrNull { it.tag == "group:$groupId" && it.id == ID }?.notification?.let {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)?.messages?.takeLast(4)?.forEach { old -> style.addMessage(old) }
        }
        val text = com.relay.app.media.MediaBody.parse(packet.text)?.let { (if (it.isVideo) "Video" else "Photo") + ReplyBody.preview(it.caption).let { c -> if (c.isBlank()) "" else ": $c" } } ?: ReplyBody.preview(packet.text).replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").take(240)
        style.addMessage(text, System.currentTimeMillis(), person)
        val dismiss = PendingIntent.getBroadcast(context, 0, Intent(context, NotificationDismissReceiver::class.java)
            .setAction(NotificationDismissReceiver.ACTION_DISMISS).setData(Uri.parse("relay-preview://dismiss-group/$groupId"))
            .putExtra(NotificationDismissReceiver.EXTRA_GROUP_ID, groupId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify("group:$groupId", ID, NotificationCompat.Builder(context, MessageNotifier.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message_notification).setColor(0xFF7D519E.toInt())
            .setContentTitle(packet.state.name).setContentText(text).setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(dismiss).setDeleteIntent(dismiss).setAllowSystemGeneratedContextualActions(false).build())
    }
}
