package com.relay.app.desktop

import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopEngineTest {

    @Test fun `encrypted group announcement reaches the group controller and never becomes chat`() = runBlocking {
        val paths = DesktopPaths(tmp.newFolder("call-config").toPath(), tmp.newFolder("call-data").toPath())
        val secrets = FileSecretStore(paths.secretFile)
        val identity = DesktopIdentity(paths, secrets)
        val keys = DesktopKeyMaterial(paths, secrets)
        val store = DesktopMessageStore(paths.databaseFile, secrets)
        val transport = FakeTransport()
        val self = identity.publicKeyHex()
        val group = GroupCallHarness(self)
        group.group = group.stored(keys = listOf(self, GroupCallHarness.B), owner = self)
        store.saveGroupState(group.roster())
        val calls = DesktopGroupCalls(self, "desk-1", store::storedGroup, store::storedGroup, store, group.api,
            announce = { _, _ -> SendResult.Sent(1) }, online = { true }, makeMedia = { group.FakeMedia() }, nameOf = { it }, clock = { group.now }, timers = group.timers)
        val engine = DesktopEngine(identity, keys, store, transport, groupCalls = calls)
        try {
            engine.start()
            val start = group.startBy(GroupCallHarness.B)
            val encrypted = com.relay.app.messaging.InnerEnvelope.seal(keys, keys.publicKeyBase64(), group.frame(start))
            transport.deliver(GroupCallHarness.B, RelayPayload("group-call-test", group.now, encrypted))
            withTimeout(5_000) { while (calls.state.value.banners[group.group!!.conversationId] == null) kotlinx.coroutines.delay(10) }
            assertEquals(start.room, engine.groupCallState.value.banners[group.group!!.conversationId]?.room)
            assertTrue(store.messages(GroupCallHarness.B).isEmpty())
            assertTrue(store.messages(group.group!!.conversationId).isEmpty())
        } finally { engine.close(); store.close(); group.close() }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    /** In-memory transport that echoes a received payload straight into the engine's collector. */
    private class FakeTransport : Transport {
        private val _status = MutableStateFlow(TransportStatus.STOPPED)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()
        private val inbox = Channel<IncomingEnvelope>(Channel.UNLIMITED)
        override val incoming: Flow<IncomingEnvelope> = inbox.receiveAsFlow()
        var lastSent: RelayPayload? = null
        var sentTo: String? = null

        override suspend fun start() {
            _status.value = TransportStatus.ONLINE
        }

        override suspend fun stop() {
            _status.value = TransportStatus.STOPPED
        }

        override suspend fun reconnect() = Unit

        override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean): SendResult {
            lastSent = payload
            sentTo = recipientPubkeyHex
            return SendResult.Sent(1)
        }

        suspend fun deliver(sender: String, payload: RelayPayload) {
            inbox.send(IncomingEnvelope(sender, payload, System.currentTimeMillis()))
        }
    }

    @Test
    fun `sendText seals, sends and stores the outgoing message`() = runBlocking {
        val paths = DesktopPaths(tmp.newFolder("config").toPath(), tmp.newFolder("data").toPath())
        val secrets = FileSecretStore(paths.secretFile)
        val identity = DesktopIdentity(paths, secrets)
        val keyMaterial = DesktopKeyMaterial(paths, secrets)
        val store = DesktopMessageStore(paths.databaseFile, secrets)
        val transport = FakeTransport()
        val peer = "a".repeat(64)
        val peerPaths = DesktopPaths(tmp.newFolder("peer-config").toPath(), tmp.newFolder("peer-data").toPath())
        val peerMaterial = DesktopKeyMaterial(peerPaths, FileSecretStore(peerPaths.secretFile))
        val engine = DesktopEngine(
            identity,
            keyMaterial,
            store,
            transport,
            peerInnerKeys = mapOf(peer to peerMaterial.publicKeyBase64()),
        )

        val result = engine.sendText(peer, "hello from the engine")
        assertTrue(result is SendResult.Sent)
        assertEquals(peer, transport.sentTo)
        assertTrue(transport.lastSent!!.body.startsWith("TYPE:ENC|CT:"))
        assertEquals("hello from the engine", com.relay.app.messaging.InnerEnvelope.open(peerMaterial, transport.lastSent!!.body))
        assertEquals("hello from the engine", engine.messages(peer).single().body)
        assertTrue(engine.messages(peer).single().outgoing)

        engine.close()
        store.close()
    }

    @Test
    fun `incoming payload is opened, stored once and marked handled`() = runBlocking {
        val paths = DesktopPaths(tmp.newFolder("config").toPath(), tmp.newFolder("data").toPath())
        val secrets = FileSecretStore(paths.secretFile)
        val identity = DesktopIdentity(paths, secrets)
        val keyMaterial = DesktopKeyMaterial(paths, secrets)
        val store = DesktopMessageStore(paths.databaseFile, secrets)
        val transport = FakeTransport()
        val engine = DesktopEngine(identity, keyMaterial, store, transport)
        engine.start()

        val peer = "b".repeat(64)
        // Peer seals to this desktop's HPKE public key.
        val body = com.relay.app.messaging.InnerEnvelope.seal(keyMaterial, keyMaterial.publicKeyBase64(), "incoming text")
        val payload = RelayPayload("incoming-1", 5_000L, body)
        val handled = java.util.concurrent.atomic.AtomicBoolean(false)
        transport.deliver(peer, payload)
        withTimeout(5_000) {
            while (engine.messages(peer).isEmpty()) kotlinx.coroutines.delay(50)
        }
        assertEquals("incoming text", engine.messages(peer).single().body)
        assertTrue(!engine.messages(peer).single().outgoing)

        // Duplicate delivery must not insert twice.
        transport.deliver(peer, payload)
        kotlinx.coroutines.delay(300)
        assertEquals(1, engine.messages(peer).size)

        engine.close()
        store.close()
    }
}
