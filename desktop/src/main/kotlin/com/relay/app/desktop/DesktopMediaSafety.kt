package com.relay.app.desktop

import com.relay.app.transport.mailbox.MailboxMedia
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream

/** The five formats the `TYPE:MEDIA` wire allows ([com.relay.app.media.MediaBody]). */
enum class MediaKind(val mime: String, val isVideo: Boolean) {
    JPEG("image/jpeg", false), PNG("image/png", false), GIF("image/gif", false), WEBP("image/webp", false), MP4("video/mp4", true)
}

data class MediaDimensions(val width: Long, val height: Long)

/** What the UI may do with received bytes. Only [Image] is ever decoded; [Video] is never parsed. */
sealed interface MediaVerdict {
    data class Image(val kind: MediaKind, val width: Long, val height: Long) : MediaVerdict
    data class Video(val kind: MediaKind) : MediaVerdict
    data class Attachment(val reason: String) : MediaVerdict
}

class PreparedMedia(val bytes: ByteArray, val kind: MediaKind, val width: Long, val height: Long)

/**
 * Header-only classification of untrusted media. Nothing here decodes pixels of a received file: the
 * magic number and the declared MIME must agree, dimensions are read from the container header and
 * bounded, and a video's bytes are only sniffed (the `ftyp` box), never parsed or decoded in-process.
 */
object MediaSafety {
    /** Largest plaintext whose raw AES-GCM blob (+28) still fits the shared client's stored-size cap. */
    const val MAX_PLAIN_BYTES = MailboxMedia.MAX_STORED_BYTES - 28
    /** Inline preview bounds. Anything bigger stays a downloadable attachment. */
    const val MAX_RENDER_PIXELS = 16_000_000L
    const val MAX_RENDER_DIM = 8_192L
    /** A picked photo may be larger than the wire limit (it is recompressed); anything above this is not even read. */
    const val MAX_SOURCE_BYTES = 100L * 1024 * 1024
    /** Outgoing photos above the render bounds are sub-sampled during decode, so only a sanity cap applies. */
    const val MAX_COMPRESS_PIXELS = 150_000_000L
    /** The phone's rules (MediaCompressor): at most 900 KiB and 1280 px on the long side. */
    const val TARGET_BYTES = 900L * 1024
    const val TARGET_DIM = 1280

