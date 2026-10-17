package com.relay.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Bounded local decode; imported payload goes through the exact camera pairing validation. */
object QrImages {
    fun decodePixels(width: Int, height: Int, pixels: IntArray): String? = runCatching {
        require(width in 1..2048 && height in 1..2048 && pixels.size == width * height)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width,height,pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true)).text
    }.getOrNull()
    fun decode(context: Context, uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            decodePixels(bitmap.width, bitmap.height, pixels)
        } finally { bitmap.recycle() }
    }.getOrNull()
}
