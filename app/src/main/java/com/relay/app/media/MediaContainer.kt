package com.relay.app.media

import java.io.ByteArrayOutputStream

/**
 * Wraps an already-encrypted media blob inside a structurally valid JPEG so a media server's file-type
 * check sees a real image, instead of the "application/octet-stream" ciphertext it otherwise correctly
 * refuses (see BlossomClient). The encrypted bytes go into JPEG comment (COM) segments, which the format
 * defines as opaque — any real decoder skips over them — so nothing about the wrapper needs to know or
 * care what the ciphertext actually is. There is no real image inside: a decoder trying to *render* this
 * file finds no frame data and fails, and Relay's own receiver never tries — it unwraps and decrypts.
 *
 * This changes nothing about privacy: the payload is exactly the same ciphertext [MediaCrypto.encrypt]
 * already produced, byte for byte. It only changes what bytes sit around it on the wire.
 */
object MediaContainer {

    /** Every upload uses this Content-Type, whatever the original media really was (see class doc). */
    const val UPLOAD_CONTENT_TYPE = "image/jpeg"

    private const val MARKER = 0xFF
    private const val APP0 = 0xE0
    private const val COM = 0xFE
    private const val EOI = 0xD9

    /** Largest a single segment's data can be: length is a 16-bit field that includes its own 2 bytes. */
    private const val MAX_SEGMENT_DATA = 0xFFFF - 2

    // Minimal JFIF header: identifier, version 1.1, no density/thumbnail.
    private val JFIF_APP0_PAYLOAD = byteArrayOf(
        'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
        1, 1,
        0,
        0, 1, 0, 1,
        0, 0,
    )

    private fun comSegmentCount(payloadSize: Int): Int =
        if (payloadSize == 0) 1 else (payloadSize + MAX_SEGMENT_DATA - 1) / MAX_SEGMENT_DATA

    /** The exact size [wrap] produces for a payload of [payloadSize] bytes, without building it. */
    fun wrappedSize(payloadSize: Int): Int =
        2 + (4 + JFIF_APP0_PAYLOAD.size) + comSegmentCount(payloadSize) * 4 + payloadSize + 2

    fun wrap(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(wrappedSize(payload.size))
        out.write(MARKER); out.write(0xD8) // SOI
        writeSegment(out, APP0, JFIF_APP0_PAYLOAD, 0, JFIF_APP0_PAYLOAD.size)
        var offset = 0
        if (payload.isEmpty()) {
            writeSegment(out, COM, payload, 0, 0)
        }
        while (offset < payload.size) {
            val chunk = minOf(MAX_SEGMENT_DATA, payload.size - offset)
            writeSegment(out, COM, payload, offset, chunk)
            offset += chunk
        }
        out.write(MARKER); out.write(EOI)
        return out.toByteArray()
    }

    /**
     * The inverse of [wrap]. Deliberately strict — anything that is not exactly this function's own
     * output (truncated, corrupted, or simply not produced by [wrap]) returns null rather than a guess.
     */
    fun unwrap(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != MARKER || (bytes[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2

        if (i + 4 > bytes.size || (bytes[i].toInt() and 0xFF) != MARKER || (bytes[i + 1].toInt() and 0xFF) != APP0) return null
        val app0Len = readUInt16(bytes, i + 2)
        if (app0Len < 2) return null
        i += 4 + (app0Len - 2)
        if (i > bytes.size) return null

        val payload = ByteArrayOutputStream()
        var sawCom = false
        while (true) {
            if (i + 2 > bytes.size || (bytes[i].toInt() and 0xFF) != MARKER) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == EOI) {
                i += 2
                break
            }
            if (marker != COM) return null
            if (i + 4 > bytes.size) return null
            val len = readUInt16(bytes, i + 2)
            if (len < 2) return null
            val dataLen = len - 2
            val dataStart = i + 4
            if (dataStart + dataLen > bytes.size) return null
            payload.write(bytes, dataStart, dataLen)
            sawCom = true
            i = dataStart + dataLen
        }
        if (!sawCom || i != bytes.size) return null
        return payload.toByteArray()
    }

    private fun readUInt16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun writeSegment(out: ByteArrayOutputStream, marker: Int, data: ByteArray, offset: Int, length: Int) {
        out.write(MARKER)
        out.write(marker)
        val len = length + 2
        out.write(len ushr 8)
        out.write(len and 0xFF)
        out.write(data, offset, length)
    }
}
