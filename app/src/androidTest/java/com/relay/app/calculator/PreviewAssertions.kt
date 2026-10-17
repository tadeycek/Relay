package com.relay.app.calculator

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.R

/** Inspect the actual custom content, not a native avatar which OEMs may mask differently. */
object PreviewAssertions {
    data class Content(val name: String, val text: String, val avatar: Bitmap)
    fun content(context: Context, notification: Notification): Content {
        var result: Content? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = requireNotNull(notification.contentView).apply(context, null)
            val drawable = view.findViewById<ImageView>(R.id.preview_avatar).drawable as BitmapDrawable
            result = Content(view.findViewById<TextView>(R.id.preview_sender).text.toString(),
                view.findViewById<TextView>(R.id.preview_text).text.toString(), drawable.bitmap)
        }
        return requireNotNull(result)
    }
}
