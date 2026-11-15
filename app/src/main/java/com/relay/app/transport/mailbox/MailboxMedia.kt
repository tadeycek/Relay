package com.relay.app.transport.mailbox

import android.content.Context
import com.relay.app.transport.nostr.NostrIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.RandomAccessFile
import com.relay.app.media.MediaCrypto
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Encrypted photos and videos on Relay's own server (`/v1/media/<sha256>`, one storage object per
 * file). The app encrypts before uploading, so the server only holds bytes it can't read. Only the
 * recipients named at upload can download a file, and it is deleted once each has confirmed it has
 * it (or after eight days). Blocking; call off the main thread.
 */
object MailboxMedia {

    private const val PATH = "/v1/media/"
    private const val CHUNK_PATH = "/v2/media/"
    private val HASH = Regex("^[0-9a-f]{64}$")
    private val BINARY = "application/octet-stream".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun urlFor(sha256Hex: String) = MailboxConfig.url + PATH + sha256Hex

    /** Whether [url] is a file on this build's server. Anything else is never fetched. */
    fun isOurs(url: String): Boolean = MailboxConfig.available && listOf(PATH, CHUNK_PATH).any { path ->
        val prefix = MailboxConfig.url + path
        url.startsWith(prefix) && HASH.matches(url.removePrefix(prefix))
    }

    fun isChunked(url: String) = isOurs(url) && url.startsWith(MailboxConfig.url + CHUNK_PATH)

    class ServerUpdateRequired : IllegalStateException("Update Relay's delivery server before sending large media")

    /** The prepared ciphertext is immutable across retries; the server acknowledges a durable prefix. */
    fun uploadChunked(context: Context, file: File, sha256Hex: String, recipients: List<String>,
                      progress: (Long, Long) -> Unit = { _, _ -> }): String {
        require(file.length() in 1..MediaCrypto.MAX_CIPHER_BYTES)
        require(HASH.matches(sha256Hex) && recipients.isNotEmpty())
        val url = MailboxConfig.url + CHUNK_PATH + sha256Hex
        val init = JSONObject().put("size", file.length()).put("partSize", MediaCrypto.PART_BYTES)
            .put("recipients", JSONArray(recipients.distinct().sorted())).toString().toByteArray(Charsets.UTF_8)
        jsonRequest(context, "$url/init", "POST", init, initial = true)
        val status = jsonRequest(context, "$url/status", "GET")
        require(status.getLong("size") == file.length() && status.getInt("partSize") == MediaCrypto.PART_BYTES) { "Upload metadata changed" }
        val count = partCount(file.length())
        val next = status.optInt("nextPart", status.optJSONArray("receivedParts")?.length() ?: 0)
        require(next in 0..count)
        progress(minOf(file.length(), next.toLong() * MediaCrypto.PART_BYTES), file.length())
        if (status.getString("state") == "complete") return url
        val hashes = JSONArray()
        RandomAccessFile(file, "r").use { input ->
            repeat(count) { index ->
                val size = minOf(MediaCrypto.PART_BYTES.toLong(), file.length() - index.toLong() * MediaCrypto.PART_BYTES).toInt()
                val part = ByteArray(size).also(input::readFully)
                val hash = MediaCrypto.sha256Hex(part)
                hashes.put(hash)
                if (index >= next) {
                    val partUrl = "$url/parts/$index"
                    val body = object : RequestBody() {
                        override fun contentType() = BINARY
                        override fun contentLength() = part.size.toLong()
                        override fun writeTo(sink: BufferedSink) {
                            var offset = 0
                            while (offset < part.size) {
                                val n = minOf(64 * 1024, part.size - offset)
                                sink.write(part, offset, n)
                                offset += n
                                progress(index.toLong() * MediaCrypto.PART_BYTES + offset, file.length())
                            }
                        }
                    }
                    val request = Request.Builder().url(partUrl)
                        .header("Authorization", MailboxAuth.header(NostrIdentity.keys(context), "PUT", partUrl, part))
                        .header("X-Relay-Part-SHA256", hash).put(body).build()
                    http.newCall(request).execute().use { check(it.isSuccessful) { "Upload interrupted (${it.code}); it will resume" } }
                    progress(minOf(file.length(), (index + 1L) * MediaCrypto.PART_BYTES), file.length())
                }
            }
        }
        jsonRequest(context, "$url/complete", "POST", JSONObject().put("parts", hashes).toString().toByteArray(Charsets.UTF_8))
        progress(file.length(), file.length())
        return url
    }

