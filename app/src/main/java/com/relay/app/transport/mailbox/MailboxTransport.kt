package com.relay.app.transport.mailbox

import android.content.Context
import android.util.Log
import com.relay.app.messaging.MessagingDb
import com.relay.app.transport.Backoff
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.mailbox.MailboxProtocol.Outcome
import com.relay.app.transport.nostr.NostrIdentity
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
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnwrappedGift
import rust.nostr.sdk.giftWrap
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Relay's own encrypted mailbox (server/cloudflare, protocol v1) as a [Transport].
 *
 * Packets are the same NIP-17 gift wraps NostrTransport publishes, so content stays end-to-end
 * encrypted and the recipient authenticates the sender exactly as before; only the route changes.
 *
 *  - Sending: `POST /v1/send` signed with NIP-98. The wrap for each (payload id, recipient) is built
 *    once and kept in [MailboxStore] until the server confirms it, so retries are byte-identical and
 *    the server answers "duplicate" instead of storing a second copy.
 *  - Receiving: one WebSocket. Every packet is written to the local raw inbox before any receipt is
 *    sent ([MailboxSession] decides when a cumulative receipt is safe), then opened and handed to the
 *    message handler; its row is deleted once [IncomingEnvelope.onHandled] reports it was dealt with.
 *  - Typing and presence ([send] with `ephemeral`): gift-wrapped like messages but passed over the open
 *    socket to the recipient's open socket and never stored; a recipient who isn't connected misses it.
 */
