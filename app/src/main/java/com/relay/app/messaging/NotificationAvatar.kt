package com.relay.app.messaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.relay.app.data.model.Contact
import com.relay.app.ui.glyph.Corner
import com.relay.app.ui.glyph.GlyphGenerator
import com.relay.app.ui.glyph.GlyphSpec

/** Unbadged sender photo. The native conversation template adds the registered application badge. */
object NotificationAvatar {
    fun forContact(contact: Contact, photo: Bitmap? = null): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(RectF(2f, 2f, 190f, 190f), 40f, 40f, Path.Direction.CW) })
        if (photo != null) {
            val scale = maxOf(192f / photo.width, 192f / photo.height)
            val width = photo.width * scale
            val height = photo.height * scale
            canvas.drawBitmap(photo, null, RectF((192 - width) / 2, (192 - height) / 2,
                (192 + width) / 2, (192 + height) / 2), paint)
        } else {
            canvas.drawColor(0xFFE4E7E8.toInt())
            val spec = GlyphGenerator.fromKey(GlyphGenerator.seedFor(contact.nostrPubkey,
                contact.phone.takeIf { contact.hasPhone }, contact.name))
            paint.color = if (contact.qrVerified) 0xFF287D6E.toInt() else 0xFF68747A.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 9.6f
            paint.strokeCap = Paint.Cap.ROUND
            for (row in 0 until GlyphSpec.GRID) for (col in 0 until GlyphSpec.GRID) {
                val x = 32f + col * 32f
                val y = 32f + row * 32f
                for (corner in GlyphGenerator.cornersFor(spec.variantAt(row, col))) {
                    val (cx, cy, angle) = when (corner) {
                        Corner.TOP_LEFT -> Triple(x, y, 0f)
                        Corner.TOP_RIGHT -> Triple(x + 32f, y, 90f)
                        Corner.BOTTOM_RIGHT -> Triple(x + 32f, y + 32f, 180f)
                        Corner.BOTTOM_LEFT -> Triple(x, y + 32f, 270f)
                    }
                    canvas.drawArc(cx - 16, cy - 16, cx + 16, cy + 16, angle, 90f, false, paint)
                }
            }
        }
        canvas.restore()
        return bitmap
    }
}
