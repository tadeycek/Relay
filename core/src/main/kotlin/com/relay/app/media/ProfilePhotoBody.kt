package com.relay.app.media

import java.util.Base64

/** Tiny JPEGs travel inside existing encrypted messages; no public avatar URL or extra fetch. */
object ProfilePhotoBody {
    const val MAX_BYTES = 8 * 1024
    const val MAX_DIM = 256
    const val REQUEST = "TYPE:PROFILE_PHOTO_REQUEST|V:1"
    private const val PREFIX = "TYPE:PROFILE_PHOTO|"
    data class Update(val revision: Long, val jpeg: ByteArray?)

    fun isControl(body: String) = body.startsWith(PREFIX) || body.startsWith("TYPE:PROFILE_PHOTO_REQUEST")
    fun format(update: Update): String {
        require(update.revision >= 0)
        require(update.jpeg == null || update.jpeg.size in 1..MAX_BYTES)
        return "${PREFIX}REV:${update.revision}|JPEG:${update.jpeg?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()}"
    }

    fun parse(body: String): Update? = runCatching {
        require(body.length <= ((MAX_BYTES + 2) / 3 * 4 + 128) && body.startsWith(PREFIX))
        val parts = body.removePrefix(PREFIX).split('|')
        require(parts.size == 2 && parts[0].startsWith("REV:") && parts[1].startsWith("JPEG:"))
        val revision = parts[0].removePrefix("REV:").toLong()
        require(revision >= 0)
        val encoded = parts[1].removePrefix("JPEG:")
        val jpeg = if (encoded.isEmpty()) null else Base64.getDecoder().decode(encoded).also {
            require(it.size in 3..MAX_BYTES && it[0] == 0xff.toByte() && it[1] == 0xd8.toByte() && it[2] == 0xff.toByte())
        }
        Update(revision, jpeg)
    }.getOrNull()
}
