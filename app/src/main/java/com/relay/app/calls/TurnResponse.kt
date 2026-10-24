package com.relay.app.calls

import com.relay.app.call.IceServer
import org.json.JSONObject

/** What `GET /v1/call/ice` gave us. */
sealed class TurnResult {
    data class Ok(val servers: List<IceServer>, val expiresAtMs: Long?) : TurnResult()
    data class Failed(val code: String?, val retryable: Boolean) : TurnResult()
}

/**
 * Parses the credential endpoint (PROTOCOL.md "Calls (v1.4, WP11)"). Pure, so a malformed or hostile
 * provider answer becomes an honest [CallEvent.IceServersFailed] instead of a peer connection with no
 * usable relays.
 */
object TurnResponse {

    private const val MAX_SERVERS = 8
    private const val MAX_URLS = 8
    private const val MAX_CREDENTIAL = 512
    private val SCHEME = Regex("^(stun|turn|turns):", RegexOption.IGNORE_CASE)

    fun parse(httpStatus: Int, body: String): TurnResult {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
        if (httpStatus != 200) {
            val error = json?.optJSONObject("error")
            val code = error?.optString("code")?.takeIf { it.isNotEmpty() }
            val retryable = error?.optBoolean("retryable") ?: (httpStatus == 429 || httpStatus >= 500)
            return TurnResult.Failed(code, retryable && httpStatus != 503)
        }
        if (json == null) return TurnResult.Failed("bad_response", false)
        val array = json.optJSONArray("iceServers") ?: return TurnResult.Failed("bad_response", false)
        val servers = ArrayList<IceServer>(array.length())
        for (i in 0 until minOf(array.length(), MAX_SERVERS)) {
            val entry = array.optJSONObject(i) ?: continue
            val urls = entry.optJSONArray("urls") ?: continue
            val clean = (0 until urls.length()).mapNotNull { urls.optString(it).takeIf(String::isNotBlank) }
                .filter { SCHEME.containsMatchIn(it) }
                .take(MAX_URLS)
            if (clean.isEmpty()) continue
            val username = entry.optString("username").takeIf { it.isNotEmpty() && it.length <= MAX_CREDENTIAL }
            val credential = entry.optString("credential").takeIf { it.isNotEmpty() && it.length <= MAX_CREDENTIAL }
            servers += IceServer(clean, username, credential)
        }
        if (servers.isEmpty()) return TurnResult.Failed("no_ice_servers", false)
        // Without a relay the call only works on a friendly network; the engine would still try, but the
        // endpoint's contract guarantees a TURN entry, so a TURN-less answer means something broke.
        if (servers.none { s -> s.urls.any { it.startsWith("turn", ignoreCase = true) } && s.username != null && s.credential != null }) {
            return TurnResult.Failed("no_turn_server", false)
        }
        val expiresAt = json.optLong("expiresAt", 0L).takeIf { it > 0 }
        return TurnResult.Ok(servers, expiresAt)
    }
}
