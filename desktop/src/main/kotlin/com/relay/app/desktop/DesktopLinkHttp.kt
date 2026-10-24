package com.relay.app.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The real `GET`/`DELETE /v1/link/<slot>` calls.
 *
 * Both are **unauthenticated**: the desktop has no identity before the bundle arrives, so the
 * one-time code is the only credential. It travels in `x-relay-link-proof`, never in the URL (URLs
 * land in access logs). No NIP-98 header is attached, and there is no `?device=` parameter, because
 * the server's link route refuses a query string outright.
 *
 * Responses are bounded: a slot holds at most 128 KiB, and anything larger is discarded before the
 * decoder sees it so a hostile endpoint cannot make this process allocate without limit.
 */
class DesktopLinkHttp(
    baseUrl: String,
    private val http: OkHttpClient = defaultClient(),
) : LinkHttp {

    private val base = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) { "Mailbox URL must be HTTP(S)" }
    }

    override suspend fun get(slot: String, proof: String): LinkHttp.Response =
        call("GET", slot, proof, body = null)

    override suspend fun delete(slot: String, proof: String): LinkHttp.Response =
        call("DELETE", slot, proof, body = null)

    private suspend fun call(
        method: String,
        slot: String,
        proof: String,
        body: ByteArray?,
    ): LinkHttp.Response = withContext(Dispatchers.IO) {
        val url = "$base/v1/link/$slot"
        val request = Request.Builder()
            .url(url)
            .header(LINK_PROOF_HEADER, proof)
            .apply {
                when (method) {
                    "GET" -> get()
                    "DELETE" -> delete()
                    else -> error("unsupported method $method")
                }
            }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val bytes = readBounded(response.body)
                when {
                    bytes == null -> LinkHttp.Response.Refused(response.code, "too_large")
                    response.code == 200 && bytes.isNotEmpty() -> LinkHttp.Response.Body(bytes)
                    response.code == 200 -> LinkHttp.Response.Refused(200, "empty")
                    else -> LinkHttp.Response.Refused(response.code, errorCode(bytes))
                }
            }
        } catch (e: IOException) {
            LinkHttp.Response.Unreachable(e.javaClass.simpleName)
        }
    }

    /** Reads at most [MAX_RESPONSE_BYTES]; anything bigger is refused before it can be allocated. */
    private fun readBounded(body: okhttp3.ResponseBody?): ByteArray? {
        if (body == null) return ByteArray(0)
        if (body.contentLength() > MAX_RESPONSE_BYTES) return null
        val out = java.io.ByteArrayOutputStream()
        body.byteStream().use { input ->
            val chunk = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (out.size() + n > MAX_RESPONSE_BYTES) return null
                out.write(chunk, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** The server's `{"v":1,"error":{"code":"…"}}`, or null for a non-JSON body. */
    private fun errorCode(bytes: ByteArray): String? = runCatching {
        org.json.JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("error").optString("code")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        /** Same spelling as [com.relay.app.linking.LinkCode.PROOF_HEADER]; pinned here too so a core
         *  rename cannot silently change the wire. */
        const val LINK_PROOF_HEADER = "x-relay-link-proof"

        /** A slot holds ≤128 KiB; this is a hard stop well above it. */
        const val MAX_RESPONSE_BYTES = 256 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
