package com.relay.app.transport.mailbox

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Encrypted photos and videos on Relay's own server (`/v1/media/<sha256>`, one storage object per
 * file). The app encrypts before uploading, so the server only holds bytes it can't read. Only the
 * recipients named at upload can download a file, and it is deleted once each has confirmed it has
 * it (or after eight days). Blocking; call off the main thread.
 */
object MailboxMedia {

    private const val PATH = "/v1/media/"
    private val HASH = Regex("^[0-9a-f]{64}$")
    private val BINARY = "application/octet-stream".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun urlFor(sha256Hex: String) = MailboxConfig.url + PATH + sha256Hex

    /** Whether [url] is a file on this build's server. Anything else is never fetched. */
    fun isOurs(url: String): Boolean = MailboxConfig.available && url.startsWith(MailboxConfig.url + PATH) &&
        HASH.matches(url.removePrefix(MailboxConfig.url + PATH))

    /** Stores [blob] (its SHA-256 is [sha256Hex]) for [recipients] and returns the reference URL. */
    fun upload(signer: NostrSigner, blob: ByteArray, sha256Hex: String, recipients: List<String>): String {
        require(recipients.isNotEmpty()) { "Nobody to send the file to" }
        val url = urlFor(sha256Hex)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(signer, "PUT", url, blob))
            .header("x-relay-recipients", recipients.distinct().joinToString(","))
            .put(blob.toRequestBody(BINARY))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Upload failed (${response.code})" }
        }
        return url
    }

    /** The file's bytes if this install may have them and they match [sha256Hex]; null otherwise. */
    fun download(signer: NostrSigner, url: String, sha256Hex: String, maxBytes: Long): ByteArray? {
        if (!isOurs(url)) return null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(signer, "GET", url))
            .build()
        return http.newCall(request).execute().use { response ->
            val body = response.body ?: return null
            if (!response.isSuccessful || body.contentLength() > maxBytes) return null
            val bytes = body.byteStream().use { it.readNBytesCompat(maxBytes + 1) }
            if (bytes.size > maxBytes) return null
            bytes.takeIf { hex(MessageDigest.getInstance("SHA-256").digest(it)) == sha256Hex }
        }
    }

    /**
     * Tells the server this phone has stored the file. Best effort: if it doesn't get through, the
     * server still deletes the file when its lifetime ends.
     */
    fun confirm(signer: NostrSigner, url: String) {
        if (!isOurs(url)) return
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", MailboxAuth.header(signer, "DELETE", url))
                .delete()
                .build()
            http.newCall(request).execute().close()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), limit - out.size()).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
