package com.relay.app.messaging

import android.content.Context

/** The same lock covers the final post and mute/cancel, preventing an in-flight post after mute. */
object ConversationNotifications {
    private fun prefs(context: Context) = context.getSharedPreferences("conversation_notifications", Context.MODE_PRIVATE)
    fun key(id: Long, group: Boolean) = if (group) "group:$id" else "contact:$id"
    fun muted(context: Context, id: Long, group: Boolean = false) = prefs(context).getBoolean(key(id, group), false)
    @Synchronized fun setMuted(context: Context, id: Long, group: Boolean, value: Boolean) {
        val epoch = prefs(context).getLong("epoch:${key(id, group)}", 0)
        check(prefs(context).edit().putBoolean(key(id, group), value)
            .putLong("epoch:${key(id, group)}", Math.addExact(epoch, 1)).commit())
        if (value) {
            if (group) GroupMessageNotifier.cancel(context, id) else {
                MessageNotifier.cancel(context, id)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .cancel(3000 + (id % 1000).toInt())
            }
        }
    }
    @Synchronized fun token(context: Context, id: Long, group: Boolean = false): Long? =
        if (muted(context, id, group)) null else prefs(context).getLong("epoch:${key(id, group)}", 0)
    @Synchronized fun postIfAllowed(context: Context, id: Long, group: Boolean = false, token: Long? = token(context, id, group), post: () -> Unit) {
        if (token != null && token == token(context, id, group)) post()
    }
}
