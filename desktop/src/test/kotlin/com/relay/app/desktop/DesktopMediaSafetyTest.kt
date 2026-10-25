package com.relay.app.desktop

import com.relay.app.transport.mailbox.MailboxMedia
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Random
import javax.imageio.ImageIO

/** WP08: bounded, header-only classification of untrusted media. No test here decodes pixels of a hostile file. */
class DesktopMediaSafetyTest {
    private fun encode(img: BufferedImage, fmt: String) = ByteArrayOutputStream().also { assertTrue(ImageIO.write(img, fmt, it)) }.toByteArray()
    private fun rgb(w: Int, h: Int, noise: Boolean = false): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { img ->
        val rnd = Random(7)
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, if (noise) rnd.nextInt() and 0xFFFFFF else (x * 5 + y * 3) and 0xFFFFFF)
    }
    private fun pngHeader(w: Long, h: Long): ByteArray = ByteBuffer.allocate(33).put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        .putInt(13).put("IHDR".toByteArray()).putInt(w.toInt()).putInt(h.toInt()).put(byteArrayOf(8, 2, 0, 0, 0)).putInt(0).array()
    private fun mp4(brand: String = "isom"): ByteArray = ByteBuffer.allocate(32).putInt(24).put("ftyp".toByteArray()).put(brand.toByteArray()).putInt(0).put("isomiso2".toByteArray()).array()
    private fun webp(chunk: String, body: ByteArray): ByteArray {
        val payload = chunk.toByteArray() + ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body
        return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(payload.size + 4).array() + "WEBP".toByteArray() + payload
    }

    @Test fun `magic numbers identify exactly the five wire formats`() {
        assertEquals(MediaKind.JPEG, MediaSafety.sniff(encode(rgb(4, 4), "jpg")))
        assertEquals(MediaKind.PNG, MediaSafety.sniff(encode(rgb(4, 4), "png")))
        assertEquals(MediaKind.GIF, MediaSafety.sniff(encode(rgb(4, 4), "gif")))
        assertEquals(MediaKind.WEBP, MediaSafety.sniff(webp("VP8X", ByteArray(10))))
        assertEquals(MediaKind.MP4, MediaSafety.sniff(mp4()))
        assertNull(MediaSafety.sniff("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()))
        assertNull(MediaSafety.sniff("MZ\u0090\u0000executable".toByteArray()))
        assertNull(MediaSafety.sniff(ByteArray(0)))
        assertNull(MediaSafety.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        // Other ISO-BMFF families (HEIC, QuickTime, 3GP) are not the wire's video/mp4.
        assertNull(MediaSafety.sniff(mp4("heic")))
        assertNull(MediaSafety.sniff(mp4("qt  ")))
    }

    @Test fun `dimensions come from headers without decoding pixels`() {
        assertEquals(MediaDimensions(37, 21), MediaSafety.dimensions(encode(rgb(37, 21), "jpg"), MediaKind.JPEG))
        assertEquals(MediaDimensions(37, 21), MediaSafety.dimensions(encode(rgb(37, 21), "png"), MediaKind.PNG))
        assertEquals(MediaDimensions(37, 21), MediaSafety.dimensions(encode(rgb(37, 21), "gif"), MediaKind.GIF))
        assertEquals(MediaDimensions(50_000, 60_000), MediaSafety.dimensions(pngHeader(50_000, 60_000), MediaKind.PNG))
        // WebP VP8X: 24-bit width-1, height-1 at offsets 4 and 7 of the chunk body.
        val vp8x = ByteArray(10).also { it[4] = 99.toByte(); it[7] = 49.toByte() }
        assertEquals(MediaDimensions(100, 50), MediaSafety.dimensions(webp("VP8X", vp8x), MediaKind.WEBP))
        val vp8l = ByteBuffer.allocate(5).order(java.nio.ByteOrder.LITTLE_ENDIAN).put(0x2f).putInt((199) or (149 shl 14)).array()
        assertEquals(MediaDimensions(200, 150), MediaSafety.dimensions(webp("VP8L", vp8l), MediaKind.WEBP))
        val vp8 = ByteArray(10).also { it[3] = 0x9d.toByte(); it[4] = 0x01; it[5] = 0x2a; it[6] = 0x40; it[7] = 0x01; it[8] = 0xF0.toByte(); it[9] = 0x00 }
        assertEquals(MediaDimensions(320, 240), MediaSafety.dimensions(webp("VP8 ", vp8), MediaKind.WEBP))
        assertNull(MediaSafety.dimensions(encode(rgb(37, 21), "jpg").copyOf(30), MediaKind.JPEG))
        assertNull(MediaSafety.dimensions(pngHeader(0, 10), MediaKind.PNG))
        assertNull(MediaSafety.dimensions(mp4(), MediaKind.MP4))
    }

    @Test fun `declared type must match the magic number or the file is only a downloadable attachment`() {
        val png = encode(rgb(20, 10), "png"); val jpg = encode(rgb(20, 10), "jpg")
        assertEquals(MediaVerdict.Image(MediaKind.PNG, 20, 10), MediaSafety.verdictForIncoming("image/png", png))
        assertEquals(MediaVerdict.Image(MediaKind.JPEG, 20, 10), MediaSafety.verdictForIncoming("image/jpeg", jpg))
        assertEquals(MediaVerdict.Video(MediaKind.MP4), MediaSafety.verdictForIncoming("video/mp4", mp4()))
        assertTrue(MediaSafety.verdictForIncoming("image/png", jpg) is MediaVerdict.Attachment)
        assertTrue(MediaSafety.verdictForIncoming("image/jpeg", png) is MediaVerdict.Attachment)
        assertTrue(MediaSafety.verdictForIncoming("video/mp4", jpg) is MediaVerdict.Attachment)
        assertTrue(MediaSafety.verdictForIncoming("image/jpeg", mp4()) is MediaVerdict.Attachment)
        assertTrue(MediaSafety.verdictForIncoming("image/png", "<html><script>alert(1)</script>".toByteArray()) is MediaVerdict.Attachment)
        assertTrue(MediaSafety.verdictForIncoming("image/png", ByteArray(0)) is MediaVerdict.Attachment)
    }

    @Test fun `image bomb headers are never rendered`() {
        val bomb = MediaSafety.verdictForIncoming("image/png", pngHeader(50_000, 50_000))
        assertTrue("$bomb", bomb is MediaVerdict.Attachment)
        assertTrue((bomb as MediaVerdict.Attachment).reason.contains("dimensions"))
        // A thin but absurdly long strip trips the per-side bound even when the pixel count is small.
        assertTrue(MediaSafety.verdictForIncoming("image/png", pngHeader(MediaSafety.MAX_RENDER_DIM + 1, 1)) is MediaVerdict.Attachment)
        assertTrue(MediaSafety.verdictForIncoming("image/png", pngHeader(4000, 4000)) is MediaVerdict.Image)
        assertTrue(MediaSafety.verdictForIncoming("image/png", pngHeader(4001, 4001)) is MediaVerdict.Attachment)
    }

    @Test fun `size bound leaves room for the 28 byte AES-GCM overhead under the client stored cap`() {
        assertEquals(MailboxMedia.MAX_STORED_BYTES - 28, MediaSafety.MAX_PLAIN_BYTES)
        assertTrue(MediaSafety.MAX_PLAIN_BYTES <= com.relay.app.media.MediaBody.MAX_MEDIA_BYTES)
    }

    @Test fun `outgoing rejects wrong magic oversize and bombs before any encryption`() {
        assertThrows(IllegalArgumentException::class.java) { MediaSafety.prepareOutgoing("hello world, not an image".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { MediaSafety.prepareOutgoing(ByteArray(0)) }
        val big = mp4().copyOf((MediaSafety.MAX_PLAIN_BYTES + 1).toInt())
        assertThrows(IllegalArgumentException::class.java) { MediaSafety.prepareOutgoing(big) }
        val justFits = mp4().copyOf(MediaSafety.MAX_PLAIN_BYTES.toInt())
        assertEquals(MediaKind.MP4, MediaSafety.prepareOutgoing(justFits).kind)
        // GIF/WebP are sent as-is, so a bomb cannot be shrunk first: refuse.
        val gifBomb = encode(rgb(2, 2), "gif").also { it[6] = 0xFF.toByte(); it[7] = 0xFF.toByte(); it[8] = 0xFF.toByte(); it[9] = 0xFF.toByte() }
        assertThrows(IllegalArgumentException::class.java) { MediaSafety.prepareOutgoing(gifBomb) }
        // A PNG whose header claims more than any sane photo is refused even though it would be recompressed.
        assertThrows(IllegalArgumentException::class.java) { MediaSafety.prepareOutgoing(pngHeader(40_000, 40_000)) }
        val video = MediaSafety.prepareOutgoing(mp4())
        assertEquals("video/mp4", video.kind.mime)
    }

    @Test fun `small images are sent unchanged and big ones follow the phone compression rules`() {
        val small = encode(rgb(200, 100), "jpg")
        val unchanged = MediaSafety.prepareOutgoing(small)
        assertArrayEquals(small, unchanged.bytes); assertEquals(MediaKind.JPEG, unchanged.kind)

        val bigPng = encode(rgb(3000, 2000, noise = true), "png")
        assertTrue(bigPng.size > MediaSafety.TARGET_BYTES)
        val out = MediaSafety.prepareOutgoing(bigPng)
        assertEquals(MediaKind.JPEG, out.kind)
        assertTrue("compressed ${out.bytes.size}", out.bytes.size <= MediaSafety.TARGET_BYTES)
        assertTrue(maxOf(out.width, out.height) <= MediaSafety.TARGET_DIM)
        val decoded = ImageIO.read(out.bytes.inputStream())
        assertEquals(out.width.toInt(), decoded.width); assertEquals(out.height.toInt(), decoded.height)
        assertEquals(3, Math.round(decoded.width.toDouble() / decoded.height * 2).toInt()) // aspect 3:2 preserved
    }

    @Test fun `exif orientation is applied when a photo is recompressed`() {
        // A 3000x1000 JPEG tagged "rotate 90 clockwise" must come out portrait, like the phone does.
        val jpeg = encode(rgb(3000, 1000, noise = true), "jpg")
        val exif = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 0x22) + "Exif\u0000\u0000".toByteArray() +
            byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0)
        val tagged = jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)
        val out = MediaSafety.prepareOutgoing(tagged)
        assertTrue("${out.width}x${out.height}", out.height > out.width)
    }
}
