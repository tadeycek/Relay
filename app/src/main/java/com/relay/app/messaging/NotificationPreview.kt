package com.relay.app.messaging

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.relay.app.R

/** App-rendered avatar avoids OEM-specific circular conversation masks. Header remains system-owned. */
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

    fun view(context: Context, sender: String, avatar: Bitmap, lines: List<PreviewLine>, expanded: Boolean) =
        RemoteViews(context.packageName, R.layout.notification_conversation).apply {
            setImageViewBitmap(R.id.preview_avatar, avatar)
            setTextViewText(R.id.preview_sender, sender)
            setTextViewText(R.id.preview_text, if (expanded) lines.joinToString("\n") { it.text } else lines.lastOrNull()?.text.orEmpty())
            setInt(R.id.preview_text, "setMaxLines", if (expanded) 5 else 1)
        }
}
