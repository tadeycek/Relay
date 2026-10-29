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
import kotlinx.coroutines.isActive
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
    /** Sent-copy sync (WP05-AC2). Both null before a link or without a mailbox; then nothing is published or applied. */
    private val syncPublisher: SentSyncPublisher? = null,
    private val syncApplier: SentSyncApplier? = null,
    syncIntervalMs: Long = 15_000,
    val files: DesktopFiles? = null,
    /** WP08 photos and videos. Receiving is always on when set; sending is gated inside the controller. */
    val media: DesktopMedia? = null,
    /** WP14 1:1 calls. Null without a linked device and a mailbox; incoming call packets are then dropped, never shown as chat. */
    val calls: DesktopCallService? = null,
    /** WP16 group calls, only available with an enrolled device and mailbox. */
    val groupCalls: DesktopGroupCalls? = null,
    private val callsLinked: () -> Boolean = { linkKeys?.linkedDevice() != null },
) : AutoCloseable {

    private val _revision = MutableStateFlow(0L)

    /** Group traffic and profiles use the same seal+send path as 1:1 text. */
    private val wireSender = GroupSender { peer, inner, relays, body ->
        val key = inner ?: recipientKey(peer)
        val sealed = key?.let { InnerEnvelope.seal(keyMaterial, it, body) }
        if (sealed == null) SendResult.Failed("No encryption key for that contact", retryable = false)
        else transport.send(peer, RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), sealed), relays)
    }

    val profiles = DesktopProfiles(
        store = store,
        ownPubkey = identity.publicKeyHex(),
        nameProvider = { store.profile(identity.publicKeyHex())?.name ?: "This computer" },
        sender = wireSender,
        onReply = { peer, body -> scope.launch { wireSender.send(peer, recipientKey(peer), emptyList(), body) } },
    )

    val groups = DesktopGroups(
        store = store,
        ownPubkey = identity.publicKeyHex(),
        ownName = { profiles.ownName() },
        ownInner = { keyMaterial.publicKeyBase64() },
        sender = wireSender,
    )
    /** Bumped after every stored/updated message so the UI can refresh without polling the DB. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val status: StateFlow<TransportStatus> get() = transport.status

    private var collector: Job? = null
    init { media?.onChange = { _revision.value++ }; calls?.onChange = { _revision.value++ }; groupCalls?.onChange = { _revision.value++ } }

    private val idleGroupCall = MutableStateFlow(DesktopGroupCallUi()).asStateFlow()
    val groupCallState: StateFlow<DesktopGroupCallUi> get() = groupCalls?.state ?: idleGroupCall
    fun startGroupCall(conversationId: String): String? = groupCalls?.start(conversationId) ?: "Link this computer to your phone before starting a group call."
    fun joinGroupCall(conversationId: String): String? = groupCalls?.join(conversationId) ?: "Link this computer to your phone before joining a group call."
    fun leaveGroupCall() { groupCalls?.leave() }
    fun endGroupCall() { groupCalls?.endCall() }
    fun muteGroupCall(muted: Boolean) { groupCalls?.setMuted(muted) }
    fun deafenGroupCall(deafened: Boolean) { groupCalls?.setDeafened(deafened) }
    fun groupCallVolume(pid: String, volume: Float) { groupCalls?.setVolume(pid, volume) }

    private val idleCall = MutableStateFlow(DesktopCallUi()).asStateFlow()
    /** What the call UI renders. The host behind it lives with the engine, not with any window. */
    val callState: StateFlow<DesktopCallUi> get() = calls?.state ?: idleCall

    /** Direct conversation with a QR-verified, non-blocked, non-deleted contact. Independent of link/online state, which [startCall] reports honestly. */
    fun canCall(conversationId: String): Boolean =
        !isGroupConversation(conversationId) && store.contacts().any { it.nostrPubkey == conversationId && DesktopCallEligibility.isEligible(it) }

    /** Null = the call started ringing; otherwise the reason it was refused. Blocking: call off the UI thread. */
    fun startCall(peer: String): String? {
        DesktopCallGuard.startBlock(callsLinked(), calls != null, status.value, canCall(peer), isGroupConversation(peer))?.let { return it }
        return calls!!.start(peer)
    }
    fun acceptCall() { calls?.accept() }
    fun declineCall() { calls?.decline() }
    fun hangUpCall() { calls?.hangUp() }
    fun muteCall(muted: Boolean) { calls?.setMuted(muted) }
    private var fileWorker: Job? = null

    private val sync: DesktopSyncService? =
        if (syncPublisher != null && syncApplier != null) {
            DesktopSyncService(syncPublisher, syncApplier, onApplied = {
                _revision.value++
                groupCalls?.let { calls -> store.storedGroups().forEach(calls::onRoster) }
            }, intervalMs = syncIntervalMs, scope = scope)
        } else {
            null
        }

    /** The background sync, exposed for status and tests. */
    fun syncService(): DesktopSyncService? = sync

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
        runCatching { files?.sweep() }
        runCatching { media?.sweep(); media?.purgeExpired() }
        sync?.start()
        fileWorker = scope.launch { while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            files?.let { _ -> store.fileRecords().filter { it.state !in listOf("COMPLETE", "CANCELLED", "DELETED", "UNSUPPORTED") }.forEach { retryFile(it.id) } }
            media?.let { m ->
                runCatching { m.purgeExpired() }
                store.mediaRecords().filter { it.state !in MEDIA_SETTLED && !(it.state == "FAILED" && it.permanent) }.forEach { retryMedia(it.id) }
            }
            kotlinx.coroutines.delay(15_000)
        } }
    }

    suspend fun sendText(peerHex: String, text: String): SendResult {
        if (isGroupConversation(peerHex)) {
            val report = groups.send(peerHex, text)
            _revision.value++
            return if (report.failed == 0) SendResult.Sent(report.sent.coerceAtLeast(1))
            else SendResult.Failed(report.firstError ?: "Some members could not be reached", retryable = true)
        }
        val recipientInnerKey = recipientKey(peerHex)
        val body = InnerEnvelope.seal(keyMaterial, recipientInnerKey, text)
        val payload = RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), body)
        val result = transport.send(peerHex, payload)
        if (result is SendResult.Sent) {
            val row = DesktopMessage(
                id = payload.id,
                conversationId = peerHex,
                sender = identity.publicKeyHex(),
                body = text,
                ts = payload.ts,
                outgoing = true,
            )
            if (syncPublisher != null) {
                // The row and its sync copy commit together. The copy carries the plain body, sealed to
                // OUR OWN key by the publisher, not the contact-encrypted TYPE:ENC one the other device
                // cannot open.
                // A failed contact send never reaches this branch, so it never produces a copy.
                syncPublisher.recordSent(row, peerHex, RelayPayload(payload.id, payload.ts, text))
                sync?.kick()
            } else {
                store.insertMessage(row)
            }
            _revision.value++
        }
        return result
    }

    private fun recipientKey(peer: String): String? = store.contacts().firstOrNull { it.nostrPubkey == peer && it.deletedAt == null }?.publicKey ?: peerInnerKeys[peer]

    suspend fun sendFile(peer: String, source: java.nio.file.Path): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val controller = files ?: error("File transport unavailable")
        require(!recipientKey(peer).isNullOrBlank()) { "Import the contact encryption key from your phone before sending files" }
        val record = controller.prepare(peer, source)
        _revision.value++
        retryFile(record.id)
        record.id
    }

    suspend fun retryFile(id: String) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        files?.run(id, send = { r, refs ->
            val key = recipientKey(r.peer) ?: return@run SendResult.Failed("Contact encryption key missing", false)
            val sealed = InnerEnvelope.seal(keyMaterial, key, r.body)
            if (sealed == null) SendResult.Failed("Cannot encrypt file reference", false)
            else transport.sendWithMedia(r.peer, RelayPayload(r.id, r.ts, sealed), refs)
        }, sentCopy = { r ->
            if (syncPublisher != null) {
                val manifest = com.relay.app.files.FileManifest.fromAuthenticatedPayload(RelayPayload(r.id, r.ts, r.body)) { true }!!
                store.queueFileSync(r.id, syncPublisher.buildWires(r.peer, RelayPayload(r.id, r.ts, r.body)), manifest.body.parts.map { it.sha256Hex })
                syncPublisher.flush()
            } else if (linkedDevice() == null) store.saveFile((store.fileRecord(id) ?: r).copy(syncPublished = true))
        })
        _revision.value++
    }

    /** Picks a photo/video, encrypts it privately, shows the bubble, then uploads and sends in the background of this call. */
    suspend fun sendMedia(peer: String, source: java.nio.file.Path, caption: String = ""): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val controller = media ?: error("Media transport unavailable")
        require(!recipientKey(peer).isNullOrBlank()) { "Import the contact encryption key from your phone before sending photos" }
        val record = controller.prepare(peer, source, caption)
        _revision.value++
        retryMedia(record.id)
        record.id
    }

    /** Runs or resumes one photo/video transfer (download, upload, send, SENT grant, confirm). */
    suspend fun retryMedia(id: String) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        media?.run(id, send = { r, refs ->
            val key = recipientKey(r.peer) ?: return@run SendResult.Failed("Contact encryption key missing", false)
            val sealed = InnerEnvelope.seal(keyMaterial, key, r.body)
            if (sealed == null) SendResult.Failed("Cannot encrypt media reference", false)
            else transport.sendWithMedia(r.peer, RelayPayload(r.id, r.ts, sealed), refs)
        }, sentCopy = { r ->
            if (syncPublisher != null) {
                store.queueMediaSync(r.id, syncPublisher.buildWires(r.peer, RelayPayload(r.id, r.ts, r.body)), listOf(
                    com.relay.app.media.MediaBody.parse(r.body)!!.sha256Hex))
                syncPublisher.flush()
            } else if (linkedDevice() == null) store.mediaRecord(r.id)?.let { store.saveMedia(it.copy(syncPublished = true)) }
        })
        _revision.value++
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
        // Group rosters, group posts, group avatars and profile controls are never chat rows.
        if (groups.handle(envelope.senderPubkeyHex, text)) {
            if (com.relay.app.groups.GroupProtocol.parse(text) is com.relay.app.groups.GroupProtocol.Sync) {
                groupCalls?.let { calls -> store.storedGroups().forEach(calls::onRoster) }
            }
            envelope.onHandled?.invoke(); _revision.value++; return
        }
        // The sender is the opened seal's authenticated pubkey, never a caller supplied in the call body.
        if (GroupCallWire.isGroupOp(text)) {
            if (encrypted) groupCalls?.ingress(envelope.senderPubkeyHex, text)
            envelope.onHandled?.invoke(); _revision.value++; return
        }
        // Malformed call controls are never chat messages either.
        if (groups.isAvatarControl(text)) {
            groups.handleAvatar(envelope.senderPubkeyHex, text)
            runCatching { groups.answerAvatarRequest(envelope.senderPubkeyHex, text) }
            envelope.onHandled?.invoke(); _revision.value++; return
        }
        if (profiles.handle(envelope.senderPubkeyHex, text)) {
            envelope.onHandled?.invoke(); _revision.value++; return
        }
        if (text.startsWith("TYPE:FILE|")) {
            if (files?.accept(DesktopMessage(payload.id, envelope.senderPubkeyHex, envelope.senderPubkeyHex, text, payload.ts, false)) == true) {
                envelope.onHandled?.invoke(); _revision.value++; scope.launch { retryFile(payload.id) }; return
            }
        }
        if (text.startsWith("TYPE:MEDIA|") && media != null) {
            if (media.accept(DesktopMessage(payload.id, envelope.senderPubkeyHex, envelope.senderPubkeyHex, text, payload.ts, false))) {
                envelope.onHandled?.invoke(); _revision.value++; scope.launch { retryMedia(payload.id) }; return
            }
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
        // Calls first: hang up, flush the hang-up signal, cancel TURN/timers, close the peer. Only then the transport.
        groupCalls?.let { service -> runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { service.close() } } }
        calls?.let { service -> runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { service.close() } } }
        fileWorker?.cancel(); fileWorker = null
        sync?.stop()
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
        private val MEDIA_SETTLED = setOf("COMPLETE", "CANCELLED", "DELETED", "UNSUPPORTED")

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
            enableFileSendForTests: Boolean = false,
            enableMediaSend: Boolean = false,
            /** Test/diagnostic seam: replaces the mailbox media client. */
            mediaTransport: DesktopMediaTransport? = null,
        ): DesktopEngine {
            val secrets = SecretStores.open(paths)
            val linkKeys = DesktopLinkKeys(paths, secrets)
            val identity = DesktopIdentity(paths, secrets, linkKeys = linkKeys)
            val keyMaterial = DesktopKeyMaterial(paths, secrets)
            val store = DesktopMessageStore(paths.databaseFile, secrets)
            val mailbox = if (mailboxUrl.isBlank()) null else DesktopMailboxTransport(
                baseUrl = mailboxUrl,
                keys = identity.keys(),
                signer = identity.signer(),
                rawStore = store,
                device = linkKeys.deviceAuth(),
            )
            val transport: Transport = mailbox ?: OfflineTransport()
            val device = linkKeys.deviceAuth()
            var publisher: SentSyncPublisher? = null
            var applier: SentSyncApplier? = null
            if (device != null && mailboxUrl.isNotBlank()) {
                val http = DesktopSyncHttp(mailboxUrl, device.signer, device.deviceId)
                publisher = SentSyncPublisher(
                    device.deviceId, store, http,
                    sealToSelf = { InnerEnvelope.seal(keyMaterial, keyMaterial.publicKeyBase64(), it) },
                )
                applier = SentSyncApplier(
                    ownDeviceId = device.deviceId,
                    ownPubkeyHex = identity.publicKeyHex(),
                    http = http,
                    store = store,
                    openBody = { InnerEnvelope.open(keyMaterial, it) },
                    knownConversation = { store.conversation(it) != null },
                )
            }
            // Calls exist only for a linked device on a real mailbox: signing and the TURN fetch need the enrolled key.
            val calls = if (mailbox != null && device != null) DesktopCallService(
                selfPubkey = identity.publicKeyHex(),
                deviceId = device.deviceId,
                link = mailbox,
                isAllowedPeer = DesktopCallEligibility.senderFilter(identity.publicKeyHex()) { store.contacts() },
                record = { store.recordCall(it) },
                nameOf = { key -> store.profile(key)?.name?.takeIf { it.isNotBlank() } ?: store.contacts().firstOrNull { it.nostrPubkey == key }?.name ?: (key.take(8) + "…") },
            ) else null
            val groupIce = mailbox?.let { DesktopGroupIce(it) }
            val groupCalls = if (mailbox != null && device != null) DesktopGroupCalls(
                selfPubkey = identity.publicKeyHex(), deviceId = device.deviceId,
                groupByConversation = store::storedGroup, groupBySharedId = store::storedGroup,
                states = store, api = DesktopGroupCallHttp(mailboxUrl, device.signer, device.deviceId),
                announce = { peer, body ->
                    val announcement = runCatching { org.json.JSONObject(GroupCallWire.unframe(body)!!) }.getOrNull()
                    val roster = announcement?.let { store.storedGroup(it.optString("owner"), it.optString("group")) }
                    val member = roster?.takeIf { !it.hidden && it.members.any { m -> m.key == identity.publicKeyHex() } }
                        ?.members?.firstOrNull { it.key == peer }
                    val inner = if (member == null) null else member.inner ?: store.contacts().firstOrNull { it.nostrPubkey == peer && it.deletedAt == null }?.publicKey
                    val sealed = inner?.let { InnerEnvelope.seal(keyMaterial, it, body) }
                    if (sealed == null) SendResult.Failed("Group member encryption key unavailable", false)
                    else transport.send(peer, RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), sealed), member?.relays ?: emptyList())
                },
                online = { transport.status.value == TransportStatus.ONLINE },
                makeMedia = { DesktopSfuPeer(requireE2ee = true, requireTurn = true, iceServers = { groupIce?.get().orEmpty() }) },
                signRoster = { digest -> runCatching { identity.keys().signSchnorr(digest) }.getOrNull()?.let { hex ->
                    if (hex.length == 128) ByteArray(64) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } else null
                } },
                nameOf = { key -> store.contacts().firstOrNull { it.nostrPubkey == key }?.name ?: key.take(8) + "…" },
            ) else null
            return DesktopEngine(
                identity, keyMaterial, store, transport, peerInnerKeys,
                calls = calls, groupCalls = groupCalls,
                linkKeys = linkKeys, syncPublisher = publisher, syncApplier = applier,
                files = if (mailboxUrl.startsWith("https://")) DesktopFiles(paths.databaseFile.parent.resolve("files"), store,
                    DesktopFileMediaClient(com.relay.app.transport.mailbox.MailboxMedia.Client(
                        com.relay.app.transport.mailbox.MailboxMedia.RequestContext(device?.signer ?: identity.signer(), device?.deviceId), mailboxUrl)),
                    identity.publicKeyHex(), sendEnabled = enableFileSendForTests) else null,
                media = DesktopMedia(paths.databaseFile.parent.resolve("media"), store,
                    mediaTransport ?: if (mailboxUrl.startsWith("https://")) DesktopMediaClient(com.relay.app.transport.mailbox.MailboxMedia.Client(
                        com.relay.app.transport.mailbox.MailboxMedia.RequestContext(device?.signer ?: identity.signer(), device?.deviceId), mailboxUrl))
                    else OfflineMediaTransport,
                    identity.publicKeyHex(), sendEnabled = enableMediaSend),
            )
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
