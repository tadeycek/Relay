package com.relay.app.transport

import com.relay.app.transport.mailbox.MailboxProtocol.Outcome
import com.relay.app.transport.mailbox.MailboxRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/**
 * The transition period between public Nostr relays and Relay's own mailbox. Both always receive:
 * a pilot user whose friend sends through the mailbox gets the message even with their own sending
 * switch off (an install that isn't on the pilot is refused and only asks again every 15 minutes).
 * [sendViaMailbox] picks the outgoing route: the mailbox first, falling back to Nostr only for
 * recipients who aren't on it (or if this install isn't). Ephemeral signals (typing, presence)
 * always use Nostr: the mailbox stores everything it accepts, and a heartbeat every 20 s per contact
 * would exhaust its daily write allowance for no benefit.
 */
class HybridTransport(
    private val nostr: Transport,
    private val mailbox: MailboxRoute,
    private val sendViaMailbox: () -> Boolean,
) : Transport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Recipients the mailbox said aren't on it, and when; asked again after [NOT_ON_MAILBOX_TTL_MS]. */
    private val notOnMailbox = ConcurrentHashMap<String, Long>()

    override val status: StateFlow<TransportStatus> =
        combine(nostr.status, mailbox.status, HybridStatus::combine).stateIn(scope, SharingStarted.Eagerly, TransportStatus.STOPPED)

    override val incoming: Flow<IncomingEnvelope> = merge(nostr.incoming, mailbox.incoming)

    override suspend fun start() {
        nostr.start()
        mailbox.start()
    }

    override suspend fun stop() {
        nostr.stop()
        mailbox.stop()
    }

    override suspend fun reconnect() {
        nostr.reconnect()
        mailbox.reconnect()
        notOnMailbox.clear()
    }

    override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean): SendResult {
        val now = System.currentTimeMillis()
        val skip = notOnMailbox[recipientPubkeyHex]?.let { now - it < NOT_ON_MAILBOX_TTL_MS } == true
        if (!ephemeral && !skip && sendViaMailbox()) {
            when (val outcome = mailbox.deliver(recipientPubkeyHex, payload)) {
                Outcome.Stored -> return SendResult.Sent(1)
                Outcome.NotOnMailbox -> notOnMailbox[recipientPubkeyHex] = now
                is Outcome.Retry -> return SendResult.Failed(outcome.reason, retryable = true)
                is Outcome.Rejected -> return SendResult.Failed(outcome.reason, retryable = false)
            }
        }
        return nostr.send(recipientPubkeyHex, payload, relayHints, ephemeral)
    }

    private companion object {
        const val NOT_ON_MAILBOX_TTL_MS = 60L * 60 * 1000
    }
}

/** How the two routes add up to one status. Pure, so unit-tested. */
object HybridStatus {
    fun combine(nostr: TransportStatus, mailbox: TransportStatus): TransportStatus = when {
        nostr == TransportStatus.ONLINE || mailbox == TransportStatus.ONLINE -> TransportStatus.ONLINE
        nostr == TransportStatus.CONNECTING || mailbox == TransportStatus.CONNECTING -> TransportStatus.CONNECTING
        nostr == TransportStatus.WAITING_FOR_TOR -> TransportStatus.WAITING_FOR_TOR
        nostr == TransportStatus.OFFLINE || mailbox == TransportStatus.OFFLINE -> TransportStatus.OFFLINE
        else -> TransportStatus.STOPPED
    }
}
