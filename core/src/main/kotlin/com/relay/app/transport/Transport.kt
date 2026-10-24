package com.relay.app.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * How bytes reach the other phone. Everything above this interface (UI, repositories, message
 * handling) is transport-agnostic; [com.relay.app.transport.nostr.NostrTransport] is the first
 * implementation. Recipients are identified by their Nostr public key in hex.
 */
interface Transport {

    val status: StateFlow<TransportStatus>

    /**
     * Decrypted, signature-checked messages from any sender. Callers decide whether the sender is a
     * known contact. Backed by a buffered channel (single consumer) rather than a SharedFlow so
     * nothing is dropped if a message arrives before the collector is attached.
     */
    val incoming: Flow<IncomingEnvelope>
    val signals: Flow<IncomingEnvelope> get() = kotlinx.coroutines.flow.emptyFlow()

    suspend fun start()

    suspend fun stop()

    /** Drops the current connection and establishes a fresh one (e.g. after a network change). */
    suspend fun reconnect()

    /**
     * Publishes [payload] to [recipientPubkeyHex]. [relayHints] are extra relays to try besides the
     * configured defaults (e.g. the contact's inbox relays learned at pairing). [ephemeral] marks
     * best-effort signals (typing, presence) that must never be stored for later delivery.
     */
    suspend fun send(
        recipientPubkeyHex: String,
        payload: RelayPayload,
        relayHints: List<String> = emptyList(),
        ephemeral: Boolean = false,
    ): SendResult

    /** Durable send with uploaded ciphertext references. Mailbox implementations must persist and
     * retry the SAME digests with the same delivery id/envelope, passing them to sendBody's media
     * overload before signing the full HTTP body. Legacy implementations fail closed for nonempty
     * references rather than silently losing per-device grants. Empty references preserve send(). */
    suspend fun sendWithMedia(
        recipientPubkeyHex: String,
        payload: RelayPayload,
        mediaDigests: List<String>,
        relayHints: List<String> = emptyList(),
    ): SendResult = if (mediaDigests.isEmpty()) send(recipientPubkeyHex, payload, relayHints, ephemeral = false)
        else SendResult.Failed("This transport does not support media references", retryable = false)
}

enum class TransportStatus {
    STOPPED, CONNECTING, ONLINE, OFFLINE,

    /** Tor is required but not running; nothing is being sent or received until it is (fail closed). */
    WAITING_FOR_TOR,
}

data class IncomingEnvelope(
    val senderPubkeyHex: String,
    val payload: RelayPayload,
    val receivedAtMs: Long,
    /**
     * Called once the message has been handled (stored or deliberately dropped), so a transport
     * that keeps a durable copy until then can let it go. Not called when handling failed or was
     * deferred, so the copy is offered again later.
     */
    val onHandled: (() -> Unit)? = null,
)

sealed class SendResult {
    /** Accepted by at least one relay. */
    data class Sent(val relayCount: Int) : SendResult()

    /** [retryable] false means retrying cannot help (e.g. malformed recipient key). */
    data class Failed(val reason: String, val retryable: Boolean = true) : SendResult()
}
