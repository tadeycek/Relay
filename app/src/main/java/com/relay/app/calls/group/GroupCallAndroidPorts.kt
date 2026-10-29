package com.relay.app.calls.group

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.relay.app.transport.mailbox.AndroidNostrSigner
import com.relay.app.transport.mailbox.MailboxAuth
import com.relay.app.transport.mailbox.MailboxConfig
import com.relay.app.transport.mailbox.PhoneDeviceEnrollment
import com.relay.app.transport.nostr.NostrIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal const val GROUP_DEVICE = "primary"

/**
 * The group-call REST surface, NIP-98 signed with the same enrolled device key as every other mailbox
 * request (G1). Paths passed by [GroupCallSessionCore] already carry their query string because NIP-98
 * binds the exact URL. A transport failure is HTTP 0, which every caller treats as retryable.
 */
class AndroidGroupCallHttp(context: Context) : GroupCallHttp {
    private val app = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    override suspend fun request(method: String, path: String, body: String?): HttpAnswer {
        if (!MailboxConfig.available) return HttpAnswer(0, "")
        val url = MailboxConfig.url + path
        val bytes = body?.toByteArray(Charsets.UTF_8)
        return try {
            val keys = PhoneDeviceEnrollment.authKeys(app) { NostrIdentity.keys(app) }
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", MailboxAuth.header(AndroidNostrSigner { keys }, method, url, bytes))
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post((bytes ?: ByteArray(0)).toRequestBody(jsonType))
                "PUT" -> builder.put((bytes ?: ByteArray(0)).toRequestBody(jsonType))
                "DELETE" -> builder.delete()
                else -> return HttpAnswer(0, "")
            }
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty().take(MAX_BODY)
                HttpAnswer(response.code, text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$method $path failed: ${e.message}")
            HttpAnswer(0, "")
        }
    }

    private companion object {
        const val TAG = "RelayGroupCallHttp"
        const val MAX_BODY = 64 * 1024
    }
}

/**
 * The room control socket (`GET /v1/call/rooms/<room>/ws?secret=…&device=…[&pid=…]`) over OkHttp.
 *
 * The upgrade is NIP-98 signed for the exact URL (including the secret query) exactly like the mailbox
 * socket. Heartbeat, pid reuse and the no-reconnect 4009 policy stay in [RoomClient]; this port only
 * moves frames and reports the HTTP status of a refused upgrade.
 */
class AndroidRoomSocketPort(context: Context) : RoomSocketPort {
    private val app = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // a control socket is long-lived
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun open(room: String, secret: String, pid: String?, listener: RoomSocketListener): RoomSocketHandle {
        val base = MailboxConfig.url.replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        val url = buildString {
            append(base).append("/v1/call/rooms/").append(room).append("/ws?secret=").append(secret)
            append("&device=").append(GROUP_DEVICE)
            if (pid != null) append("&pid=").append(pid)
        }
        if (!MailboxConfig.available) {
            listener.onFailure(null, "unlinked")
            return ClosedHandle
        }
        return try {
            val keys = PhoneDeviceEnrollment.authKeys(app) { NostrIdentity.keys(app) }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", MailboxAuth.header(AndroidNostrSigner { keys }, "GET", url))
                .build()
            val socket = http.newWebSocket(request, Listener(listener))
            SocketHandle(socket)
        } catch (e: Exception) {
            Log.w(TAG, "room socket failed: ${e.message}")
            listener.onFailure(null, "transport")
            ClosedHandle
        }
    }

    private class Listener(private val sink: RoomSocketListener) : WebSocketListener() {
        private val reported = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) = sink.onOpen()

        override fun onMessage(webSocket: WebSocket, text: String) = sink.onText(text)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (reported.compareAndSet(false, true)) sink.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!reported.compareAndSet(false, true)) return
            val status = response?.code
            val errorCode = runCatching {
                val text = response?.body?.string().orEmpty()
                JSONObject(text).optJSONObject("error")?.optString("code")?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            sink.onFailure(status, errorCode)
        }
    }

    private class SocketHandle(private val socket: WebSocket) : RoomSocketHandle {
        override fun send(text: String): Boolean = socket.send(text)
        override fun close(code: Int, reason: String) {
            runCatching { socket.close(code, reason) }
        }
    }

    private object ClosedHandle : RoomSocketHandle {
        override fun send(text: String): Boolean = false
        override fun close(code: Int, reason: String) = Unit
    }

    private companion object { const val TAG = "RelayGroupRoomSocket" }
}

/** Main-looper timers for [RoomClient]'s heartbeat and reconnect policy. */
class AndroidRoomTimers(private val handler: Handler = Handler(Looper.getMainLooper())) : RoomTimers {
    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun after(delayMs: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0))
        return { handler.removeCallbacks(runnable) }
    }
}
