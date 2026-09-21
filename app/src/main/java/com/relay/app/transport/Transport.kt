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

    suspend fun start()

    suspend fun stop()

    /**
     * Publishes [payload] to [recipientPubkeyHex]. [relayHints] are extra relays to try besides the
     * configured defaults (e.g. the contact's inbox relays learned at pairing).
     */
    suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String> = emptyList()): SendResult
}

enum class TransportStatus { STOPPED, CONNECTING, ONLINE, OFFLINE }

data class IncomingEnvelope(
    val senderPubkeyHex: String,
    val payload: RelayPayload,
    val receivedAtMs: Long,
)

sealed class SendResult {
    /** Accepted by at least one relay. */
    data class Sent(val relayCount: Int) : SendResult()

    /** [retryable] false means retrying cannot help (e.g. malformed recipient key). */
    data class Failed(val reason: String, val retryable: Boolean = true) : SendResult()
}
