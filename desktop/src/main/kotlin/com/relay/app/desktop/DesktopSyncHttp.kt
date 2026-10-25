package com.relay.app.desktop

import com.relay.app.transport.mailbox.MailboxAuth
import com.relay.app.transport.mailbox.NostrSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * `POST /v1/sync`, `GET /v1/sync`, `POST /v1/sync/ack`, signed with [signer] and carrying
 * `?device=<deviceId>`: the enrolled device key and 32-hex id for a linked computer, or the
 * phone's administration key and `primary` in tests that play the phone.
 *
 * Responses are read with a hard cap (a page is ≤ 64 KiB of event bytes, ≈ 90 KiB as base64 JSON).
 */
internal class MediaGrantFailure(message: String) : Exception(message)

class DesktopSyncHttp(
    baseUrl: String,
    private val signer: NostrSigner,
    private val deviceId: String,
    private val http: OkHttpClient = defaultClient(),
) : SyncHttp {

    private val base = baseUrl.trim().trimEnd('/')

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) { "Mailbox URL must be HTTP(S)" }
    }

    override suspend fun post(envelopeJson: String): SyncHttpResult<Boolean> =
        call("POST", "/v1/sync?device=$deviceId", envelopeJson.toByteArray(Charsets.UTF_8)) { json ->
            val requested = JSONObject(envelopeJson).optJSONArray("media")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            // A duplicate carries no grant list; the first publication was judged when it happened.
            SyncWire.checkMediaReply(requested, json).takeIf { !json.optBoolean("duplicate", false) }?.let { throw MediaGrantFailure(it) }
            json.optBoolean("duplicate", false)
        }

    override suspend fun list(after: Long, limit: Int): SyncHttpResult<SyncPage> =
        call("GET", "/v1/sync?after=$after&limit=$limit&device=$deviceId", null) { json ->
            val array = json.getJSONArray("events")
            val events = (0 until array.length()).map { i ->
                val e = array.getJSONObject(i)
                SyncEventDto(
                    cursor = e.getLong("cursor"),
                    id = e.getString("id"),
                    rev = e.getLong("rev"),
                    total = e.getLong("total"),
                    hash = e.getString("hash"),
                    dataBase64 = e.getString("data"),
                )
            }
            SyncPage(events, json.optBoolean("more", false), json.optLong("cursor", after))
        }

    override suspend fun ack(through: Long): SyncHttpResult<Long> =
        call("POST", "/v1/sync/ack?device=$deviceId", """{"v":1,"through":$through}""".toByteArray()) { json ->
            json.optLong("cursor", through)
        }

    private suspend fun <T> call(
        method: String,
        pathAndQuery: String,
        body: ByteArray?,
        parse: (JSONObject) -> T,
    ): SyncHttpResult<T> = withContext(Dispatchers.IO) {
        val url = base + pathAndQuery
        try {
            val builder = Request.Builder().url(url)
                .header("Authorization", MailboxAuth.header(signer, method, url, body))
            if (method == "GET") builder.get() else builder.post((body ?: ByteArray(0)).toRequestBody(JSON))
            http.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.byteStream()?.use { it.readNBytes(MAX_RESPONSE_BYTES + 1) } ?: ByteArray(0)
                if (bytes.size > MAX_RESPONSE_BYTES) return@use SyncHttpResult.Rejected("response too large")
                val text = String(bytes, Charsets.UTF_8)
                if (response.code in 200..299) {
                    runCatching { SyncHttpResult.Ok(parse(JSONObject(text))) }
                        .getOrElse { if (it is MediaGrantFailure) SyncHttpResult.Rejected(it.message ?: "media grant failed", final = true) else SyncHttpResult.Rejected("unreadable response") }
                } else {
                    classify(response.code, text)
                }
            }
        } catch (e: IOException) {
            SyncHttpResult.Retry("unreachable")
        } catch (e: Exception) {
            SyncHttpResult.Retry("request failed")
        }
    }

    /** Reason codes only; the server's error body never contains message content. */
    private fun classify(status: Int, text: String): SyncHttpResult<Nothing> {
        val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
        val code = error?.optString("code").orEmpty().ifEmpty { "http_$status" }
        val retryable = error?.optBoolean("retryable", false) ?: false
        return when {
            status >= 500 || status == 429 || status == 401 || status == 403 || retryable -> SyncHttpResult.Retry("$status $code")
            else -> SyncHttpResult.Rejected("$status $code")
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_RESPONSE_BYTES = 256 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
