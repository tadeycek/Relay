package com.relay.app.media

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * Minimal Blossom (BUD-01/02) client: upload an already-encrypted blob and download one by URL.
 *
 * Uploads are authorised with a kind 24242 Nostr event signed by a **throwaway key generated per
 * upload**, so a media server cannot link a user's uploads to their messaging identity. Every
 * download is checked against the expected SHA-256 before anything is decrypted, so a malicious or
 * compromised server can only make a download fail, never substitute content.
 *
 * All methods are blocking; call them off the main thread. Pass a [proxy] (SOCKS) to route through Tor.
 */
class BlossomClient(
    private val proxy: Proxy = Proxy.NO_PROXY,
    /** Which URLs may be fetched. Overridable only so unit tests can use a local plain-http server. */
    private val urlCheck: (String) -> Boolean = MediaBody::isAcceptableUrl,
) {

    data class Uploaded(val url: String, val sha256Hex: String)

    /** Tries each server in order and returns the first success, or null if all refuse or fail. */
    fun upload(servers: List<String>, blob: ByteArray, sha256Hex: String): Uploaded? {
        for (server in servers) {
            val base = server.trimEnd('/')
            if (!base.startsWith("https://")) continue
            try {
                upload(base, blob, sha256Hex)?.let { return it }
            } catch (e: IOException) {
                // network/server problem: try the next server
            }
        }
        return null
    }

    private fun upload(base: String, blob: ByteArray, sha256Hex: String): Uploaded? {
        val conn = open("$base/upload").apply {
            requestMethod = "PUT"
            doOutput = true
            setFixedLengthStreamingMode(blob.size)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-SHA-256", sha256Hex)
            setRequestProperty("Authorization", authHeader(sha256Hex))
        }
        try {
            conn.outputStream.use { it.write(blob) }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val json = try { JSONObject(body) } catch (e: JSONException) { return null }
            val reportedSha = json.optString("sha256", sha256Hex).lowercase()
            if (reportedSha != sha256Hex) return null
            val url = json.optString("url").takeIf { it.isNotEmpty() } ?: "$base/$sha256Hex"
            return if (MediaBody.isAcceptableUrl(url)) Uploaded(url, sha256Hex) else null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads [url] and returns the bytes only if they hash to [expectedSha256Hex] and do not
     * exceed [maxBytes]. Follows at most [MAX_REDIRECTS] redirects, each of which must again be an
     * acceptable https URL (the hash check makes the redirect target's identity irrelevant).
     */
    fun download(url: String, expectedSha256Hex: String, maxBytes: Long): ByteArray? {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!urlCheck(current)) return null
            val conn = open(current).apply { requestMethod = "GET" }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = conn.getHeaderField("Location") ?: return null
                    return@repeat
                }
                if (code !in 200..299) return null
                val declared = conn.contentLengthLong
                if (declared > maxBytes) return null
                val bytes = readLimited(conn, maxBytes) ?: return null
                return if (MediaCrypto.sha256Hex(bytes) == expectedSha256Hex.lowercase()) bytes else null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun readLimited(conn: HttpURLConnection, maxBytes: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        conn.inputStream.use { input ->
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            useCaches = false
        }

    private fun authHeader(sha256Hex: String): String {
        val now = System.currentTimeMillis() / 1000
        val event = EventBuilder(Kind(24242u), "Upload encrypted blob")
            .tags(
                listOf(
                    Tag.parse(listOf("t", "upload")),
                    Tag.parse(listOf("x", sha256Hex)),
                    Tag.expiration(Timestamp.fromSecs((now + 300).toULong())),
                )
            )
            .signWithKeys(Keys.generate())
        return "Nostr " + Base64.encodeToString(event.asJson().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private companion object {
        const val MAX_REDIRECTS = 3
    }
}
