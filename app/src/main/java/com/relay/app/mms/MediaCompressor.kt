package com.relay.app.mms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object MediaCompressor {

    private const val MAX_BYTES = 900 * 1024L
    private const val MAX_DIM = 1280
    private const val MAX_VIDEO_DURATION_MS = 30_000L

    sealed class Result {
        data class Success(val file: File, val mimeType: String) : Result()
        data class TooLarge(val message: String) : Result()
        data class Error(val cause: Throwable) : Result()
    }

    fun compressImage(context: Context, uri: Uri): Result {
        return try {
        val outDir = File(context.cacheDir, "mms").also { it.mkdirs() }
        val outFile = File(outDir, "img_${System.currentTimeMillis()}.jpg")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return Result.Error(IllegalStateException("Cannot decode image"))

        var quality = 85
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            quality -= 10
        } while (bytes.size > MAX_BYTES && quality > 20)

        bitmap.recycle()

        if (bytes.size > MAX_BYTES) {
            return Result.TooLarge("Image is too large to compress under 900 KB.")
        }

        FileOutputStream(outFile).use { it.write(bytes) }
        Result.Success(outFile, "image/jpeg")
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    fun prepareVideo(context: Context, uri: Uri): Result {
        return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        retriever.release()

        if (durationMs > MAX_VIDEO_DURATION_MS) {
            return Result.TooLarge("Video must be 30 seconds or shorter to send via MMS.")
        }

        val outDir = File(context.cacheDir, "mms").also { it.mkdirs() }
        val outFile = File(outDir, "vid_${System.currentTimeMillis()}.mp4")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { input.copyTo(it) }
        }

        if (outFile.length() > MAX_BYTES) {
            outFile.delete()
            return Result.TooLarge("Video exceeds 900 KB. Please trim the clip and try again.")
        }

        Result.Success(outFile, "video/mp4")
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var size = 1
        while ((width / size) > MAX_DIM || (height / size) > MAX_DIM) size *= 2
        return size
    }
}