    /** Resume only at verified complete part boundaries; validate the whole ciphertext before decrypting. */
    fun downloadChunked(context: Context, url: String, sha256Hex: String, target: File,
                        progress: (Long, Long) -> Unit = { _, _ -> }) {
        require(isChunked(url) && HASH.matches(sha256Hex) && url.endsWith("/$sha256Hex"))
        val status = jsonRequest(context, "$url/status", "GET")
        val size = status.getLong("size")
        require(status.getString("state") == "complete" && size in 1..MediaCrypto.MAX_CIPHER_BYTES && status.getInt("partSize") == MediaCrypto.PART_BYTES)
        val expectedParts = partCount(size)
        require(status.getInt("totalParts") == expectedParts)
        if (target.exists() && target.length() == size && MediaCrypto.sha256Hex(target) == sha256Hex) return
        val prefix = if (target.exists() && target.length() <= size) target.length() / MediaCrypto.PART_BYTES else 0L
        val start = prefix.coerceAtMost(expectedParts.toLong()).toInt()
        progress(minOf(size, start.toLong() * MediaCrypto.PART_BYTES), size)
        val required = size - start.toLong() * MediaCrypto.PART_BYTES
        require(target.parentFile!!.usableSpace > required + MediaCrypto.PART_BYTES) { "Not enough free storage to download this video" }
        RandomAccessFile(target, "rw").use { output ->
            output.setLength(start.toLong() * MediaCrypto.PART_BYTES)
            output.seek(output.length())
            for (index in start until expectedParts) {
                val partUrl = "$url/parts/$index"
                val request = Request.Builder().url(partUrl)
                    .header("Authorization", MailboxAuth.header(NostrIdentity.keys(context), "GET", partUrl)).build()
                val expected = minOf(MediaCrypto.PART_BYTES.toLong(), size - index.toLong() * MediaCrypto.PART_BYTES).toInt()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Download interrupted (${response.code})" }
                    val bytes = requireNotNull(response.body).byteStream().use { input ->
                        input.readNBytesCompat(expected + 1L) { read -> progress(minOf(size,index.toLong()*MediaCrypto.PART_BYTES+read),size) }
                    }
                    require(bytes.size == expected) { "Incomplete media part" }
                    val checksum = response.header("X-Relay-Part-SHA256")
                    if (checksum != null) require(MediaCrypto.sha256Hex(bytes) == checksum) { "Invalid media part" }
                    output.write(bytes)
                    output.fd.sync()
                    progress(minOf(size, (index + 1L) * MediaCrypto.PART_BYTES), size)
                }
            }
        }
        if (target.length() != size || MediaCrypto.sha256Hex(target) != sha256Hex) {
            target.delete()
            error("Downloaded media failed verification")
        }
    }

    internal fun partCount(size: Long): Int {
        require(size in 1..MediaCrypto.MAX_CIPHER_BYTES)
        return ((size + MediaCrypto.PART_BYTES - 1L) / MediaCrypto.PART_BYTES).toInt()
    }

    private fun jsonRequest(context: Context, url: String, method: String, bytes: ByteArray? = null, initial: Boolean = false): JSONObject {
        val body = bytes?.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url)
            .header("Authorization", MailboxAuth.header(NostrIdentity.keys(context), method, url, bytes))
            .method(method, body).build()
        return http.newCall(request).execute().use { response ->
            if (initial && response.code in listOf(404, 405)) throw ServerUpdateRequired()
            check(response.isSuccessful) { "Media server request failed (${response.code})" }
            val data = requireNotNull(response.body).byteStream().use { it.readNBytesCompat(512 * 1024L + 1) }
            require(data.size <= 512 * 1024)
            JSONObject(String(data, Charsets.UTF_8))
        }
    }

    /** Stores [blob] (its SHA-256 is [sha256Hex]) for [recipients] and returns the reference URL. */
    fun upload(context: Context, blob: ByteArray, sha256Hex: String, recipients: List<String>,
               progress: (Long, Long) -> Unit = { _, _ -> }): String {
        require(recipients.isNotEmpty()) { "Nobody to send the file to" }
        val url = urlFor(sha256Hex)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(NostrIdentity.keys(context), "PUT", url, blob))
            .header("x-relay-recipients", recipients.distinct().joinToString(","))
            .put(object : RequestBody() {
                override fun contentType() = BINARY
                override fun contentLength() = blob.size.toLong()
                override fun writeTo(sink: BufferedSink) {
                    var at = 0
                    while (at < blob.size) {
                        val n = minOf(64 * 1024, blob.size - at)
                        sink.write(blob, at, n)
                        at += n
                        progress(at.toLong(), blob.size.toLong())
                    }
                }
            })
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Upload failed (${response.code})" }
        }
        return url
    }

    /** The file's bytes if this install may have them and they match [sha256Hex]; null otherwise. */
    fun download(context: Context, url: String, sha256Hex: String, maxBytes: Long): ByteArray? {
        if (!isOurs(url) || isChunked(url) || maxBytes !in 1..(32L * 1024 * 1024)) return null
        val request = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(NostrIdentity.keys(context), "GET", url))
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
    fun confirm(context: Context, url: String) {
        if (!isOurs(url)) return
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", MailboxAuth.header(NostrIdentity.keys(context), "DELETE", url))
                .delete()
                .build()
            http.newCall(request).execute().close()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long, progress: (Long) -> Unit = {}): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), limit - out.size()).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            progress(out.size().toLong())
        }
        return out.toByteArray()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
