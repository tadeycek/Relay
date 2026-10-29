package com.relay.app.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person

internal fun callNotificationName(displayName: String?, previewsHidden: Boolean): String =
    if (previewsHidden) "Relay call" else displayName?.takeIf { it.isNotBlank() } ?: "Relay call"

/**
 * CallStyle notifications for the ringing and in-call states (WP13 step 2). The ringtone itself is played
 * by [CallRingtone], so the channel is silent; the notification exists to be actionable, to raise a
 * full-screen intent when Android allows it, and to keep the foreground `phoneCall` service visible.
 */
object CallNotifier {

    const val CHANNEL_CALLS = "relay_calls"
    const val NOTIFICATION_ID = 7101

    fun incoming(context: Context, displayName: String?, callId: String): Notification {
        ensureChannel(context)
        val person = Person.Builder().setName(callNotificationName(displayName, CallController.previewsHidden(context))).setImportant(true).build()
        val decline = CallActionReceiver.intent(context, CallActionReceiver.ACTION_DECLINE)
        val answer = CallActionReceiver.intent(context, CallActionReceiver.ACTION_ACCEPT)
        val fullScreen = PendingIntent.getActivity(
            context, 1,
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(CallActivity.EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (canUseFullScreen(context)) builder.setFullScreenIntent(fullScreen, true)
        return builder.build()
    }

    fun ongoing(context: Context, displayName: String?, callId: String): Notification {
        ensureChannel(context)
        val person = Person.Builder().setName(callNotificationName(displayName, CallController.previewsHidden(context))).setImportant(true).build()
        val hangUp = CallActionReceiver.intent(context, CallActionReceiver.ACTION_HANGUP)
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(CallActivity.EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    /**
     * The group-call equivalent of [ongoing]. It opens the group chat (the group call UI lives there) and its
     * only action leaves the call; "end for everyone" stays in the in-call screen because it needs the
     * creator's confirmation.
     */
    fun groupOngoing(context: Context, displayName: String?, groupId: Long): Notification {
        ensureChannel(context)
        val person = Person.Builder().setName(callNotificationName(displayName, CallController.previewsHidden(context))).setImportant(true).build()
        val leave = CallActionReceiver.intent(context, CallActionReceiver.ACTION_GROUP_LEAVE)
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, com.relay.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("group_call_id", groupId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, leave))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /**
     * Android 14 lets the user switch full-screen intents off per app (and sideloaded installs do not
     * automatically get them), so this is a capability check, never an assumption (readiness gate G4).
     */
    fun canUseFullScreen(context: Context): Boolean = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34) manager?.canUseFullScreenIntent() == true else true
    } catch (e: Exception) {
        false
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_CALLS) != null) return
        val channel = NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming Relay voice calls"
            setSound(null, null) // the ringtone is played by CallRingtone, not the channel
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }
}

/** Answer/decline/hang-up from the notification, without opening the app's UI. */
class CallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ACCEPT -> CallController.accept(context)
            ACTION_DECLINE -> CallController.decline(context)
            ACTION_HANGUP -> CallController.hangUp(context)
            ACTION_GROUP_LEAVE -> com.relay.app.calls.group.GroupCallController.leave(context)
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.relay.app.calls.ACCEPT"
        const val ACTION_DECLINE = "com.relay.app.calls.DECLINE"
        const val ACTION_HANGUP = "com.relay.app.calls.HANGUP"
        const val ACTION_GROUP_LEAVE = "com.relay.app.calls.GROUP_LEAVE"

        fun intent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
