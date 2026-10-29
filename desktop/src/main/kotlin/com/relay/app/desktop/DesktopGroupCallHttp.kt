package com.relay.app.desktop

import com.relay.app.transport.mailbox.MailboxAuth
import com.relay.app.transport.mailbox.NostrSigner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The Worker's group-call surface (PROTOCOL.md "Group calls", WP15 + G5), signed with the ENROLLED DEVICE key and carrying
 * `?device=<id>` on every request, like every other linked-desktop call. Responses are bounded; the join secret is only ever
 * in the socket URL the protocol defines and is never logged.
 */
class DesktopGroupCallHttp(
    baseUrl: String,
    private val signer: NostrSigner,
    private val deviceId: String,
    httpClient: OkHttpClient? = null,
) : GroupCallRoomApi {
    private val base = baseUrl.trim().trimEnd('/')
    private val http = (httpClient?.newBuilder() ?: OkHttpClient.Builder())
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val device get() = "device=$deviceId"

    init { require(base.startsWith("http://") || base.startsWith("https://")) { "Mailbox URL must be HTTP(S)" } }

    private class Reply(val status: Int, val body: String, val rostered: String?)

    private fun call(method: String, url: String, json: String?): Reply {
        val bytes = json?.toByteArray(Charsets.UTF_8)
        val builder = Request.Builder().url(url).header("Authorization", MailboxAuth.header(signer, method, url, bytes))
        val body = (bytes ?: ByteArray(0)).toRequestBody(JSON)
        val request = when (method) {
            "GET" -> builder.get().build()
            "PUT" -> builder.put(body).build()
            else -> builder.post(body).build()
        }
        http.newCall(request).execute().use { r ->
            val text = r.body?.source()?.let { DesktopMailboxTransport.boundedUtf8OrNull(it, MAX_REPLY) }.orEmpty()
            return Reply(r.code, text, r.header("x-relay-call-rostered"))
        }
    }

    private fun errorCode(body: String): String? = try { JSONObject(body).optJSONObject("error")?.optString("code")?.takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    override fun create(request: CreateRoomRequest): CreateRoomOutcome {
        val json = JSONObject().put("v", 1).put("room", request.room).put("secretHash", request.secretHash)
            .put("group", request.group).put("revision", request.revision).put("rosterHash", request.rosterHash)
            .put("members", JSONArray(request.members)).toString()
        val reply = try { call("POST", "$base/v1/call/rooms?$device", json) } catch (_: IOException) { return CreateRoomOutcome.Unreachable }
        val code = errorCode(reply.body)
        return when {
            reply.status == 200 -> {
                // A roster-bound create must answer rostered: anything else is a server that ignored the roster. Never accept it.
                val rostered = try { JSONObject(reply.body).optBoolean("rostered", false) } catch (_: Exception) { false }
                if (rostered || reply.rostered == "1") CreateRoomOutcome.Created else CreateRoomOutcome.Refused(200, "not_rostered", false)
            }
            reply.status == 409 && code == "group_room_exists" -> CreateRoomOutcome.GroupRoomExists(
                try { JSONObject(reply.body).optString("room").takeIf { Regex("[0-9a-f]{32}").matches(it) } } catch (_: Exception) { null })
            reply.status == 409 && code == "stale_revision" -> CreateRoomOutcome.StaleRevision
            reply.status == 403 && code == "not_in_roster" -> CreateRoomOutcome.NotInRoster
            else -> CreateRoomOutcome.Refused(reply.status, code, retryable = reply.status == 429 || reply.status >= 500)
        }
    }

    override fun info(room: String): RoomInfoOutcome {
        val reply = try { call("GET", "$base/v1/call/rooms/$room?$device", null) } catch (_: IOException) { return RoomInfoOutcome.Unknown }
        return when (reply.status) {
            200 -> try {
                val o = JSONObject(reply.body)
                RoomInfoOutcome.Live(o.optBoolean("rostered", false), if (o.has("revision")) o.optLong("revision") else null,
                    o.optString("rosterHash").takeIf { it.isNotEmpty() }, o.optInt("participants"))
            } catch (_: Exception) { RoomInfoOutcome.Unknown }
            404 -> RoomInfoOutcome.Gone
            else -> RoomInfoOutcome.Unknown
        }
    }

    override fun end(room: String): Boolean = ok { call("POST", "$base/v1/call/rooms/$room/end?$device", null) }

    override fun removeMembers(room: String, remove: List<String>, secretHash: String?): Boolean {
        val json = JSONObject().put("v", 1).put("remove", JSONArray(remove.sorted().distinct())).also { if (secretHash != null) it.put("secretHash", secretHash) }.toString()
        return ok { call("POST", "$base/v1/call/rooms/$room/roster?$device", json) }
    }

    override fun advanceRoster(room: String, advance: RosterAdvance): Boolean {
        val json = JSONObject().put("v", 1)
            .put("group", advance.group).put("revision", advance.revision).put("rosterHash", advance.rosterHash)
            .put("members", JSONArray(advance.members)).put("owner", advance.owner).put("rosterSig", advance.rosterSig)
            .put("issuedAt", advance.issuedAt).put("expiresAt", advance.expiresAt).put("secretHash", advance.secretHash)
            .toString()
        return ok { call("POST", "$base/v1/call/rooms/$room/roster?$device", json) }
    }

    override fun rotate(room: String, secretHash: String): Boolean =
        ok { call("POST", "$base/v1/call/rooms/$room/rotate?$device", JSONObject().put("v", 1).put("secretHash", secretHash).toString()) }

    private fun ok(block: () -> Reply): Boolean = try { block().status == 200 } catch (_: IOException) { false }

    override fun connect(room: String, secret: String, pid: String?, listener: RoomListener): RoomSocket {
        val wsBase = base.replaceFirst(Regex("^http"), "ws")
        val url = "$wsBase/v1/call/rooms/$room/ws?secret=$secret&$device" + (pid?.let { "&pid=$it" } ?: "")
        val request = Request.Builder().url(url).header("Authorization", MailboxAuth.header(signer, "GET", url)).build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.length <= MAX_FRAME) listener.onText(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { runCatching { webSocket.close(code, reason) }; listener.onClosed(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                val code = response?.let { r -> runCatching { r.body?.source()?.let { DesktopMailboxTransport.boundedUtf8OrNull(it, 4096) }?.let(::errorCode) }.getOrNull() }
                listener.onFailure(status, code)
            }
        })
        return object : RoomSocket {
            override fun send(text: String) = ws.send(text)
            override fun close(code: Int, reason: String) { runCatching { ws.close(code, reason) } }
        }
    }

    override fun sfu(room: String): SfuApi = SfuApi { op, body ->
        require(op in SFU_OPS) { "Unsupported SFU operation" }
        val method = if (op == "renegotiate" || op == "tracks-close") "PUT" else "POST"
        val url = "$base/v1/call/rooms/$room/sfu/$op?$device"
        val reply = try { call(method, url, JSONObject(body.toString()).put("v", 1).toString()) }
        catch (e: IOException) { throw SfuException(0, null, "SFU request failed") }
        if (reply.status !in 200..299) throw SfuException(reply.status, errorCode(reply.body), "SFU $op refused (${reply.status})")
        try { JSONObject(reply.body) } catch (_: Exception) { throw SfuException(reply.status, null, "SFU $op returned an unreadable answer") }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val SFU_OPS = setOf("sessions-new", "tracks-new", "renegotiate", "tracks-close")
        const val MAX_REPLY = 512L * 1024
        const val MAX_FRAME = 64 * 1024
    }
}
