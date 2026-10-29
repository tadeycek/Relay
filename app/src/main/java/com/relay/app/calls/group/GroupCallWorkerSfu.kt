package com.relay.app.calls.group

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * [SfuApi] over the Worker proxy (`POST /v1/call/rooms/<room>/sfu/<op>`). The provider app id/secret and
 * the provider URL never exist on the phone: every call is NIP-98 signed, bound to the room, and the
 * proxy refuses a session id the signer does not own.
 *
 * Non-2xx answers become [SfuReply] with [SfuReply.retryable] from the server's error object; a transport
 * failure is status 0 and retryable. No operation falls back to the provider directly.
 */
class WorkerSfuApi(private val http: GroupCallHttp, private val room: String) : SfuApi {

    override suspend fun sessionsNew(): SfuReply = post("sessions-new", JSONObject().put("v", 1))

    override suspend fun tracksNewLocal(sessionId: String, offerSdp: String, mid: String): SfuReply =
        post("tracks-new", JSONObject().put("v", 1).put("sessionId", sessionId)
            .put("sessionDescription", JSONObject().put("type", "offer").put("sdp", offerSdp))
            .put("tracks", JSONArray().put(JSONObject().put("location", "local").put("mid", mid).put("trackName", "mic"))))

    override suspend fun tracksNewRemote(sessionId: String, publisherSessions: List<String>): SfuReply =
        post("tracks-new", JSONObject().put("v", 1).put("sessionId", sessionId)
            .put("tracks", JSONArray().apply {
                publisherSessions.forEach { put(JSONObject().put("location", "remote").put("sessionId", it).put("trackName", "mic")) }
            }))

    override suspend fun renegotiate(sessionId: String, answerSdp: String): SfuReply =
        post("renegotiate", JSONObject().put("v", 1).put("sessionId", sessionId).put("answer", answerSdp))

    override suspend fun tracksClose(sessionId: String, mids: List<String>): SfuReply =
        post("tracks-close", JSONObject().put("v", 1).put("sessionId", sessionId)
            .put("mids", JSONArray().apply { mids.forEach { put(it) } }))

    private suspend fun post(op: String, body: JSONObject): SfuReply {
        val answer = http.request("POST", "/v1/call/rooms/$room/sfu/$op?device=$GROUP_DEVICE", body.toString())
        if (answer.status == 0) return SfuReply(0, errorCode = "transport", retryable = true)
        val json = runCatching { JSONObject(answer.body) }.getOrNull()
        if (answer.status != 200) {
            val error = json?.optJSONObject("error")
            return SfuReply(
                answer.status,
                errorCode = error?.optString("code")?.takeIf { it.isNotEmpty() },
                retryable = error?.optBoolean("retryable") ?: (answer.status == 429 || answer.status >= 500),
            )
        }
        if (json == null) return SfuReply(200, errorCode = "bad_response", retryable = false)
        val tracks = json.optJSONArray("tracks")?.let { arr ->
            (0 until minOf(arr.length(), 64)).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SfuTrack(o.optString("mid").takeIf { it.isNotEmpty() }, o.optString("trackName").takeIf { it.isNotEmpty() })
            }
        }.orEmpty()
        val description = json.optJSONObject("sessionDescription")
        return SfuReply(
            status = 200,
            sessionId = json.optString("sessionId").takeIf { it.isNotEmpty() },
            tracks = tracks,
            sdp = description?.optString("sdp")?.takeIf { it.isNotEmpty() },
            sdpType = description?.optString("type")?.takeIf { it.isNotEmpty() },
            requiresImmediateRenegotiation = json.optBoolean("requiresImmediateRenegotiation", false),
        )
    }

    private companion object { const val TAG = "RelayGroupSfuApi" }
}
