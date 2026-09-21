package com.relay.app.media

import java.util.Base64

/**
 * Reference to an encrypted file stored on a Blossom server, carried in a message body as
 * `TYPE:MEDIA|URL:...|SHA:...|KEY:...|MIME:...|SIZE:...|CAP:...`.
 *
 * Images and video are far too big for a Nostr event, so the file is encrypted with a fresh random
 * key, the *ciphertext* is uploaded to a media server, and only this small reference travels in the
 * (already end-to-end encrypted) message. The server therefore never sees the plaintext or the key.
 * [sha256Hex] is the hash of the ciphertext blob, which is also its address on the server and lets
 * the receiver detect a tampered or swapped download before decrypting.
 */
data class MediaRef(
    val url: String,
    val sha256Hex: String,
    /** Base64 of the 32-byte AES-256-GCM key. */
    val keyBase64: String,
    val mime: String,
    /** Plaintext size in bytes (for display / sanity limits only). */
    val size: Long,
    val caption: String = "",
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

object MediaBody {

    const val MAX_URL_LENGTH = 300
    const val MAX_CAPTION_LENGTH = 200
    /** Hard cap on the plaintext size a receiver will accept (the blob is a few bytes larger). */
    const val MAX_MEDIA_BYTES = 25L * 1024 * 1024

    private const val PREFIX = "TYPE:MEDIA|"
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val ALLOWED_MIME = setOf("image/jpeg", "image/png", "image/gif", "image/webp", "video/mp4")

    fun isMedia(body: String): Boolean = body.trim().startsWith(PREFIX, ignoreCase = true)

    fun format(ref: MediaRef): String {
        val caption = sanitizeCaption(ref.caption)
        val sb = StringBuilder(PREFIX)
        sb.append("URL:").append(ref.url)
        sb.append("|SHA:").append(ref.sha256Hex)
        sb.append("|KEY:").append(ref.keyBase64)
        sb.append("|MIME:").append(ref.mime)
        sb.append("|SIZE:").append(ref.size)
        if (caption.isNotEmpty()) sb.append("|CAP:").append(caption)
        return sb.toString()
    }

    /** Returns null unless every field is present and passes validation. */
    fun parse(body: String): MediaRef? {
        val trimmed = body.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        for (part in trimmed.substring(PREFIX.length).split('|')) {
            val i = part.indexOf(':')
            if (i <= 0) return null
            fields[part.substring(0, i).uppercase()] = part.substring(i + 1)
        }

        val url = fields["URL"] ?: return null
        if (!isAcceptableUrl(url)) return null
        val sha = fields["SHA"]?.lowercase() ?: return null
        if (!HEX64.matches(sha)) return null
        val key = fields["KEY"] ?: return null
        val keyBytes = try { Base64.getDecoder().decode(key) } catch (e: IllegalArgumentException) { return null }
        if (keyBytes.size != 32) return null
        val mime = fields["MIME"]?.lowercase() ?: return null
        if (mime !in ALLOWED_MIME) return null
        val size = fields["SIZE"]?.toLongOrNull() ?: return null
        if (size < 1 || size > MAX_MEDIA_BYTES) return null

        return MediaRef(
            url = url,
            sha256Hex = sha,
            keyBase64 = key,
            mime = mime,
            size = size,
            caption = sanitizeCaption(fields["CAP"] ?: ""),
        )
    }

    /**
     * Only `https://` URLs with a host are followed. A hostile sender chooses this URL, so anything
     * else (plain http, file:, content:, javascript:, embedded credentials) is refused rather than
     * fetched; the fetch also never follows redirects to a different scheme (see BlossomClient).
     */
    fun isAcceptableUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH || url.any { it.isWhitespace() || it == '|' || it.isISOControl() }) return false
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val rest = url.substring("https://".length)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.contains('@')) return false
        return true
    }

    fun sanitizeCaption(caption: String): String =
        caption.replace(Regex("""[\p{Cc}\p{Cf}]"""), "").replace("|", "").trim().take(MAX_CAPTION_LENGTH)
}
