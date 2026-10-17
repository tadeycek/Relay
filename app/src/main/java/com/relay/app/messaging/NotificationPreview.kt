package com.relay.app.messaging

import android.app.Notification
import android.os.Bundle
import androidx.core.app.NotificationCompat

/** Bounded notification history, including migration from the 1.6 custom preview layout. */
object NotificationPreview {
    private const val TEXTS = "com.relay.preview.texts"
    private const val TIMES = "com.relay.preview.times"

    fun history(notification: Notification?): List<PreviewLine> {
        if (notification == null) return emptyList()
        val texts = notification.extras.getStringArray(TEXTS)
        val times = notification.extras.getLongArray(TIMES)
        if (texts != null && times != null) return texts.zip(times.toTypedArray()).takeLast(5).map {
            PreviewLine(it.first.take(240), it.second)
        }
        // Preserve a visible pre-update MessagingStyle preview on the next message/profile refresh.
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.messages?.takeLast(5)?.map { PreviewLine(it.text?.toString().orEmpty().take(240), it.timestamp) }.orEmpty()
    }

    fun extras(lines: List<PreviewLine>) = Bundle().apply {
        putStringArray(TEXTS, lines.map { it.text }.toTypedArray())
        putLongArray(TIMES, lines.map { it.timestamp }.toLongArray())
    }

}
