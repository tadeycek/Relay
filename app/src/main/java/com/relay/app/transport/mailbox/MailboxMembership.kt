package com.relay.app.transport.mailbox

import android.content.Context
import android.util.Log
import com.relay.app.transport.nostr.NostrIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Who may use the delivery server grows by in-person QR pairing (Tadej, 2026-10-18): a member who
 * pairs with someone new lets them in. Pairing messages themselves travel through the server, so this
 * happens at scan time, before the pairing request is sent. Blocking; call off the main thread.
 */
object MailboxMembership {

    private const val TAG = "MailboxMembership"
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Showing our QR: register its one-time code (only its SHA-256) as an invite, so whoever scans it
     * can join if they're new. Refused, harmlessly, while this phone isn't a member itself.
     */
    fun invite(context: Context, code: String, expiresAtMs: Long) {
        runCatching {
            val body = JSONObject().put("v", 1).put("code", sha256Hex(code)).put("expiresAt", expiresAtMs).toString()
            post(context, "/v1/invite", body).use { Log.i(TAG, "invite registration: HTTP ${it.code}") }
        }.onFailure { Log.w(TAG, "invite not registered: ${it.message}") }
    }

    /**
     * Scanned [peer]'s QR with one-time [code]: vouch for them if we're a member, or join with their
     * invite if we're new. True when both can now use the server, false when one can't, null when the
     * server couldn't be reached.
     */
    fun pair(context: Context, peer: String, code: String): Boolean? = try {
        val body = JSONObject().put("v", 1).put("peer", peer).put("code", code).toString()
        post(context, "/v1/pair", body).use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "pairing check refused: HTTP ${response.code}")
                return null
            }
            val reply = JSONObject(response.body?.string().orEmpty())
            Log.i(TAG, "pairing check: this phone on the server ${reply.optBoolean("you")}, peer ${reply.optBoolean("peer")}")
            reply.optBoolean("you") && reply.optBoolean("peer")
        }
    } catch (e: Exception) {
        Log.w(TAG, "pairing check failed: ${e.message}")
        null
    }

    private fun post(context: Context, path: String, body: String): okhttp3.Response {
        val url = MailboxConfig.url + path
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(NostrIdentity.keys(context), "POST", url, bytes))
            .post(bytes.toRequestBody(JSON))
            .build()
        return http.newCall(request).execute()
    }

    private fun sha256Hex(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