class MailboxTransport(
    context: Context,
    baseUrl: String,
    private val identity: () -> Keys,
) : Transport {

    constructor(context: Context, baseUrl: String) : this(context, baseUrl, { NostrIdentity.keys(context.applicationContext) })

    private val appContext = context.applicationContext
    private val base = baseUrl.trimEnd('/')
    private val socketUrl = base.replaceFirst(Regex("^http"), "ws") + "/v1/ws?device=primary"
    private val store by lazy { MailboxStore(MessagingDb.get(appContext)) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Protocol pings keep NAT mappings open and notice dead links. Cloudflare answers them
        // without waking the mailbox, and they aren't billed.
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val lifecycleLock = Mutex()
    private var loopJob: Job? = null
    private var openerJob: Job? = null
    @Volatile private var socket: WebSocket? = null
    /** The socket after the server greeted it, i.e. one that can carry live signals right now. */
    @Volatile private var live: WebSocket? = null
    /** Same superseded-loop guard as NostrTransport: a stale loop must not touch shared state. */
    @Volatile private var generation = 0

    /** Live signals in flight, by ref, so a refusal can be traced to its recipient. Bounded: successes get no reply. */
    private val signalRefs = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }
    private val signalCounter = java.util.concurrent.atomic.AtomicLong()
    /** Recipients the server says aren't on it: no live signals for them until the time stored. */
    private val noSignalsUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Cuts a back-off sleep short: [start] on a running transport means "try now". */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Seqs stored in the raw inbox and waiting to be opened, in arrival order. */
    private val stored = Channel<Long>(Channel.UNLIMITED)
    private val inbox = Channel<IncomingEnvelope>(Channel.UNLIMITED)
    private val signalInbox = Channel<IncomingEnvelope>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    private val _status = MutableStateFlow(TransportStatus.STOPPED)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()
    override val incoming: Flow<IncomingEnvelope> = inbox.receiveAsFlow()
    override val signals: Flow<IncomingEnvelope> = signalInbox.receiveAsFlow()

    override suspend fun start() = lifecycleLock.withLock {
        if (openerJob?.isActive != true) openerJob = scope.launch { openStored() }
        if (loopJob?.isActive == true) {
            wake.trySend(Unit)
            return@withLock
        }
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
        if (openerJob?.isActive != true) openerJob = scope.launch { openStored() }
        generation++
        loopJob?.cancel()
        closeSocket()
        _status.value = TransportStatus.CONNECTING
        val gen = generation
        loopJob = scope.launch { runConnectionLoop(gen) }
    }

    override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean): SendResult {
        if (ephemeral) return signal(recipientPubkeyHex, payload)
        return when (val outcome = deliver(recipientPubkeyHex, payload)) {
            Outcome.Stored -> SendResult.Sent(1)
            Outcome.NotOnMailbox -> SendResult.Failed("Recipient is not on the mailbox", retryable = false)
            is Outcome.Retry -> SendResult.Failed(outcome.reason, retryable = true)
            is Outcome.Rejected -> SendResult.Failed(outcome.reason, retryable = false)
        }
    }

    override suspend fun sendWithMedia(recipientPubkeyHex: String, payload: RelayPayload, mediaDigests: List<String>, relayHints: List<String>): SendResult =
        when (val outcome = deliver(recipientPubkeyHex, payload, mediaDigests)) {
            Outcome.Stored -> SendResult.Sent(1)
            Outcome.NotOnMailbox -> SendResult.Failed("Recipient is not on the mailbox", retryable = false)
            is Outcome.Retry -> SendResult.Failed(outcome.reason, retryable = true)
            is Outcome.Rejected -> SendResult.Failed(outcome.reason, retryable = false)
        }

    /** Best effort, never stored: only works while connected, and is simply dropped otherwise. */
    private suspend fun signal(recipient: String, payload: RelayPayload): SendResult {
        val ws = live ?: return SendResult.Failed("Not connected", retryable = false)
        if (!MailboxProtocol.isRecipient(recipient)) return SendResult.Failed("Invalid recipient key", retryable = false)
        // Presence goes to every contact every 20 s; don't keep offering it to people not on the server.
        if ((noSignalsUntil[recipient] ?: 0) > System.currentTimeMillis()) return SendResult.Failed("Not on the delivery server", retryable = false)
        val envelope = try {
            wrap(recipient, payload)
        } catch (e: Exception) {
            return SendResult.Failed("Couldn't prepare the signal: ${e.message}", retryable = false)
        }
        val ref = "s" + signalCounter.incrementAndGet()
        synchronized(signalRefs) { signalRefs[ref] = recipient }
        return if (ws.send(MailboxProtocol.signalFrame(recipient, envelope, ref))) SendResult.Sent(1)
            else SendResult.Failed("Not connected", retryable = false)
    }

    /** Hands one message to the mailbox for [recipient]. Safe to call again with the same payload. */
    suspend fun deliver(recipient: String, payload: RelayPayload, mediaDigests: List<String> = emptyList()): Outcome = withContext(Dispatchers.IO) {
        if (!MailboxProtocol.isRecipient(recipient)) return@withContext Outcome.Rejected("Invalid recipient key")
        if (!MailboxProtocol.isDeliveryId(payload.id)) return@withContext Outcome.NotOnMailbox
        val envelope = try {
            // Only attach a reference row when there is something to reference: a plain text send must not
            // write a file_media_refs row (it used to, one per send).
            if (mediaDigests.isNotEmpty()) {
                com.relay.app.media.AndroidPhoneFileStore.persistRefs(MessagingDb.get(appContext), payload.id, mediaDigests)
            }
            store.packet(payload.id, recipient) ?: store.savePacket(payload.id, recipient, wrap(recipient, payload))
        } catch (e: Exception) {
            return@withContext Outcome.Retry("Couldn't prepare the message: ${e.message}")
        }
        val url = "$base/v1/send"
        val body = MailboxProtocol.sendBody(recipient, payload.id, envelope, mediaDigests).toByteArray(Charsets.UTF_8)
        val outcome = try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", MailboxAuth.header(AndroidNostrSigner { PhoneDeviceEnrollment.authKeys(appContext, identity) }, "POST", url, body))
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { MailboxProtocol.sendOutcome(it.code, it.body?.string().orEmpty()) }
        } catch (e: IOException) {
            Outcome.Retry("Mailbox unreachable: ${e.message}")
        } catch (e: Exception) {
            Outcome.Retry("Mailbox request failed: ${e.message}")
        }
        // Only a retry needs the same bytes again.
        if (outcome !is Outcome.Retry) runCatching { store.deletePacket(payload.id, recipient) }
        outcome
    }

    /**
     * Stores one delivery marked `urgent: true` — the call-invite wake path (`PROTOCOL.md`, "urgent on a
     * delivery"). Best effort: the caller has already sent the live signal and does not wait for this.
     * A refused send is dropped (the live invite and its resend still reach a connected callee).
     */
    suspend fun deliverUrgent(recipient: String, payload: RelayPayload): Boolean = withContext(Dispatchers.IO) {
        if (!MailboxProtocol.isRecipient(recipient)) return@withContext false
        if (!MailboxProtocol.isDeliveryId(payload.id)) return@withContext false
        try {
            val envelope = store.packet(payload.id, recipient) ?: store.savePacket(payload.id, recipient, wrap(recipient, payload))
            val url = "$base/v1/send"
            val body = MailboxUrgent.withUrgent(MailboxProtocol.sendBody(recipient, payload.id, envelope))
                .toByteArray(Charsets.UTF_8)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", MailboxAuth.header(AndroidNostrSigner { PhoneDeviceEnrollment.authKeys(appContext, identity) }, "POST", url, body))
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { response ->
                val stored = response.isSuccessful
                if (stored) runCatching { store.deletePacket(payload.id, recipient) }
                stored
            }
        } catch (e: Exception) {
            Log.w(TAG, "urgent call invite delivery failed: ${e.message}")
            false
        }
    }

    private suspend fun wrap(recipient: String, payload: RelayPayload): String {
        val keys = identity()
        val receiver = PublicKey.parse(recipient)
        val rumor = EventBuilder.privateMsgRumor(receiver, PayloadCodec.encode(payload)).build(keys.publicKey())
        return giftWrap(NostrSigner.keys(keys), receiver, rumor, emptyList()).asJson()
    }

    // ---- receiving -----------------------------------------------------------------------------

    private suspend fun runConnectionLoop(gen: Int) {
        val failures = AtomicInteger(0)
        while (scope.isActive && gen == generation) {
            _status.value = TransportStatus.CONNECTING
            val ended = CompletableDeferred<Int?>()
            val ws = try {
                val request = Request.Builder()
                    .url(socketUrl)
                    .header("Authorization", MailboxAuth.header(AndroidNostrSigner { PhoneDeviceEnrollment.authKeys(appContext, identity) }, "GET", socketUrl))
                    .build()
                http.newWebSocket(request, Listener(gen, ended) { failures.set(0) })
            } catch (e: Exception) {
                Log.w(TAG, "couldn't open the mailbox socket: ${e.message}")
                ended.complete(null)
                null
            }
            socket = ws
            val refusedWith = ended.await()
            if (socket === ws) socket = null
            if (gen != generation) return
            _status.value = TransportStatus.OFFLINE
            // Not on the allowlist: asking every minute only spends the mailbox's daily requests. Opening
            // the app (or a network change) still retries straight away, e.g. right after being added.
            val wait = if (refusedWith == 403) NOT_ALLOWED_RETRY_MS else Backoff.reconnectDelayMs(failures.incrementAndGet())
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private fun closeSocket() {
        socket?.let { runCatching { it.close(1000, "closing") } }
        socket = null
        live = null
    }

    private inner class Listener(
        private val gen: Int,
        /** Completes when the socket is gone, with the HTTP status if the upgrade itself was refused. */
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
                    onReady()
                    perform(webSocket, session.onReady())
                }
                is MailboxProtocol.Frame.Batch -> perform(webSocket, session.onBatch(frame.messages, frame.more, frame.cursor))
                is MailboxProtocol.Frame.Message -> perform(webSocket, session.onPush(frame.message))
                is MailboxProtocol.Frame.Signal -> scope.launch {
                    unwrap(frame.envelope)?.let { (sender, payload) -> signalInbox.trySend(IncomingEnvelope(sender, payload, System.currentTimeMillis())) }
                }
                is MailboxProtocol.Frame.Error -> {
                    val signalRecipient = frame.ref?.let { ref -> synchronized(signalRefs) { signalRefs.remove(ref) } }
                    if (signalRecipient != null) {
                        // A refused typing/presence signal is harmless; it must never cost the connection.
                        if (frame.code == "unknown_recipient") noSignalsUntil[signalRecipient] = System.currentTimeMillis() + NO_SIGNALS_MS
                    } else {
                        // A catch-up or receipt failed on the server: start over on a fresh connection,
                        // where a new catch-up picks up whatever is still waiting.
                        Log.w(TAG, "mailbox error ${frame.code} for ${frame.ref}; reconnecting")
                        webSocket.close(4000, "retry")
                    }
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
            Log.w(TAG, "mailbox socket failed (${response?.code ?: "no response"}): ${t.message}")
            ended.complete(response?.code)
        }
    }

    /** Runs a session's actions in order, stopping (and dropping the connection) if storing fails. */
    private fun perform(ws: WebSocket, actions: List<MailboxSession.Action>) {
        for (action in actions) when (action) {
            is MailboxSession.Action.Store -> {
                val fresh = try {
                    store.storeAll(action.messages)
                } catch (e: Exception) {
                    // Nothing may be acknowledged that isn't stored; the server keeps it for next time.
                    Log.e(TAG, "couldn't store mailbox packets", e)
                    ws.close(1011, "local store failed")
                    return
                }
                fresh.forEach { stored.trySend(it) }
            }
            is MailboxSession.Action.Sync -> ws.send(MailboxProtocol.syncFrame(action.after))
            is MailboxSession.Action.Ack -> ws.send(MailboxProtocol.ackFrame(action.through))
        }
    }

    /** Opens stored packets one by one: leftovers from an earlier run first, then new arrivals. */
    private suspend fun openStored() {
        runCatching { store.purgePackets() }
        runCatching { store.replayable() }.onSuccess { it.forEach { seq -> stored.send(seq) } }
            .onFailure { Log.e(TAG, "couldn't read the mailbox inbox", it) }
        for (seq in stored) {
            try {
                open(seq)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "opening mailbox packet $seq failed", e)
            }
        }
    }

    /** Decrypts a gift wrap addressed to this install and authenticates its sender, or null. */
    private suspend fun unwrap(envelope: String): Pair<String, RelayPayload>? = try {
        val event = Event.fromJson(envelope)
        if (event.kind().asU16() != GIFT_WRAP_KIND) null
        else {
            val gift = UnwrappedGift.fromGiftWrap(NostrSigner.keys(identity()), event)
            val rumor = gift.rumor()
            val sender = gift.sender().toHex()
            // The rumor is unsigned; its claimed author must be the key that signed the seal.
            if (rumor.kind().asU16() != CHAT_MESSAGE_KIND || rumor.author().toHex() != sender) null
            else PayloadCodec.decode(rumor.content())?.let { sender to it }
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun open(seq: Long) {
        val envelope = store.envelope(seq) ?: return
        val opened = unwrap(envelope)
        if (opened == null) {
            // Not for us, corrupt, or another dialect: nothing will ever make it readable.
            Log.d(TAG, "dropping unreadable mailbox packet $seq")
            store.delete(seq)
            return
        }
        val (sender, payload) = opened
        inbox.send(IncomingEnvelope(sender, payload, System.currentTimeMillis(), onHandled = { runCatching { store.delete(seq) } }))
    }

    /**
     * One NIP-98-signed request on this transport's mailbox, for the few protocol calls that are not
     * messages (device enrolment, the link slot, sync). [path] is appended to the mailbox base URL and
     * must already carry any query string, because NIP-98 binds the exact URL.
     *
     * Signing stays here — the same [MailboxAuth] + [AndroidNostrSigner] pair as every other request —
     * but [keys] lets a caller sign as the phone-only admin key (device operations) while the rest of
     * the transport keeps using the identity key. `status == 0` means the request never got an answer.
     */
    suspend fun signedRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        keys: Keys = identity(),
    ): SignedResponse = withContext(Dispatchers.IO) {
        val url = base + path
        val verb = method.uppercase()
        val payload = if (verb == "GET" || verb == "HEAD") null else body
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(AndroidNostrSigner { keys }, verb, url, payload))
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (payload == null) builder.get() else builder.method(verb, payload.toRequestBody(BYTES))
        try {
            http.newCall(builder.build()).execute().use { response ->
                SignedResponse(response.code, runCatching { response.body?.string().orEmpty() }.getOrDefault(""))
            }
        } catch (e: IOException) {
            SignedResponse(0, "")
        } catch (e: Exception) {
            SignedResponse(0, "")
        }
    }

    /** Answer to one [signedRequest]. [errorCode] is the server's `error.code`, when it sent one. */
    data class SignedResponse(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
        val errorCode: String?
            get() = try {
                JSONObject(body).optJSONObject("error")?.optString("code")?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
    }

    companion object {
        private const val TAG = "MailboxTransport"
        private val JSON = "application/json".toMediaType()
        private val BYTES = "application/octet-stream".toMediaType()
        private val GIFT_WRAP_KIND: UShort = 1059u
        private val CHAT_MESSAGE_KIND: UShort = 14u
        private const val NOT_ALLOWED_RETRY_MS = 5L * 60 * 1000
        private const val NO_SIGNALS_MS = 60L * 60 * 1000
    }
}
