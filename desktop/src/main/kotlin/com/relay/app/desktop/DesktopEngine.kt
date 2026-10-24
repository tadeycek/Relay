package com.relay.app.desktop

import com.relay.app.messaging.InnerEnvelope
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rust.nostr.sdk.Keys

/**
 * Headless core layer for the desktop client: binds the encrypted store, the inner HPKE envelope,
 * the Nostr identity and a [Transport]. The UI only calls this class, so all message behaviour is
 * unit-testable without a display.
 *
 * After a phone link ([DesktopLinkFlow] → [DesktopStoreLinkImporter]) the identity and HPKE keyset
 * are the phone's, and the transport signs with the enrolled device-auth key. Until then this
 * install is local-only: its own identity, no `device` parameter.
 */
class DesktopEngine(
    val identity: DesktopIdentity,
    val keyMaterial: DesktopKeyMaterial,
    val store: DesktopMessageStore,
    private val transport: Transport,
    private val peerInnerKeys: Map<String, String> = emptyMap(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Link key material and link state; null only in tests that never link. */
    val linkKeys: DesktopLinkKeys? = null,
) : AutoCloseable {

    private val _revision = MutableStateFlow(0L)
    /** Bumped after every stored/updated message so the UI can refresh without polling the DB. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val status: StateFlow<TransportStatus> get() = transport.status

    private var collector: Job? = null

    /** Non-null once this computer completed a phone link. */
    fun linkedDevice(): LinkedDevice? = linkKeys?.linkedDevice()

    fun isLinked(): Boolean = linkedDevice() != null

    fun start() {
        if (collector?.isActive == true) return
        collector = scope.launch {
            transport.start()
            transport.incoming.collect { envelope ->
                runCatching { handle(envelope) }
                    .onFailure { /* keep the connection: a poison packet must not stop delivery */ }
            }
        }
    }

    suspend fun sendText(peerHex: String, text: String): SendResult {
        val recipientInnerKey = peerInnerKeys[peerHex]
        val body = InnerEnvelope.seal(keyMaterial, recipientInnerKey, text)
        val payload = RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), body)
        val result = transport.send(peerHex, payload)
        if (result is SendResult.Sent) {
            store.insertMessage(
                DesktopMessage(
                    id = payload.id,
                    conversationId = peerHex,
                    sender = identity.publicKeyHex(),
                    body = text,
                    ts = payload.ts,
                    outgoing = true,
                ),
            )
            _revision.value++
        }
        return result
    }

    fun messages(peerHex: String): List<DesktopMessage> = store.messages(peerHex)

    fun conversations(): List<DesktopConversation> = store.conversations()

    fun contacts(): List<DesktopContact> = store.contacts()

    fun groups(): List<DesktopGroup> = store.groups()

    /**
     * Builds the link flow for [mailboxUrl]. The flow is pure; the caller drives
     * `begin()`/`step()` (or `await()`) and renders the states.
     */
    fun createLinkFlow(
        mailboxUrl: String,
        linkHttp: LinkHttp = DesktopLinkHttp(mailboxUrl),
        clock: LinkClock = SystemLinkClock,
        codeSource: () -> String = { com.relay.app.linking.LinkCode.generate() },
    ): DesktopLinkFlow {
        val links = linkKeys ?: error("This engine was built without link key material")
        return DesktopLinkFlow(
            http = linkHttp,
            hpke = DesktopLinkHpke { links.loadOrCreate().ephemeralPrivateKeysetJson },
            keys = object : LinkKeyProvider {
                override fun loadOrCreate(deviceLabel: String?): DesktopLinkSession =
                    links.loadOrCreate(deviceLabel ?: DesktopLinkKeys.defaultDeviceLabel())
            },
            importer = DesktopStoreLinkImporter(identity, keyMaterial, store, links, clock),
            clock = clock,
            codeSource = codeSource,
        )
    }

    /** Decrypts one incoming payload and stores it. [IncomingEnvelope.onHandled] fires only after commit. */
    private suspend fun handle(envelope: IncomingEnvelope) {
        val payload = envelope.payload
        val encrypted = SmsMessageParser.parseEncryptedPayload(payload.body) != null
        val text = if (encrypted) {
            InnerEnvelope.open(keyMaterial, payload.body) ?: run {
                // Encrypted to a key we do not hold: drop it, never store ciphertext as a message.
                envelope.onHandled?.invoke()
                return
            }
        } else {
            payload.body
        }
        store.insertMessage(
            DesktopMessage(
                id = payload.id,
                conversationId = envelope.senderPubkeyHex,
                sender = envelope.senderPubkeyHex,
                body = text,
                ts = payload.ts,
                outgoing = false,
            ),
        )
        envelope.onHandled?.invoke()
        _revision.value++
    }

    suspend fun stop() {
        collector?.cancel()
        collector = null
        transport.stop()
    }

    override fun close() {
        runCatching { kotlinx.coroutines.runBlocking { stop() } }
    }

    /** A transport for offline/local-only launches: no socket, no sends. */
    class OfflineTransport : Transport {
        private val stopped = MutableStateFlow(TransportStatus.STOPPED)
        override val status: StateFlow<TransportStatus> = stopped.asStateFlow()
        override val incoming: kotlinx.coroutines.flow.Flow<IncomingEnvelope> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun start() {}
        override suspend fun stop() {}
        override suspend fun reconnect() {}
        override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean): SendResult =
            SendResult.Failed("Offline mode: no mailbox configured", retryable = false)
    }

    companion object {
        /**
         * Builds the production desktop stack for [mailboxUrl] with the given XDG paths.
         *
         * If this computer already completed a link, the transport signs with the enrolled device
         * key and sends `?device=<id>`; the identity is the one the phone exported.
         */
        fun create(
            paths: DesktopPaths,
            mailboxUrl: String,
            peerInnerKeys: Map<String, String> = emptyMap(),
        ): DesktopEngine {
            val secrets = SecretStores.open(paths)
            val linkKeys = DesktopLinkKeys(paths, secrets)
            val identity = DesktopIdentity(paths, secrets, linkKeys = linkKeys)
            val keyMaterial = DesktopKeyMaterial(paths, secrets)
            val store = DesktopMessageStore(paths.databaseFile, secrets)
            val transport: Transport = if (mailboxUrl.isBlank()) {
                OfflineTransport()
            } else {
                DesktopMailboxTransport(
                    baseUrl = mailboxUrl,
                    keys = identity.keys(),
                    signer = identity.signer(),
                    rawStore = store,
                    device = linkKeys.deviceAuth(),
                )
            }
            return DesktopEngine(identity, keyMaterial, store, transport, peerInnerKeys, linkKeys = linkKeys)
        }
    }
}

/**
 * The enrolled device key a linked desktop signs with. [signer] is over the device-auth key, not the
 * identity key: after migration the server refuses the shared identity key for device operations.
 */
data class DesktopDeviceAuth(
    val deviceId: String,
    val signer: com.relay.app.transport.mailbox.NostrSigner,
)

/** The enrolled device, or null before a link. */
fun DesktopLinkKeys.deviceAuth(): DesktopDeviceAuth? {
    val file = load() ?: return null
    if (file.identityPubkey == null) return null
    return runCatching {
        val keys = Keys.parse(file.session.deviceAuthSecretHex)
        DesktopDeviceAuth(file.session.deviceId, DesktopNostrSigner(keys))
    }.getOrNull()
}
