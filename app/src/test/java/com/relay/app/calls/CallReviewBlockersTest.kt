package com.relay.app.calls

import com.relay.app.call.CallOutcome
import com.relay.app.data.conversation.ConversationFormat
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.ui.screens.chat.ChatItem
import com.relay.app.ui.screens.chat.ChatTimeline
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CallReviewBlockersTest {
    @Test fun candidatesWaitForRemoteDescriptionAndStaleGenerationIsDiscarded() {
        val added = mutableListOf<String>()
        val queue = RemoteCandidateQueue<String>(2) { added.add(it) }
        queue.begin(1)
        queue.add(1, listOf("first"))
        assertTrue(added.isEmpty())
        queue.remoteDescriptionSet(1)
        assertEquals(listOf("first"), added)
        queue.begin(2)
        queue.add(2, listOf("second"))
        queue.remoteDescriptionSet(1)
        assertEquals(listOf("first"), added)
        queue.remoteDescriptionSet(2)
        assertEquals(listOf("first", "second"), added)
    }

    @Test fun candidateQueueIsBoundedAndCloseDropsPendingCandidates() {
        val added = mutableListOf<String>()
        val queue = RemoteCandidateQueue<String>(2) { added.add(it) }
        queue.begin(1)
        queue.add(1, listOf("a", "b", "c"))
        queue.remoteDescriptionSet(1)
        assertEquals(listOf("a", "b"), added)
        queue.begin(2)
        queue.add(2, listOf("d"))
        queue.close()
        queue.remoteDescriptionSet(2)
        assertEquals(listOf("a", "b"), added)
    }

    @Test fun secondSignalCannotOvertakeBlockedFirstSignal() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val unblock = CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val serial = RecipientSignalQueue(workers) { recipient: String, wire: String ->
            if (wire == "invite") { entered.complete(Unit); unblock.await() }
            sent.add("$recipient:$wire")
        }
        serial.send("alice", "invite")
        entered.await()
        serial.send("alice", "cancel")
        delay(50)
        assertTrue(sent.isEmpty())
        unblock.complete(Unit)
        serial.flush()
        assertEquals(listOf("alice:invite", "alice:cancel"), sent)
        workers.cancel()
    }

    @Test fun hiddenPreviewCannotExposeCallerName() {
        assertEquals("Relay call", callNotificationName("Alice", true))
        assertEquals("Alice", callNotificationName("Alice", false))
    }

    @Test fun remoteCallLogTextIsNotTrustedButLocalRowIsNote() {
        val id = "0123456789abcdef0123456789abcdef"
        val body = CallRecord.encode(id, true, CallOutcome.MISSED, null, 0, 100)
        val remote = Message(contactId = 1, body = body, type = MessageType.TEXT, isSent = false, msgId = "remote", timestamp = 100)
        val local = remote.copy(msgId = CallRecord.messageId(id), deliveryState = CallRecord.LOCAL_DELIVERY_STATE)
        assertTrue(ChatTimeline.build(listOf(remote), null, 100).any { it is ChatItem.Bubble })
        val forgedId = remote.copy(msgId = CallRecord.messageId(id)) // attacker also guesses the derived id
        assertTrue(ChatTimeline.build(listOf(forgedId), null, 100).none { it is ChatItem.Note })
        assertTrue(ChatTimeline.build(listOf(local), null, 100).any { it is ChatItem.Note })
        assertTrue(ConversationFormat.preview(MessageType.TEXT, body, false).startsWith(CallRecord.PREFIX))
        assertEquals("Missed call", ConversationFormat.preview(MessageType.TEXT, body, false, CallRecord.LOCAL_DELIVERY_STATE))
    }

    @Test fun bluetoothPermissionIsRequestedOnlyOnAndroid12AndLaterWhenMissing() {
        assertFalse(CallAudio.needsBluetoothPermission(30) { false })
        assertTrue(CallAudio.needsBluetoothPermission(31) { false })
        assertFalse(CallAudio.needsBluetoothPermission(31) { true })
    }

    @Test fun failedOpenReleasesEveryAcquiredResourceInReverseOrder() {
        val released = mutableListOf<String>()
        val guard = OpenGuard()
        guard.track("connection") { released.add(it) }
        guard.track("source") { released.add(it) }
        try {
            guard.track<String>("track-factory") { released.add(it) }
            throw IllegalStateException("addTrack failed")
        } catch (e: IllegalStateException) {
            guard.rollback()
        }
        assertEquals(listOf("track-factory", "source", "connection"), released)
        guard.rollback()
        assertEquals(3, released.size)
    }

    @Test fun committedOpenIsNotRolledBackAndBrokenCleanupDoesNotStopTheRest() {
        val released = mutableListOf<String>()
        val kept = OpenGuard()
        kept.track("a") { released.add(it) }
        kept.commit()
        kept.rollback()
        assertTrue(released.isEmpty())
        val broken = OpenGuard()
        broken.track("first") { released.add(it) }
        broken.track("second") { throw IllegalStateException("dispose failed") }
        broken.rollback()
        assertEquals(listOf("first"), released)
    }

    private class FakeTransport(private val gate: CompletableDeferred<Unit>?, private val entered: CompletableDeferred<Unit>) :
        com.relay.app.transport.Transport {
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        override val status = kotlinx.coroutines.flow.MutableStateFlow(com.relay.app.transport.TransportStatus.ONLINE)
        override val incoming = kotlinx.coroutines.flow.emptyFlow<com.relay.app.transport.IncomingEnvelope>()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun reconnect() = Unit
        override suspend fun send(
            recipientPubkeyHex: String,
            payload: com.relay.app.transport.RelayPayload,
            relayHints: List<String>,
            ephemeral: Boolean,
        ): com.relay.app.transport.SendResult {
            if (recipientPubkeyHex == "alice" && !entered.isCompleted && gate != null) { entered.complete(Unit); gate.await() }
            sent.add(recipientPubkeyHex to payload.body)
            return com.relay.app.transport.SendResult.Sent(1)
        }
    }

    @Test fun mailboxSignalPortDoesNotLetCancelOvertakeABlockedInvite() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val transport = FakeTransport(gate, entered)
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = MailboxSignalPort(workers, { transport }, SeqRandom())
        port.send("alice", "invite-wire", com.relay.app.call.SignalMode.LIVE_AND_STORED_URGENT)
        entered.await()
        port.send("alice", "cancel-wire", com.relay.app.call.SignalMode.LIVE)
        port.send("bob", "other-wire", com.relay.app.call.SignalMode.LIVE)
        delay(50)
        assertEquals(listOf("bob" to "other-wire"), transport.sent.toList()) // another recipient is not blocked
        gate.complete(Unit)
        port.flush()
        assertEquals(
            listOf("alice" to "invite-wire", "alice" to "cancel-wire"),
            transport.sent.filter { it.first == "alice" },
        )
        workers.cancel()
    }
}
