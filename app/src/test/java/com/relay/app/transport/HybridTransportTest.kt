package com.relay.app.transport

import com.relay.app.transport.mailbox.MailboxProtocol.Outcome
import com.relay.app.transport.mailbox.MailboxRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridTransportTest {

    private open class FakeTransport : Transport {
        val sent = mutableListOf<String>()
        var started = 0
        var reconnected = 0
        override val status: StateFlow<TransportStatus> = MutableStateFlow(TransportStatus.ONLINE)
        override val incoming: Flow<IncomingEnvelope> = emptyFlow()
        override suspend fun start() { started++ }
        override suspend fun stop() {}
        override suspend fun reconnect() { reconnected++ }
        override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean): SendResult {
            sent += recipientPubkeyHex
            return SendResult.Sent(1)
        }
    }

    private class FakeMailbox(var outcome: Outcome = Outcome.Stored) : FakeTransport(), MailboxRoute {
        val delivered = mutableListOf<String>()
        override suspend fun deliver(recipient: String, payload: RelayPayload): Outcome {
            delivered += recipient
            return outcome
        }
    }

    private val payload = RelayPayload("3f2b6c1e-9a7d-4c5b-8e2f-1a2b3c4d5e6f", 1, "hi")
    private val alice = "a".repeat(64)
    private val nostr = FakeTransport()
    private val mailbox = FakeMailbox()
    private var sendViaMailbox = true
    private val hybrid = HybridTransport(nostr, mailbox) { sendViaMailbox }

    @Test
    fun sendsThroughTheMailboxWhenItTakesTheMessage() = runTest {
        assertEquals(SendResult.Sent(1), hybrid.send(alice, payload))
        assertEquals(listOf(alice), mailbox.delivered)
        assertTrue(nostr.sent.isEmpty())
    }

    @Test
    fun fallsBackToRelaysForRecipientsNotOnTheMailboxAndRemembersThem() = runTest {
        mailbox.outcome = Outcome.NotOnMailbox
        hybrid.send(alice, payload)
        hybrid.send(alice, payload)
        assertEquals("asked the mailbox only once", listOf(alice), mailbox.delivered)
        assertEquals(listOf(alice, alice), nostr.sent)
        // A reconnect (or an hour) forgets it, e.g. after the recipient joined the pilot.
        hybrid.reconnect()
        mailbox.outcome = Outcome.Stored
        hybrid.send(alice, payload)
        assertEquals(listOf(alice, alice), mailbox.delivered)
    }

    @Test
    fun aBusyMailboxIsRetriedLaterNotBypassed() = runTest {
        // Falling back here could deliver twice (the mailbox may have stored it) and would hide outages.
        mailbox.outcome = Outcome.Retry("busy")
        assertEquals(SendResult.Failed("busy", retryable = true), hybrid.send(alice, payload))
        mailbox.outcome = Outcome.Rejected("too large")
        assertEquals(SendResult.Failed("too large", retryable = false), hybrid.send(alice, payload))
        assertTrue(nostr.sent.isEmpty())
    }

    @Test
    fun ephemeralSignalsNeverTouchTheMailbox() = runTest {
        hybrid.send(alice, payload, ephemeral = true)
        assertTrue(mailbox.delivered.isEmpty())
        assertEquals(listOf(alice), nostr.sent)
    }

    @Test
    fun theSwitchOnlyChoosesTheSendingRoute() = runTest {
        sendViaMailbox = false
        hybrid.send(alice, payload)
        assertTrue(mailbox.delivered.isEmpty())
        assertEquals(listOf(alice), nostr.sent)
        // Receiving from the mailbox continues, so nothing sent there by others is stranded.
        hybrid.start()
        hybrid.reconnect()
        assertEquals(1, mailbox.started)
        assertEquals(1, mailbox.reconnected)
    }
}
