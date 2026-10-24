package com.relay.app.transport.mailbox

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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
        .followRedirects(false)
        .followSslRedirects(false)
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

    const val MAX_STORED_BYTES = 26_214_400L
    private const val BUFFER_BYTES = 64 * 1024

    /** Use the phone's admin signer after migration, or the desktop's enrolled device signer.
     * Null device is the legacy primary request (no query); explicit primary is supported. */
    data class RequestContext(val signer: NostrSigner, val device: String? = null) {
        init { require(device == null || device == "primary" || Regex("^[0-9a-f]{32}$").matches(device)) }
    }

    /** Immutable, replayable opaque ciphertext. Each open must start at offset zero and return a new
     * owned stream. The caller must keep its contents stable until upload completes. */
    class RawSource(val length: Long, val open: () -> InputStream)

    /** Thread-safe one-operation cancellation. Cancels the HTTP call and closes active I/O, including
     * a source blocked in read. Do not reuse after cancellation. No coroutine dependency required. */
    class Cancellation {
        private var cancelled = false
        private val actions = mutableSetOf<() -> Unit>()
        fun cancel() {
            val pending = synchronized(this) {
                if (cancelled) return
                cancelled = true
                actions.toList().also { actions.clear() }
            }
            pending.forEach { runCatching(it) }
        }
        internal fun check() { synchronized(this) { if (cancelled) throw InterruptedIOException("Media operation cancelled") } }
        internal fun attach(action: () -> Unit): Closeable {
            synchronized(this) {
                if (cancelled) { runCatching(action); throw InterruptedIOException("Media operation cancelled") }
                actions.add(action)
            }
            return Closeable { synchronized(this) { actions.remove(action) } }
        }
    }

    class HttpFailure(val status: Int, val code: String?, val retryable: Boolean) :
        IOException("Mailbox media request failed ($status${code?.let { ": $it" }.orEmpty()})")

    sealed interface Confirmation {
        data object Confirmed : Confirmation
        /** Null status means local validation / I/O failed. Codes are bounded server error codes. */
        data class Failed(val status: Int?, val code: String?, val retryable: Boolean) : Confirmation
    }

    /** Blocking, bounded raw-file transport. No encryption or public-file publication happens here.
     * A per-client snapshot of the configured origin prevents config changes redirecting in-flight work.
     * Inject a platform-configured HTTP client for proxy/Tor/timeouts; redirects are always disabled. */
    class Client(
        private val context: RequestContext,
        baseUrl: String = MailboxConfig.url,
        httpClient: OkHttpClient = http,
    ) {
        private val base = baseUrl.toHttpUrl().also {
            require(it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty() &&
                it.query == null && it.fragment == null && it.encodedPath == "/") { "Expected HTTPS mailbox origin" }
        }.toString().trimEnd('/')
        private val client = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

        fun urlFor(sha256Hex: String): String {
            require(HASH.matches(sha256Hex)) { "Invalid ciphertext SHA-256" }
            return base + PATH + sha256Hex
        }

        private fun requestUrl(url: String): String {
            require(url.startsWith(base + PATH) && HASH.matches(url.removePrefix(base + PATH))) { "Untrusted media URL" }
            return url.toHttpUrl().newBuilder().also { builder ->
                context.device?.let { builder.addQueryParameter("device", it) }
            }.build().toString()
        }

        private fun request(method: String, url: String, digest: String? = null): Request.Builder {
            val exact = requestUrl(url)
            return Request.Builder().url(exact).header("Authorization",
                MailboxAuth.headerWithDigest(context.signer, method, exact, digest))
        }

        fun upload(file: File, sha256Hex: String, recipients: List<String>, cancellation: Cancellation = Cancellation()): String =
            upload(RawSource(file.length()) { file.inputStream() }, sha256Hex, recipients, cancellation)

        fun upload(source: RawSource, sha256Hex: String, recipients: List<String>, cancellation: Cancellation = Cancellation()): String {
            require(source.length in 1..MAX_STORED_BYTES) { "Invalid stored blob size" }
            require(recipients.isNotEmpty() && recipients.distinct().size <= 16 && recipients.all(MailboxProtocol::isRecipient)) {
                "Expected 1–16 recipient public keys"
            }
            val url = urlFor(sha256Hex)
            // Preflight catches stale/wrong digest before sending any bytes. This is bounded disk I/O,
            // not a whole-body allocation; every subsequent write verifies again (TOCTOU/retry fence).
            copySource(source, sha256Hex, cancellation) { _, _ -> }
            val body = object : RequestBody() {
                override fun contentType() = BINARY
                override fun contentLength() = source.length
                override fun writeTo(sink: BufferedSink) {
                    copySource(source, sha256Hex, cancellation) { b, n -> sink.write(b, 0, n) }
                }
            }
            val req = request("PUT", url, sha256Hex)
                .header("x-relay-recipients", recipients.distinct().joinToString(","))
                .put(body).build()
            execute(req, cancellation) { response -> requireSuccess(response, cancellation) }
            return url // Reference is canonical and device-independent, never the signed query URL.
        }

        private fun copySource(source: RawSource, hash: String, cancellation: Cancellation, write: (ByteArray, Int) -> Unit) {
            cancellation.check()
            source.open().use { input ->
                cancellation.attach { input.close() }.use {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val n = copyBounded(input, source.length, cancellation) { b, len -> digest.update(b, 0, len); write(b, len) }
                    if (n != source.length || hex(digest.digest()) != hash) throw IOException("Ciphertext size or SHA-256 mismatch")
                }
            }
        }

        /** Creates a NEW owner-only file in the caller's PRIVATE staging directory. Never replaces an
         * existing target, follows a target symlink, confirms a receipt, or publishes plaintext. On every
         * failure/cancellation removes its partial file. Only a successful return makes staging usable;
         * caller still must authenticate/decrypt, durably store, then confirm. Parent must be trusted. */
        fun downloadTo(url: String, sha256Hex: String, staging: File, maxBytes: Long,
                       cancellation: Cancellation = Cancellation()): Long {
            require(maxBytes in 1..MAX_STORED_BYTES) { "Invalid download bound" }
            require(url == urlFor(sha256Hex)) { "Media URL/digest mismatch" }
            val req = request("GET", url).build()
            cancellation.check()
            // CREATE_NEW + atomic permissions: no shared world-readable interval. Android/Linux/macOS
            // support POSIX attributes; unsupported providers fail closed instead of weakening privacy.
            Files.createFile(staging.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            var complete = false
            try {
                val count = execute(req, cancellation) { response ->
                    requireSuccess(response, cancellation)
                    val body = response.body ?: throw IOException("Missing media body")
                    val declared = body.contentLength()
                    if (declared > maxBytes) throw IOException("Media exceeds download bound")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val n = staging.outputStream().use { output ->
                        cancellation.attach { output.close() }.use {
                            body.byteStream().use { input ->
                                cancellation.attach { input.close() }.use {
                                    copyBounded(input, maxBytes, cancellation) { b, len ->
                                        digest.update(b, 0, len); output.write(b, 0, len)
                                    }
                                }
                            }
                        }
                    }
                    if ((declared >= 0 && declared != n) || hex(digest.digest()) != sha256Hex) throw IOException("Ciphertext size or SHA-256 mismatch")
                    n
                }
                cancellation.check()
                complete = true
                return count
            } finally {
                if (!complete) Files.deleteIfExists(staging.toPath())
            }
        }

        /** Explicit outcome; unlike the legacy helper, no HTTP status is silently treated as success.
         * Cancellation propagates; a revoked signer is Failed(401, "auth_revoked", false). */
        fun confirm(url: String, cancellation: Cancellation = Cancellation()): Confirmation = try {
            execute(request("DELETE", url).delete().build(), cancellation) { response ->
                requireSuccess(response, cancellation)
                Confirmation.Confirmed
            }
        } catch (e: InterruptedIOException) {
            cancellation.check() // propagate actual cancellation, but report ordinary network timeouts
            Confirmation.Failed(null, null, true)
        } catch (e: HttpFailure) {
            Confirmation.Failed(e.status, e.code, e.retryable)
        } catch (e: IOException) {
            Confirmation.Failed(null, null, true)
        } catch (e: IllegalArgumentException) {
            Confirmation.Failed(null, "invalid_url", false)
        }

        private fun <T> execute(req: Request, cancellation: Cancellation, block: (Response) -> T): T {
            cancellation.check()
            val call = client.newCall(req)
            return cancellation.attach { call.cancel() }.use {
                try {
                    call.execute().use { response ->
                        cancellation.check()
                        block(response).also { cancellation.check() }
                    }
                } catch (e: IOException) { cancellation.check(); throw e }
            }
        }

        private fun requireSuccess(response: Response, cancellation: Cancellation) {
            if (response.isSuccessful) return
            // Error replies are untrusted too. Never use string()/bytes() on an unbounded body.
            val error = try {
                val out = java.io.ByteArrayOutputStream()
                response.body?.byteStream()?.use { input -> copyBounded(input, 4096, cancellation) { b, n -> out.write(b, 0, n) } }
                JSONObject(out.toString("UTF-8")).optJSONObject("error")
            } catch (e: IOException) { cancellation.check(); null }
              catch (e: org.json.JSONException) { null }
            val code = error?.optString("code")?.takeIf { Regex("^[a-z0-9_]{1,64}$").matches(it) }
            throw HttpFailure(response.code, code,
                if (response.code == 401 || response.code == 403) false
                else error?.optBoolean("retryable", response.code >= 500 || response.code == 429)
                    ?: (response.code >= 500 || response.code == 429))
        }
    }

    private fun copyBounded(input: InputStream, limit: Long, cancellation: Cancellation,
                            write: (ByteArray, Int) -> Unit): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            cancellation.check()
            val n = try {
                input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
            } catch (e: IOException) { cancellation.check(); throw e }
            cancellation.check()
            if (n < 0) return total
            if (n == 0) throw IOException("Media source made no progress")
            if (n > limit - total) throw IOException("Media exceeds byte bound")
            write(buffer, n)
            total += n
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
