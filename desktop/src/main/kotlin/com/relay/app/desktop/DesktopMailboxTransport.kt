package com.relay.app.desktop

import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.mailbox.MailboxAuth
import com.relay.app.transport.mailbox.MailboxMessage
import com.relay.app.transport.mailbox.MailboxProtocol
import com.relay.app.transport.mailbox.MailboxSession
import com.relay.app.transport.mailbox.NostrSigner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import rust.nostr.sdk.Keys
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The slice of the local store the transport needs to keep packets until they are handled. */
interface RawPacketStore {
    fun store(messages: List<MailboxMessage>): List<Long>
    fun pending(): List<MailboxMessage>
    fun delete(seq: Long): Boolean
}

/**
 * Relay's encrypted mailbox over HTTP + WebSocket for the desktop client. It reuses the same wire
 * protocol and receipt rules as the phone (:core MailboxProtocol / MailboxSession), so the server
 * cannot tell the two clients apart.
 *
 * Two modes, decided by [device]:
 *  - **local-only** (device = null): requests are signed with this install's own identity key and
 *    carry no `device` parameter, exactly like a 1.15 phone.
 *  - **linked** ([DesktopDeviceAuth]): every request is signed with the enrolled device-auth key and
 *    carries `?device=<id>`, so after migration the server binds this client to its own device
 *    cursor instead of the phone's. Gift-wrap encryption still uses the shared identity keys
 *    ([keys]); only the HTTP/WS signature changes.
 */
