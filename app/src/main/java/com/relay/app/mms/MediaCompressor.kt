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

    fun compressImage(context: Context, uri: Uri, maxBytes: Long = MAX_BYTES, maxDim: Int = MAX_DIM): Result {
        return try {
        val outDir = File(context.cacheDir, "mms").also { it.mkdirs() }
        val outFile = File(outDir, "img_${System.currentTimeMillis()}.jpg")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
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
        } while (bytes.size > maxBytes && quality > 20)

        bitmap.recycle()

        if (bytes.size > maxBytes) {
            return Result.TooLarge("Image is too large to compress under ${maxBytes / 1024} KB.")
        }

        FileOutputStream(outFile).use { it.write(bytes) }
        Result.Success(outFile, "image/jpeg")
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    fun prepareVideo(
        context: Context,
        uri: Uri,
        maxBytes: Long = MAX_BYTES,
        maxDurationMs: Long = MAX_VIDEO_DURATION_MS,
    ): Result {
        return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        retriever.release()

        if (durationMs > maxDurationMs) {
            return Result.TooLarge("Video must be ${maxDurationMs / 1000} seconds or shorter.")
        }

        val outDir = File(context.cacheDir, "mms").also { it.mkdirs() }
        val outFile = File(outDir, "vid_${System.currentTimeMillis()}.mp4")

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { input.copyTo(it) }
        }

        if (outFile.length() > maxBytes) {
            outFile.delete()
            return Result.TooLarge("Video exceeds ${maxBytes / 1024} KB. Please trim the clip and try again.")
        }

        Result.Success(outFile, "video/mp4")
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var size = 1
        while ((width / size) > maxDim || (height / size) > maxDim) size *= 2
        return size
    }
}
