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
import org.json.JSONObject
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
 * Receives call-signalling bodies (`TYPE:CALL|…`) instead of the chat inbox (WP14). [route] runs the host's
 * handling synchronously and returns when it is done: a stored delivery is only deleted afterwards.
 * [sender] is the authenticated NIP-17 seal sender, never a field of the body.
 */
fun interface DesktopCallRouter {
    fun route(sender: String, body: String, stored: Boolean): Boolean
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
) : Transport, DesktopCallLink {

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

    /** Set by the call service before [start]. Call bodies never reach [incoming], with or without a router. */
    @Volatile override var callRouter: DesktopCallRouter? = null

    private val ownPubkey = keys.publicKey().toHex()
    private val lifecycleLock = Mutex()
    private var loopJob: Job? = null
    private var openerJob: Job? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var live: WebSocket? = null
    @Volatile private var generation = 0

    private val _status = MutableStateFlow(TransportStatus.STOPPED)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()

    /**
     * Live `signal` envelopes in arrival order. ONE consumer unwraps and routes them, so frames reach the call host in the
     * order the socket delivered them (a coroutine per frame let a later offer/ICE overtake an earlier invite). Bounded:
     * a flood of frames drops the newest instead of growing memory; call signalling is lossy by design.
     */
    private val signalInbox = Channel<String>(MAX_PENDING_SIGNALS)
    private val inbox = Channel<IncomingEnvelope>(Channel.UNLIMITED)
    override val incoming: Flow<IncomingEnvelope> = inbox.receiveAsFlow()

    /** Last non-fatal error, for the UI banner. Contains no message content. */
    @Volatile var lastError: String? = null
        private set

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) { "Mailbox URL must be HTTP(S)" }
        scope.launch {
            for (envelope in signalInbox) {
                try {
                    DesktopGiftWrap.unwrap(keys, envelope)?.let { (sender, payload) ->
                        if (isCallBody(payload.body)) {
                            // Call signalling is never a chat row. A throwing host must not drop the connection or the consumer.
                            runCatching { callRouter?.route(sender, payload.body, false) }
                        } else inbox.trySend(IncomingEnvelope(sender, payload, System.currentTimeMillis()))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { /* an undecryptable frame is dropped */ }
            }
        }
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
        if (ephemeral) return sendSignal(recipientPubkeyHex, payload)
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
                    val reply = response.body?.string().orEmpty()
                    when (val outcome = MailboxProtocol.sendOutcome(response.code, reply)) {
                        MailboxProtocol.Outcome.Stored -> {
                            if (mediaDigests.isNotEmpty() && !mediaGrantAccepted(reply, mediaDigests))
                                SendResult.Failed("Media grant absent or unavailable; use a new delivery after re-upload (same id cannot repair it)", retryable = false)
                            else SendResult.Sent(1)
                        }
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

    // ---- calls (WP14) ------------------------------------------------------------------------

    override fun isOnline(): Boolean = status.value == TransportStatus.ONLINE
    override suspend fun signal(to: String, payload: RelayPayload): SendResult = send(to, payload, ephemeral = true)

    private val callAttempts = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private val signalSeq = java.util.concurrent.atomic.AtomicLong()

    /**
     * Sends one call body as a NIP-17 gift-wrapped `signal` frame on the live socket. Nothing is stored; with no
     * live socket the send fails (retryable) instead of silently pretending. Only call bodies may travel this way,
     * and the envelope must fit the server's signal bound.
     */
    private suspend fun sendSignal(recipient: String, payload: RelayPayload): SendResult = withContext(Dispatchers.IO) {
        if (!MailboxProtocol.isRecipient(recipient)) return@withContext SendResult.Failed("Invalid recipient key", retryable = false)
        if (!payload.body.startsWith(com.relay.app.call.CallLimits.PREFIX)) return@withContext SendResult.Failed("Only call signals may be sent live", retryable = false)
        try {
            val envelope = DesktopGiftWrap.wrap(keys, recipient, payload)
            if (envelope.toByteArray(Charsets.UTF_8).size > MAX_SIGNAL_BYTES) return@withContext SendResult.Failed("Call signal too large", retryable = false)
            val ws = live ?: return@withContext SendResult.Failed("Mailbox socket is not connected", retryable = true)
            val frame = MailboxProtocol.signalFrame(recipient, envelope, "sig" + signalSeq.incrementAndGet())
            if (ws.send(frame)) SendResult.Sent(1) else SendResult.Failed("Mailbox socket refused the signal", retryable = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Failed("Call signal failed", retryable = true)
        }
    }

    /**
     * The stored copy of the first call invite: `POST /v1/send` with `"urgent": true` (PROTOCOL.md "urgent on a
     * delivery"). One delivery, never to this identity, envelope within the delivery bound. Best effort, one attempt:
     * the server budgets urgent sends (1 per recipient per 3 s) and answers 429 over budget, which is reported as
     * retryable and never repeated blindly here.
     */
    override suspend fun deliverUrgent(to: String, payload: RelayPayload): SendResult = withContext(Dispatchers.IO) {
        val recipientPubkeyHex = to
        if (!MailboxProtocol.isRecipient(recipientPubkeyHex)) return@withContext SendResult.Failed("Invalid recipient key", retryable = false)
        if (recipientPubkeyHex == ownPubkey) return@withContext SendResult.Failed("Urgent delivery to this identity is refused", retryable = false)
        if (!MailboxProtocol.isDeliveryId(payload.id)) return@withContext SendResult.Failed("Invalid message id", retryable = false)
        // A prefix check is not enough: only a codec-valid invite from this identity to this recipient may be stored urgent.
        val decoded = com.relay.app.call.CallBodyCodec.decode(
            payload.body,
            com.relay.app.call.CallReceiveContext(selfPubkey = recipientPubkeyHex, senderPubkey = ownPubkey, nowMs = payload.ts),
        )
        if ((decoded as? com.relay.app.call.CallParseResult.Ok)?.packet !is com.relay.app.call.CallPacket.Invite)
            return@withContext SendResult.Failed("Only call invites may be sent urgent", retryable = false)
        try {
            val envelope = DesktopGiftWrap.wrap(keys, recipientPubkeyHex, payload)
            if (envelope.toByteArray(Charsets.UTF_8).size > MAX_STORED_INVITE_BYTES) return@withContext SendResult.Failed("Call invite too large", retryable = false)
            val json = JSONObject(MailboxProtocol.sendBody(recipientPubkeyHex, payload.id, envelope))
            json.getJSONArray("deliveries").getJSONObject(0).put("urgent", true)
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val url = "$base/v1/send$deviceQuery"
            val request = Request.Builder().url(url)
                .header("Authorization", MailboxAuth.header(requestSigner, "POST", url, bytes))
                .post(bytes.toRequestBody(JSON)).build()
            http.newCall(request).execute().use { response ->
                val reply = response.body?.source()?.let { boundedUtf8(it, MAX_REPLY_BYTES.toLong()) }.orEmpty()
                when (val outcome = MailboxProtocol.sendOutcome(response.code, reply)) {
                    MailboxProtocol.Outcome.Stored -> SendResult.Sent(1)
                    MailboxProtocol.Outcome.NotOnMailbox -> SendResult.Failed("Recipient is not on the mailbox", retryable = false)
                    is MailboxProtocol.Outcome.Retry -> SendResult.Failed(outcome.reason, retryable = true)
                    is MailboxProtocol.Outcome.Rejected -> SendResult.Failed(outcome.reason, retryable = false)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Failed("Urgent call delivery failed", retryable = true)
        }
    }

    /**
     * Authenticated `GET /v1/call/ice[?device=<id>]`, signed with the enrolled device key (the identity key before
     * linking). [reply] gets `(status, body)` exactly once, or never after the returned handle was closed; status 0
     * with a null body means a transport failure or an answer over [MAX_ICE_BYTES]. Nothing here logs the body
     * (it holds TURN credentials).
     */
    override fun fetchIce(reply: (status: Int, body: String?) -> Unit): AutoCloseable {
        val url = "$base/v1/call/ice$deviceQuery"
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val call = try {
            val request = Request.Builder().url(url).header("Authorization", MailboxAuth.header(requestSigner, "GET", url)).get().build()
            http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().newCall(request)
        } catch (e: Exception) {
            reply(0, null)
            return AutoCloseable { }
        }
        fun report(status: Int, body: String?) { if (!cancelled.get()) reply(status, body) }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) = report(0, null)
            override fun onResponse(call: okhttp3.Call, response: Response) {
                // A body read that dies (cancel, reset) is a transport failure, never an exception out of the callback.
                val out = try {
                    response.use {
                        val source = it.body?.source()
                        val text = if (source == null) "" else boundedUtf8OrNull(source, MAX_ICE_BYTES.toLong())
                        if (text == null) 0 to null else it.code to text
                    }
                } catch (e: IOException) { 0 to null }
                report(out.first, out.second)
            }
        })
        return AutoCloseable { cancelled.set(true); call.cancel() }
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
                is MailboxProtocol.Frame.Signal -> signalInbox.trySend(frame.envelope)
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
                if (isCallBody(payload.body)) {
                    // Handle first, delete after. A host failure is retried a few times, then dropped (an invite is
                    // short-lived anyway) so one poison packet cannot spin this loop forever.
                    val handled = try { callRouter?.route(sender, payload.body, true); true } catch (e: Exception) { false }
                    val tries = if (handled) 0 else callAttempts.merge(packet.seq, 1, Int::plus)!!
                    if (handled || tries >= MAX_CALL_ATTEMPTS) { callAttempts.remove(packet.seq); runCatching { rawStore.delete(packet.seq) } }
                    continue
                }
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
        /** PROTOCOL.md: `MAX_SIGNAL_BYTES` default; WP02 measured real call frames under 14 KiB. */
        const val MAX_SIGNAL_BYTES = 16_384
        /** PROTOCOL.md limits: envelope 128 KiB. */
        const val MAX_STORED_INVITE_BYTES = 128 * 1024
        const val MAX_ICE_BYTES = 16 * 1024
        private const val MAX_REPLY_BYTES = 64 * 1024
        private const val MAX_CALL_ATTEMPTS = 3
        private const val MAX_PENDING_SIGNALS = 256

        internal fun isCallBody(body: String) = body.startsWith(com.relay.app.call.CallLimits.PREFIX)

        /** Reads at most [limit] bytes; null when the stream is longer (never buffers an unbounded body). */
        internal fun boundedUtf8OrNull(source: okio.BufferedSource, limit: Long): String? {
            val buffer = okio.Buffer()
            while (buffer.size <= limit) { if (source.read(buffer, 8192) < 0) return buffer.readUtf8() }
            return null
        }
        private fun boundedUtf8(source: okio.BufferedSource, limit: Long): String = boundedUtf8OrNull(source, limit) ?: ""

        /** A stored delivery is not proof of its blob grant. Missing/duplicate/gone fail closed. */
        internal fun mediaGrantAccepted(reply: String, digests: List<String>): Boolean = try {
            val results = org.json.JSONObject(reply).getJSONArray("media")
            results.length() == digests.size && digests.indices.all { i ->
                val item = results.getJSONObject(i)
                item.getString("hash") == digests[i] && item.getString("status") in setOf("attached", "pending")
            }
        } catch (_: Exception) { false }

        fun backoffMs(failures: Int): Long = when {
            failures <= 1 -> 1_000
            failures == 2 -> 2_000
            failures == 3 -> 4_000
            failures == 4 -> 8_000
            else -> 30_000
        }
    }
}