class DesktopMailboxTransport(
    baseUrl: String,
    private val keys: Keys,
    private val signer: NostrSigner,
    private val rawStore: RawPacketStore,
    private val device: DesktopDeviceAuth? = null,
    httpClient: OkHttpClient? = null,
) : Transport {

    private val base = baseUrl.trim().trimEnd('/')
    private val deviceQuery = device?.let { "?device=${it.deviceId}" }.orEmpty()
    private val socketUrl = base.replaceFirst(Regex("^http"), "ws") + "/v1/ws" + deviceQuery

    /** The key that signs HTTP/WS requests: the enrolled device key once linked, else the identity. */
    private val requestSigner: NostrSigner get() = device?.signer ?: signer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = (httpClient?.newBuilder() ?: OkHttpClient.Builder())
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val lifecycleLock = Mutex()
    private var loopJob: Job? = null
    private var openerJob: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var live: WebSocket? = null
    @Volatile private var generation = 0

    private val _status = MutableStateFlow(TransportStatus.STOPPED)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    private val inbox = Channel<IncomingEnvelope>(Channel.UNLIMITED)
    override val incoming: Flow<IncomingEnvelope> = inbox.receiveAsFlow()

    /** Last non-fatal error, for the UI banner. Contains no message content. */
    @Volatile var lastError: String? = null
        private set

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) { "Mailbox URL must be HTTP(S)" }
    }

    override suspend fun start() = lifecycleLock.withLock {
        if (openerJob?.isActive != true) openerJob = scope.launch { openStored() }
        if (loopJob?.isActive == true) return@withLock
        val gen = ++generation
        loopJob = scope.launch { runConnectionLoop(gen) }
    }

    override suspend fun stop() = lifecycleLock.withLock {
        generation++
        loopJob?.cancel()
        loopJob = null
        closeSocket()
        _status.value = TransportStatus.STOPPED
    }

    override suspend fun reconnect() = lifecycleLock.withLock {
        generation++
        loopJob?.cancel()
        closeSocket()
        _status.value = TransportStatus.CONNECTING
        val gen = generation
        loopJob = scope.launch { runConnectionLoop(gen) }
    }

    override suspend fun send(
        recipientPubkeyHex: String,
        payload: RelayPayload,
        relayHints: List<String>,
        ephemeral: Boolean,
    ): SendResult = sendMedia(recipientPubkeyHex, payload, emptyList(), ephemeral)

    override suspend fun sendWithMedia(recipientPubkeyHex: String, payload: RelayPayload, mediaDigests: List<String>, relayHints: List<String>): SendResult =
        sendMedia(recipientPubkeyHex, payload, mediaDigests, false)

    private suspend fun sendMedia(recipientPubkeyHex: String, payload: RelayPayload, mediaDigests: List<String>, ephemeral: Boolean): SendResult {
        if (ephemeral) return SendResult.Failed("Signals are not implemented in the WP06 desktop core", retryable = false)
        return withContext(Dispatchers.IO) {
            if (!MailboxProtocol.isRecipient(recipientPubkeyHex)) return@withContext SendResult.Failed("Invalid recipient key", retryable = false)
            if (!MailboxProtocol.isDeliveryId(payload.id)) return@withContext SendResult.Failed("Invalid message id", retryable = false)
            try {
                // Validate before encryption/network, never truncate or silently omit references.
                MailboxProtocol.sendBody(recipientPubkeyHex, payload.id, "validation", mediaDigests)
                val url = "$base/v1/send$deviceQuery"
                val body = if (mediaDigests.isEmpty()) {
                    MailboxProtocol.sendBody(recipientPubkeyHex, payload.id, DesktopGiftWrap.wrap(keys, recipientPubkeyHex, payload))
                } else {
                    val durable = rawStore as? DesktopMessageStore ?: return@withContext SendResult.Failed("Durable media outbox required", false)
                    val existing = durable.mediaSendBodyOrNull(recipientPubkeyHex, payload.id)
                    val candidate = existing ?: MailboxProtocol.sendBody(recipientPubkeyHex, payload.id, DesktopGiftWrap.wrap(keys, recipientPubkeyHex, payload), mediaDigests)
                    durable.mediaSendBody(recipientPubkeyHex, payload.id) { candidate }.also { persisted ->
                        require(org.json.JSONObject(persisted).getJSONArray("media").toString() == org.json.JSONArray(mediaDigests).toString()) { "Media retry reference mismatch" }
                    }
                }
                val bytes = body.toByteArray(Charsets.UTF_8)
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", MailboxAuth.header(requestSigner, "POST", url, bytes))
                    .post(bytes.toRequestBody(JSON))
                    .build()
                http.newCall(request).execute().use { response ->
                    when (val outcome = MailboxProtocol.sendOutcome(response.code, response.body?.string().orEmpty())) {
                        MailboxProtocol.Outcome.Stored -> SendResult.Sent(1)
                        MailboxProtocol.Outcome.NotOnMailbox -> SendResult.Failed("Recipient is not on the mailbox", retryable = false)
                        is MailboxProtocol.Outcome.Retry -> SendResult.Failed(outcome.reason, retryable = true)
                        is MailboxProtocol.Outcome.Rejected -> SendResult.Failed(outcome.reason, retryable = false)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                SendResult.Failed("Invalid mailbox media request", retryable = false)
            } catch (e: IOException) {
                SendResult.Failed("Mailbox unreachable: ${e.message}", retryable = true)
            } catch (e: Exception) {
                SendResult.Failed("Mailbox request failed: ${e.message}", retryable = true)
            }
        }
    }

    // ---- receiving ---------------------------------------------------------------------------

    private suspend fun runConnectionLoop(gen: Int) {
        var failures = 0
        while (scope.isActive && gen == generation) {
            _status.value = TransportStatus.CONNECTING
            val ended = CompletableDeferred<Int?>()
            val ws = try {
                val request = Request.Builder()
                    .url(socketUrl)
                    .header("Authorization", MailboxAuth.header(requestSigner, "GET", socketUrl))
                    .build()
                http.newWebSocket(request, Listener(gen, ended) { failures = 0 })
            } catch (e: Exception) {
                lastError = "Couldn't open the mailbox socket: ${e.message}"
                ended.complete(null)
                null
            }
            socket = ws
            val refusedWith = ended.await()
            if (socket === ws) socket = null
            if (gen != generation) return
            _status.value = TransportStatus.OFFLINE
            val wait = if (refusedWith == 403) NOT_ALLOWED_RETRY_MS else backoffMs(++failures)
            withTimeoutOrNull(wait) { /* simple sleep; stop() cancels the loop */ }
        }
    }

    private fun closeSocket() {
        socket?.let { runCatching { it.close(1000, "closing") } }
        socket = null
        live = null
    }

    private inner class Listener(
        private val gen: Int,
        private val ended: CompletableDeferred<Int?>,
        private val onReady: () -> Unit,
    ) : WebSocketListener() {

        private val session = MailboxSession()

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) {
                webSocket.close(1000, "superseded")
                return
            }
            when (val frame = MailboxProtocol.parseFrame(text)) {
                is MailboxProtocol.Frame.Ready -> {
                    live = webSocket
                    _status.value = TransportStatus.ONLINE
                    lastError = null
                    onReady()
                    perform(webSocket, session.onReady())
                }
                is MailboxProtocol.Frame.Batch -> perform(webSocket, session.onBatch(frame.messages, frame.more, frame.cursor))
                is MailboxProtocol.Frame.Message -> perform(webSocket, session.onPush(frame.message))
                is MailboxProtocol.Frame.Signal -> scope.launch {
                    DesktopGiftWrap.unwrap(keys, frame.envelope)?.let { (sender, payload) ->
                        inbox.trySend(IncomingEnvelope(sender, payload, System.currentTimeMillis()))
                    }
                }
                is MailboxProtocol.Frame.Error -> {
                    lastError = "Mailbox error ${frame.code} for ${frame.ref}"
                    webSocket.close(4000, "retry")
                }
                else -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (live === webSocket) live = null
            ended.complete(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (live === webSocket) live = null
            lastError = "Mailbox socket failed (${response?.code ?: "no response"}): ${t.message}"
            ended.complete(response?.code)
        }
    }

    /** Runs session actions in order. A failed raw store closes the socket without acknowledging. */
    private fun perform(ws: WebSocket, actions: List<MailboxSession.Action>) {
        for (action in actions) when (action) {
            is MailboxSession.Action.Store -> {
                try {
                    rawStore.store(action.messages)
                } catch (e: Exception) {
                    lastError = "Couldn't store mailbox packets: ${e.message}"
                    ws.close(1011, "local store failed")
                    return
                }
            }
            is MailboxSession.Action.Sync -> ws.send(MailboxProtocol.syncFrame(action.after))
            is MailboxSession.Action.Ack -> ws.send(MailboxProtocol.ackFrame(action.through))
        }
    }

    /** Opens stored packets left by an earlier run, then follows new raw arrivals via the WS listener. */
    private suspend fun openStored() {
        // The WS listener stores raw packets; this loop polls the raw table and opens whatever is
        // there. A crash between ack and decryption therefore loses nothing: the packet stays local
        // and is opened on the next run.
        while (scope.isActive) {
            val pending = runCatching { rawStore.pending() }.getOrElse { emptyList() }
            for (packet in pending) {
                val opened = DesktopGiftWrap.unwrap(keys, packet.envelope)
                if (opened == null) {
                    rawStore.delete(packet.seq)
                    continue
                }
                val (sender, payload) = opened
                inbox.send(
                    IncomingEnvelope(sender, payload, System.currentTimeMillis(), onHandled = {
                        runCatching { rawStore.delete(packet.seq) }
                    }),
                )
            }
            kotlinx.coroutines.delay(250)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val NOT_ALLOWED_RETRY_MS = 5L * 60 * 1000

        fun backoffMs(failures: Int): Long = when {
            failures <= 1 -> 1_000
            failures == 2 -> 2_000
            failures == 3 -> 4_000
            failures == 4 -> 8_000
            else -> 30_000
        }
    }
}
