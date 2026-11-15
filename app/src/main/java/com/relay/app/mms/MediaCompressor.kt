package com.relay.app.mms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return Result.Error(IllegalStateException("Cannot decode image"))

        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (oriented !== decoded) decoded.recycle()

        val scale = minOf(1f, maxDim.toFloat() / maxOf(oriented.width, oriented.height))
        var bitmap = if (scale < 1f) Bitmap.createScaledBitmap(oriented,
            (oriented.width * scale).toInt().coerceAtLeast(1), (oriented.height * scale).toInt().coerceAtLeast(1), true) else oriented
        if (bitmap !== oriented) oriented.recycle()
        var quality = 90
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            if (bytes.size > maxBytes) {
                if (quality > 60) quality -= 10
                else {
                    if (bitmap.width <= 1 || bitmap.height <= 1) break
                    val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.8f).toInt().coerceAtLeast(1),
                        (bitmap.height * 0.8f).toInt().coerceAtLeast(1), true)
                    bitmap.recycle(); bitmap = smaller; quality = 90
                }
            }
        } while (bytes.size > maxBytes)

        bitmap.recycle()

        if (bytes.size > maxBytes) {
            return Result.TooLarge("Image is too large to compress under ${maxBytes / 1024} KB.")
        }

        val outFile = File.createTempFile("img_", ".jpg", outDir)
        FileOutputStream(outFile).use { it.write(bytes) }
        Result.Success(outFile, "image/jpeg")
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    fun prepareVideo(
        context: Context,
        uri: Uri,
        maxBytes: Long = com.relay.app.media.MediaBody.MAX_MEDIA_BYTES,
        maxDurationMs: Long = Long.MAX_VALUE,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Result {
        var outFile: File? = null
        return try {
            require(maxBytes in 1..com.relay.app.media.MediaBody.MAX_MEDIA_BYTES)
            val mime = context.contentResolver.getType(uri)?.lowercase() ?: "video/mp4"
            val extension = when (mime) {
                "video/mp4" -> "mp4"
                "video/webm" -> "webm"
                "video/quicktime" -> "mov"
                "video/3gpp" -> "3gp"
                else -> return Result.TooLarge("Choose an MP4, WebM, MOV or 3GP video")
            }
            val size = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                } ?: -1L
            }.getOrDefault(-1L)
            if (size > maxBytes) return Result.TooLarge("Video must be 3 GiB or smaller")
            progress(0, size.coerceAtLeast(0))
            val durationMs = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { retriever.release() }
            }.getOrDefault(0L)
            if (durationMs > maxDurationMs) return Result.TooLarge("Video must be ${maxDurationMs / 1000} seconds or shorter")
            val outDir = File(context.cacheDir, "mms").also { it.mkdirs() }
            if (size > 0 && outDir.usableSpace < size + 1024 * 1024) return Result.TooLarge("Not enough free storage to prepare this video")
            val target = File.createTempFile("vid_", ".$extension", outDir)
            outFile = target
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Couldn't open video" }.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        copied += n
                        require(copied <= maxBytes) { "Video must be 3 GiB or smaller" }
                        output.write(buffer, 0, n)
                        progress(copied,size.coerceAtLeast(0))
                    }
                    require(copied > 0) { "Video is empty" }
                    progress(copied,copied)
                }
            }
            Result.Success(target, mime)
        } catch (e: Exception) {
            outFile?.delete()
            Result.Error(e)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var size = 1
        while ((width / (size * 2)) >= maxDim || (height / (size * 2)) >= maxDim) size *= 2
        return size
    }
}
