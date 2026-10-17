package com.relay.app.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.relay.app.mms.MediaCompressor
import java.io.File

/** Local GIFs/stickers retain their original animation, within conservative byte/pixel bounds. */
object GifPreparation {
    fun image(context: Context, uri: Uri, mime: String): MediaCompressor.Result {
        if (mime != "image/gif") return MediaCompressor.compressImage(context, uri, 3L*1024*1024,2048)
        var file: File? = null
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 3*1024*1024) {
                    val read = input.read(buffer,0,minOf(buffer.size,3*1024*1024+1-output.size()))
                    if(read<0) break
                    output.write(buffer,0,read)
                }
                output.toByteArray()
            } ?: error("Couldn't read GIF")
            require(bytes.size <= 3*1024*1024 && bytes.size >= 6 && String(bytes,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a")) { "Choose a GIF smaller than 3 MiB" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) { "GIF dimensions must be at most 2048 px" }
            file = File.createTempFile("sticker_", ".gif", context.cacheDir)
            file.writeBytes(bytes)
            MediaCompressor.Result.Success(file,"image/gif")
        } catch(e:Exception) { file?.delete(); MediaCompressor.Result.Error(e) }
    }
}