    private val MP4_BRANDS = setOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "dash", "M4V ", "M4A ")

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun be32(b: ByteArray, i: Int) = (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le32(b: ByteArray, i: Int) = (u8(b, i + 3).toLong() shl 24) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 1).toLong() shl 8) or u8(b, i).toLong()
    private fun ascii(b: ByteArray, i: Int, n: Int) = String(b, i, n, Charsets.ISO_8859_1)

    fun sniff(bytes: ByteArray): MediaKind? {
        if (bytes.size >= 3 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == 0xD8 && u8(bytes, 2) == 0xFF) return MediaKind.JPEG
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && ascii(bytes, 1, 3) == "PNG" && u8(bytes, 4) == 13 && u8(bytes, 5) == 10 && u8(bytes, 6) == 26 && u8(bytes, 7) == 10) return MediaKind.PNG
        if (bytes.size >= 10 && (ascii(bytes, 0, 6) == "GIF87a" || ascii(bytes, 0, 6) == "GIF89a")) return MediaKind.GIF
        if (bytes.size >= 20 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP") return MediaKind.WEBP
        if (bytes.size >= 16 && ascii(bytes, 4, 4) == "ftyp") {
            val boxSize = be32(bytes, 0)
            if (boxSize in 16..bytes.size.toLong() && ascii(bytes, 8, 4) in MP4_BRANDS) return MediaKind.MP4
        }
        return null
    }

    /** Header-only. Null when the header is truncated, inconsistent or declares a zero side. */
    fun dimensions(bytes: ByteArray, kind: MediaKind): MediaDimensions? = runCatching {
        val d = when (kind) {
            MediaKind.PNG -> if (bytes.size >= 24 && ascii(bytes, 12, 4) == "IHDR") MediaDimensions(be32(bytes, 16), be32(bytes, 20)) else null
            MediaKind.GIF -> if (bytes.size >= 10) MediaDimensions(le16(bytes, 6).toLong(), le16(bytes, 8).toLong()) else null
            MediaKind.JPEG -> jpegDimensions(bytes)
            MediaKind.WEBP -> webpDimensions(bytes)
            MediaKind.MP4 -> null
        }
        d?.takeIf { it.width > 0 && it.height > 0 }
    }.getOrNull()

    private fun jpegDimensions(b: ByteArray): MediaDimensions? {
        var i = 2
        var guard = 0
        while (i + 4 <= b.size && guard++ < 4096) {
            if (u8(b, i) != 0xFF) return null
            val marker = u8(b, i + 1)
            if (marker == 0xFF) { i++; continue } // fill byte
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            if (marker == 0xD9 || marker == 0xDA) return null // no frame header before data
            val len = be16(b, i + 2)
            if (len < 2) return null
            val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (sof) return if (i + 9 <= b.size) MediaDimensions(be16(b, i + 7).toLong(), be16(b, i + 5).toLong()) else null
            i += 2 + len
        }
        return null
    }

    private fun webpDimensions(b: ByteArray): MediaDimensions? {
        if (b.size < 25) return null
        // The RIFF size may not claim more bytes than we hold (truncated or lying container).
        if (le32(b, 4) + 8 > b.size.toLong()) return null
        return when (ascii(b, 12, 4)) {
            "VP8X" -> if (b.size < 30) null else MediaDimensions(1L + (u8(b, 24) or (u8(b, 25) shl 8) or (u8(b, 26) shl 16)), 1L + (u8(b, 27) or (u8(b, 28) shl 8) or (u8(b, 29) shl 16)))
            "VP8L" -> if (u8(b, 20) == 0x2f) {
                val bits = le32(b, 21)
                MediaDimensions(1L + (bits and 0x3FFF), 1L + ((bits shr 14) and 0x3FFF))
            } else null
            "VP8 " -> if (b.size >= 30 && u8(b, 23) == 0x9d && u8(b, 24) == 0x01 && u8(b, 25) == 0x2a) MediaDimensions((le16(b, 26) and 0x3FFF).toLong(), (le16(b, 28) and 0x3FFF).toLong()) else null
            else -> null
        }
    }

    private fun withinRenderBounds(d: MediaDimensions) =
        d.width <= MAX_RENDER_DIM && d.height <= MAX_RENDER_DIM && d.width * d.height <= MAX_RENDER_PIXELS

    /** Decides how received, already authenticated bytes may be shown. Never throws. */
    fun verdictForIncoming(declaredMime: String, plain: ByteArray): MediaVerdict {
        val kind = sniff(plain) ?: return MediaVerdict.Attachment("Unrecognised file type; not previewed")
        if (kind.mime != declaredMime.lowercase()) return MediaVerdict.Attachment("File contents do not match the declared type ($declaredMime); not previewed")
        if (kind.isVideo) return MediaVerdict.Video(kind)
        val d = dimensions(plain, kind) ?: return MediaVerdict.Attachment("Image header is damaged; not previewed")
        if (!withinRenderBounds(d)) return MediaVerdict.Attachment("Image dimensions ${d.width}×${d.height} exceed the preview limit; not previewed")
        return MediaVerdict.Image(kind, d.width, d.height)
    }

    /**
     * Validates a user-chosen file for sending, deriving the MIME from the magic number (never the file
     * name), and applies the phone's compression rules to photos. Throws [IllegalArgumentException] with a
     * user-readable reason; nothing is encrypted or sent before this returns.
     */
    fun prepareOutgoing(plain: ByteArray): PreparedMedia {
        require(plain.isNotEmpty()) { "The file is empty" }
        require(plain.size <= MAX_SOURCE_BYTES) { "The file is too large" }
        val kind = sniff(plain) ?: throw IllegalArgumentException("Only JPEG, PNG, GIF, WebP photos and MP4 videos can be sent")
        if (kind.isVideo) {
            require(plain.size <= MAX_PLAIN_BYTES) { "The video is larger than the 25 MiB limit; trim or compress it first" }
            return PreparedMedia(plain, kind, 0, 0)
        }
        val d = dimensions(plain, kind) ?: throw IllegalArgumentException("The image header is damaged")
        require(d.width * d.height <= MAX_COMPRESS_PIXELS && d.width <= 65_535 && d.height <= 65_535) { "The image dimensions are too large" }
        val compressible = kind == MediaKind.JPEG || kind == MediaKind.PNG
        val needsWork = compressible && (plain.size > TARGET_BYTES || maxOf(d.width, d.height) > TARGET_DIM || (kind == MediaKind.JPEG && exifOrientation(plain) in 2..8))
        if (!needsWork) {
            require(plain.size <= MAX_PLAIN_BYTES) { "The image is larger than the 25 MiB limit" }
            require(withinRenderBounds(d)) { "The image dimensions are too large to send as-is" }
            return PreparedMedia(plain, kind, d.width, d.height)
        }
        return recompress(plain, kind, d)
    }

    private fun exifOrientation(b: ByteArray): Int {
        var i = 2
        var guard = 0
        while (i + 4 <= b.size && guard++ < 64) {
            if (u8(b, i) != 0xFF) return 1
            val marker = u8(b, i + 1)
            if (marker == 0xDA || marker == 0xD9) return 1
            val len = be16(b, i + 2)
            if (marker == 0xE1 && len >= 16 && i + 4 + 6 <= b.size && ascii(b, i + 4, 4) == "Exif") {
                val t = i + 10
                if (t + 8 > b.size) return 1
                val little = ascii(b, t, 2) == "II"
                fun r16(o: Int) = if (little) le16(b, t + o) else be16(b, t + o)
                fun r32(o: Int) = if (little) le32(b, t + o) else be32(b, t + o)
                val ifd = r32(4).toInt()
                if (ifd < 8 || t + ifd + 2 > b.size) return 1
                val n = r16(ifd)
                for (k in 0 until minOf(n, 64)) {
                    val e = ifd + 2 + k * 12
                    if (t + e + 12 > b.size) return 1
                    if (r16(e) == 0x0112) return r16(e + 8)
                }
                return 1
            }
            if (len < 2) return 1
            i += 2 + len
        }
        return 1
    }

    private fun recompress(plain: ByteArray, kind: MediaKind, d: MediaDimensions): PreparedMedia {
        val readers = ImageIO.getImageReadersByMIMEType(kind.mime)
        require(readers.hasNext()) { "This image format cannot be recompressed here" }
        val reader = readers.next()
        try {
            reader.input = MemoryCacheImageInputStream(ByteArrayInputStream(plain))
            // Sub-sample while decoding so a 100-megapixel photo never allocates its full raster.
            val step = maxOf(1, (maxOf(d.width, d.height) / (TARGET_DIM * 2)).toInt())
            val param = reader.defaultReadParam.also { it.setSourceSubsampling(step, step, 0, 0) }
            val decoded = reader.read(0, param)
            val oriented = orient(decoded, if (kind == MediaKind.JPEG) exifOrientation(plain) else 1)
            var image = scaleDown(oriented, TARGET_DIM)
            var quality = 0.9f
            while (true) {
                val bytes = jpeg(image, quality)
                if (bytes.size <= TARGET_BYTES) return PreparedMedia(bytes, MediaKind.JPEG, image.width.toLong(), image.height.toLong())
                if (quality > 0.6f) quality -= 0.1f
                else {
                    require(image.width > 1 && image.height > 1) { "The image cannot be compressed under 900 KB" }
                    image = scaleDown(image, maxOf(1, (maxOf(image.width, image.height) * 0.8).toInt())); quality = 0.9f
                }
            }
        } catch (e: IllegalArgumentException) { throw e
        } catch (e: Exception) { throw IllegalArgumentException("The image could not be read") }
        catch (e: OutOfMemoryError) { throw IllegalArgumentException("The image is too large to process") }
        finally { reader.dispose() }
    }

    private fun orient(src: BufferedImage, orientation: Int): BufferedImage {
        if (orientation !in 2..8) return src
        val w = src.width; val h = src.height
        val swap = orientation in 5..8
        val t = AffineTransform()
        when (orientation) {
            2 -> { t.scale(-1.0, 1.0); t.translate(-w.toDouble(), 0.0) }
            3 -> { t.translate(w.toDouble(), h.toDouble()); t.rotate(Math.PI) }
            4 -> { t.scale(1.0, -1.0); t.translate(0.0, -h.toDouble()) }
            5 -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0) }
            6 -> { t.translate(h.toDouble(), 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.scale(-1.0, 1.0); t.translate(-h.toDouble(), 0.0); t.translate(0.0, w.toDouble()); t.rotate(3 * Math.PI / 2) }
            8 -> { t.translate(0.0, w.toDouble()); t.rotate(3 * Math.PI / 2) }
        }
        val out = BufferedImage(if (swap) h else w, if (swap) w else h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try { g.color = java.awt.Color.WHITE; g.fillRect(0, 0, out.width, out.height); g.drawImage(src, t, null) } finally { g.dispose() }
        return out
    }

    private fun scaleDown(src: BufferedImage, maxSide: Int): BufferedImage {
        val long = maxOf(src.width, src.height)
        if (long <= maxSide && src.type == BufferedImage.TYPE_INT_RGB) return src
        val scale = minOf(1.0, maxSide.toDouble() / long)
        val w = maxOf(1, Math.round(src.width * scale).toInt()); val h = maxOf(1, Math.round(src.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, h); g.drawImage(src, 0, 0, w, h, null)
        } finally { g.dispose() }
        return out
    }

    private fun jpeg(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        try {
            val param = writer.defaultWriteParam.also { it.compressionMode = ImageWriteParam.MODE_EXPLICIT; it.compressionQuality = quality }
            javax.imageio.stream.MemoryCacheImageOutputStream(out).use { writer.output = it; writer.write(null, IIOImage(img, null, null), param) }
        } finally { writer.dispose() }
        return out.toByteArray()
    }
}
