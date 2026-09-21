package com.relay.app.transport.nostr

import android.content.Context
import android.util.Log
import com.relay.app.transport.Backoff
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SeenIds
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientOptions
import rust.nostr.sdk.Connection
import rust.nostr.sdk.ConnectionMode
import rust.nostr.sdk.ConnectionTarget
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnwrappedGift

/** SOCKS5 proxy (e.g. Orbot at 127.0.0.1:9050) all relay connections are routed through. */
data class ProxyConfig(val host: String, val port: Int)

/**
 * NIP-17 private direct messages over public Nostr relays.
 *
 *  - Outgoing: payload JSON -> kind 14 rumor -> seal (signed by us) -> kind 1059 gift wrap (random
 *    throwaway key), published to the configured relays plus the caller's hints.
 *  - Incoming: a long-lived subscription for kind 1059 events addressed to our key; each is
 *    unwrapped (which also verifies the seal signature), checked to be a kind 14 whose author is the
 *    seal signer, and decoded as a [RelayPayload].
 *
 * The connection loop reconnects with backoff; the rust-nostr client also reconnects individual
 * relays on its own.
 *
 * NOTE: not yet exercised against real relays or on a device (no device was available when this was
 * written) — see rebuild/PROGRESS.md.
 */
class NostrTransport(
    private val context: Context,
    private val relays: () -> List<String>,
    private val proxy: () -> ProxyConfig? = { null },
    private val lastSeenStore: LastSeenStore = PrefsLastSeenStore(context),
) : Transport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Mutex()
    private var loopJob: Job? = null
    @Volatile private var client: Client? = null
    @Volatile private var signer: NostrSigner? = null

    private val seen = SeenIds()
    private val inbox = Channel<IncomingEnvelope>(Channel.UNLIMITED)

    private val _status = MutableStateFlow(TransportStatus.STOPPED)
    override val status: StateFlow<TransportStatus> = _status.asStateFlow()
    override val incoming: Flow<IncomingEnvelope> = inbox.receiveAsFlow()

    override suspend fun start() = lifecycleLock.withLock {
        if (loopJob?.isActive == true) return@withLock
        loopJob = scope.launch { runConnectionLoop() }
    }

    override suspend fun stop() = lifecycleLock.withLock {
        loopJob?.cancelAndJoin()
        loopJob = null
        teardownClient()
        _status.value = TransportStatus.STOPPED
    }

    override suspend fun send(
        recipientPubkeyHex: String,
        payload: RelayPayload,
        relayHints: List<String>,
    ): SendResult {
        val c = client ?: return SendResult.Failed("Transport not connected", retryable = true)
        val me = NostrIdentity.keys(context)
        val receiver = try {
            PublicKey.parse(recipientPubkeyHex)
        } catch (e: Exception) {
            return SendResult.Failed("Invalid recipient key", retryable = false)
        }
        return try {
            val targets = (relays() + relayHints).distinct()
            val urls = targets.mapNotNull { runCatching { RelayUrl.parse(it) }.getOrNull() }
            if (urls.isEmpty()) return SendResult.Failed("No usable relays configured", retryable = false)
            urls.forEach { runCatching { c.addRelay(it); c.connectRelay(it) } }

            val rumor = EventBuilder.privateMsgRumor(receiver, PayloadCodec.encode(payload)).build(me.publicKey())
            val out = c.giftWrapTo(urls, receiver, rumor, emptyList())
            if (out.success.isNotEmpty()) SendResult.Sent(out.success.size)
            else SendResult.Failed(out.failed.values.firstOrNull() ?: "No relay accepted the message")
        } catch (e: Exception) {
            SendResult.Failed(e.message ?: "Send failed")
        }
    }

    private suspend fun runConnectionLoop() {
        var failures = 0
        while (scope.isActive) {
            try {
                _status.value = TransportStatus.CONNECTING
                val keys = NostrIdentity.keys(context)
                val s = NostrSigner.keys(keys)
                val c = buildClient(s)
                signer = s
                client = c

                relays().mapNotNull { runCatching { RelayUrl.parse(it) }.getOrNull() }
                    .forEach { runCatching { c.addRelay(it) } }
                c.connect()

                c.subscribe(subscriptionFilter(keys.publicKey()), null)
                _status.value = TransportStatus.ONLINE
                failures = 0

                // Blocks until the notification stream ends or the client is torn down.
                c.handleNotifications(object : HandleNotification {
                    override suspend fun handleMsg(relayUrl: RelayUrl, msg: RelayMessage) {}
                    override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
                        onEvent(event)
                    }
                })
                _status.value = TransportStatus.OFFLINE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connection loop error: ${e.message}")
                _status.value = TransportStatus.OFFLINE
            }
            teardownClient()
            failures++
            delay(Backoff.reconnectDelayMs(failures))
        }
    }

    private fun buildClient(s: NostrSigner): Client {
        val opts = ClientOptions()
        proxy()?.let { p ->
            opts.connection(
                Connection()
                    .mode(ConnectionMode.Proxy(p.host, p.port.toUShort()))
                    .target(ConnectionTarget.ALL)
            )
        }
        return ClientBuilder().signer(s).opts(opts).build()
    }

    /**
     * Gift-wrap timestamps are randomised up to two days into the past (NIP-17), so a wrap
     * published after our last check can carry a `created_at` up to two days before it. Looking
     * back [LOOKBACK_SECS] from the last time we heard anything (plus a day of slack) guarantees
     * nothing is missed; duplicates are removed by [SeenIds] and by persistent message-id checks.
     */
    private fun subscriptionFilter(me: PublicKey): Filter {
        val nowSecs = System.currentTimeMillis() / 1000
        val last = lastSeenStore.lastSeenSecs().takeIf { it > 0 } ?: nowSecs
        val since = (last - LOOKBACK_SECS).coerceAtLeast(0)
        return Filter().kind(Kind(GIFT_WRAP_KIND)).pubkey(me).since(Timestamp.fromSecs(since.toULong()))
    }

    private suspend fun onEvent(event: Event) {
        if (event.kind().asU16() != GIFT_WRAP_KIND) return
        val eventId = event.id().toHex()
        if (!seen.markIfNew(eventId)) return
        val s = signer ?: return
        try {
            val gift = UnwrappedGift.fromGiftWrap(s, event)
            val rumor = gift.rumor()
            if (rumor.kind().asU16() != CHAT_MESSAGE_KIND) return
            val sender = gift.sender()
            // The rumor is unsigned; make sure its claimed author is the key that signed the seal.
            if (rumor.author().toHex() != sender.toHex()) return
            val payload = PayloadCodec.decode(rumor.content()) ?: return
            lastSeenStore.touch(System.currentTimeMillis() / 1000)
            inbox.trySend(IncomingEnvelope(sender.toHex(), payload, System.currentTimeMillis()))
        } catch (e: Exception) {
            // Not addressed to us, corrupt, or from a client speaking a different dialect: ignore.
            Log.d(TAG, "ignored wrap: ${e.message}")
        }
    }

    private suspend fun teardownClient() {
        val c = client
        client = null
        signer = null
        if (c != null) runCatching { c.disconnect() }
    }

    interface LastSeenStore {
        fun lastSeenSecs(): Long
        fun touch(secs: Long)
    }

    private class PrefsLastSeenStore(context: Context) : LastSeenStore {
        private val prefs = context.applicationContext.getSharedPreferences("relay_transport", Context.MODE_PRIVATE)
        override fun lastSeenSecs(): Long = prefs.getLong("last_seen_secs", 0L)
        override fun touch(secs: Long) {
            if (secs > lastSeenSecs()) prefs.edit().putLong("last_seen_secs", secs).apply()
        }
    }

    companion object {
        private const val TAG = "NostrTransport"
        private val GIFT_WRAP_KIND: UShort = 1059u
        private val CHAT_MESSAGE_KIND: UShort = 14u
        private const val LOOKBACK_SECS = 3L * 24 * 60 * 60
    }
}
