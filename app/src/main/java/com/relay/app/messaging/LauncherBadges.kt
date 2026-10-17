package com.relay.app.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object LauncherBadges {
    const val MESSAGES = "relay_messages_no_badge"
    const val LOCATION = "relay_location_requests_no_badge"
    const val SECURITY = "relay_security_alerts_no_badge"
    @Synchronized fun disable(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mapOf("relay_messages" to MESSAGES, "relay_location_requests" to LOCATION, "relay_security_alerts" to SECURITY).forEach { (oldId,newId) ->
            val old = manager.getNotificationChannel(oldId) ?: return@forEach
            if (manager.getNotificationChannel(newId) == null) manager.createNotificationChannel(
                NotificationChannel(newId,old.name,old.importance).apply {
                    description = old.description; group = old.group; lockscreenVisibility = old.lockscreenVisibility
                    setBypassDnd(old.canBypassDnd()); setSound(old.sound,old.audioAttributes); enableLights(old.shouldShowLights()); lightColor = old.lightColor
                    vibrationPattern = old.vibrationPattern; enableVibration(old.shouldVibrate()); setShowBadge(false)
                })
            manager.activeNotifications.filter { it.notification.channelId == oldId }.forEach { active ->
                val updated = Notification.Builder.recoverBuilder(context,active.notification)
                    .setChannelId(newId).setNumber(0).setBadgeIconType(Notification.BADGE_ICON_NONE).setOnlyAlertOnce(true).build()
                manager.notify(active.tag,active.id,updated)
            }
        }
    }
}
