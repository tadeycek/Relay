package com.relay.app.desktop

import com.relay.app.call.CallOutcome
import com.relay.app.linking.LinkBundle
import com.relay.app.linking.LinkContact
import com.relay.app.linking.LinkIdentity
import com.relay.app.linking.LinkSettings
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** Engine wiring: eligibility, honest refusals, call rows in the chat state, and shutdown order. */
class DesktopCallEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val events = CopyOnWriteArrayList<String>()
    private val closers = mutableListOf<AutoCloseable>()
    @After fun after() { closers.reversed().forEach { runCatching { it.close() } } }

    private inner class RecordingTransport(initial: TransportStatus) : Transport, DesktopCallLink {
        val st = MutableStateFlow(initial)
        override val status: StateFlow<TransportStatus> = st.asStateFlow()
        override val incoming: Flow<IncomingEnvelope> = emptyFlow()
        override suspend fun start() { st.value = TransportStatus.ONLINE }
        override suspend fun stop() { events += "transport.stop"; st.value = TransportStatus.STOPPED }
        override suspend fun reconnect() = Unit
        override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean): SendResult = SendResult.Sent(1)
        override var callRouter: DesktopCallRouter? = null
        override fun isOnline() = st.value == TransportStatus.ONLINE
        override suspend fun signal(to: String, payload: RelayPayload): SendResult { events += "signal"; return SendResult.Sent(1) }
        override suspend fun deliverUrgent(to: String, payload: RelayPayload): SendResult { events += "urgent"; return SendResult.Sent(1) }
        override fun fetchIce(reply: (Int, String?) -> Unit): AutoCloseable = AutoCloseable { }
    }

    private val peer = "b".repeat(64)
    private class Fixture(val engine: DesktopEngine, val store: DesktopMessageStore, val transport: Any, val calls: DesktopCallService?)

    private fun fixture(status: TransportStatus, linked: Boolean, withCalls: Boolean = true, contact: LinkContact? = null): Fixture {
        val paths = DesktopPaths(tmp.newFolder().toPath(), tmp.newFolder().toPath())
        val secrets = FileSecretStore(paths.secretFile)
        val identity = DesktopIdentity(paths, secrets)
        val store = DesktopMessageStore(paths.databaseFile, secrets).also { closers += AutoCloseable { it.close() } }
        if (contact != null) store.importLinkBundle(LinkBundle(createdAt = 1, identity = LinkIdentity("0".repeat(64), identity.publicKeyHex(), "primary", "x", "x"),
            contacts = listOf(contact), groups = emptyList(), settings = LinkSettings()), 1)
        val transport = RecordingTransport(status)
        val calls = if (withCalls) DesktopCallService(
            identity.publicKeyHex(), "d".repeat(32), transport,
            DesktopCallEligibility.senderFilter(identity.publicKeyHex()) { store.contacts() }, { store.recordCall(it) }, { peer.take(4) },
        ) else null
        val engine = DesktopEngine(identity, DesktopKeyMaterial(paths, secrets), store, transport, calls = calls, callsLinked = { linked })
        closers += AutoCloseable { engine.close() }
        return Fixture(engine, store, transport, calls)
    }
    private fun verified(qr: Boolean = true, trust: String = "trusted", deleted: Long? = null) =
        LinkContact(name = "Bea", phone = "", hasRelay = true, trustLevel = trust, nostrPubkey = peer, qrVerified = qr, deletedAt = deleted)

    @Test fun callIsRefusedWhileUnlinked() {
        val f = fixture(TransportStatus.ONLINE, linked = false, contact = verified())
        assertTrue(f.engine.startCall(peer)!!.contains("Link this computer"))
        assertEquals(DesktopCallStage.IDLE, f.engine.callState.value.stage)
    }

    @Test fun callIsRefusedWhileOffline() {
        val f = fixture(TransportStatus.OFFLINE, linked = true, contact = verified())
        assertTrue(f.engine.startCall(peer)!!.contains("offline", ignoreCase = true))
    }

    @Test fun callIsRefusedWithoutAMailbox() {
        val f = fixture(TransportStatus.STOPPED, linked = true, withCalls = false, contact = verified())
        assertNotNull(f.engine.startCall(peer))
    }

    @Test fun callIsRefusedForAnUnverifiedContact() {
        val f = fixture(TransportStatus.ONLINE, linked = true, contact = verified(qr = false))
        assertTrue(f.engine.startCall(peer)!!.contains("verified"))
    }

    @Test fun onlineLinkedVerifiedContactStartsRingingOut() {
        val f = fixture(TransportStatus.ONLINE, linked = true, contact = verified())
        assertNull(f.engine.startCall(peer))
        assertEquals(DesktopCallStage.RINGING_OUT, f.engine.callState.value.stage)
    }

    @Test fun canCallIsTrueOnlyForEligibleDirectContacts() {
        assertTrue(fixture(TransportStatus.ONLINE, true, contact = verified()).engine.canCall(peer))
        assertFalse(fixture(TransportStatus.ONLINE, true, contact = verified(qr = false)).engine.canCall(peer))
        assertFalse(fixture(TransportStatus.ONLINE, true, contact = verified(trust = "BLOCKED")).engine.canCall(peer))
        assertFalse(fixture(TransportStatus.ONLINE, true, contact = verified(deleted = 5)).engine.canCall(peer))
        assertFalse(fixture(TransportStatus.ONLINE, true, contact = verified()).engine.canCall("group:abc:1"))
        assertFalse(fixture(TransportStatus.ONLINE, true).engine.canCall("c".repeat(64)))
    }

    @Test fun callRecordShowsInTheThreadAndRemoteLookalikeTextDoesNot() {
        val f = fixture(TransportStatus.ONLINE, linked = true, contact = verified())
        f.store.ensureConversation(peer, "Bea")
        f.store.insertMessage(DesktopMessage("m", peer, peer, "TYPE:CALLLOG|J:forged", 100L, false))
        f.store.recordCall(DesktopCallRecord("0123456789abcdef0123456789abcdef", peer, true, CallOutcome.MISSED, null, 0, 200L))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { closers += AutoCloseable { it.cancel() } }
        val chat = DesktopChat(f.engine, scope, io = Dispatchers.Unconfined)
        chat.start()
        chat.select(peer)
        val end = System.currentTimeMillis() + 5_000
        while (chat.state.value.thread.none { it.callLog } && System.currentTimeMillis() < end) Thread.sleep(20)
        val rows = chat.state.value.thread
        assertEquals(1, rows.count { it.callLog })
        assertTrue(chat.state.value.canCall)
        assertFalse(rows.single { it.id == "m" }.callLog)
    }

    @Test fun stopHangsUpAndFlushesTheCancelBeforeTheTransportStops() = runBlocking {
        val f = fixture(TransportStatus.ONLINE, linked = true, contact = verified())
        assertNull(f.engine.startCall(peer))
        val end = System.currentTimeMillis() + 5_000
        while (events.count { it == "signal" } < 1 && System.currentTimeMillis() < end) Thread.sleep(20)
        events.clear()
        f.engine.stop()
        val stopAt = events.indexOf("transport.stop")
        assertTrue("events=$events", stopAt >= 0)
        assertTrue("cancel signal must precede transport.stop: $events", events.indexOf("signal") in 0 until stopAt)
    }
}
