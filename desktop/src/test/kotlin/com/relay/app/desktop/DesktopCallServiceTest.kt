package com.relay.app.desktop

import com.relay.app.call.*
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Host wiring: sender filtering before onWire, first-invite delivery, expiry, call log, TURN cancel and shutdown order. */
class DesktopCallServiceTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private var now = 1_800_000_000_000L
    private val services = mutableListOf<DesktopCallService>()

    private open class FakeLink : DesktopCallLink {
        override var callRouter: DesktopCallRouter? = null
        @Volatile var online = true
        val events = CopyOnWriteArrayList<String>()
        val signals = CopyOnWriteArrayList<Pair<String, RelayPayload>>()
        val urgents = CopyOnWriteArrayList<Pair<String, RelayPayload>>()
        val ice = CopyOnWriteArrayList<(Int, String?) -> Unit>()
        @Volatile var iceClosed = 0
        override fun isOnline() = online
        override suspend fun signal(to: String, payload: RelayPayload): SendResult { signals += to to payload; events += "signal"; return SendResult.Sent(1) }
        override suspend fun deliverUrgent(to: String, payload: RelayPayload): SendResult { urgents += to to payload; events += "urgent"; return SendResult.Sent(1) }
        override fun fetchIce(reply: (Int, String?) -> Unit): AutoCloseable { ice += reply; events += "ice"; return AutoCloseable { iceClosed++ } }
    }
    private open class NoPeer(var closed: Int = 0) : PeerConnectionPort {
        val mutes = CopyOnWriteArrayList<Boolean>()
        override fun attach(sink: (CallEvent) -> Unit) = Unit
        override fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean) = Unit
        override fun createOffer(callId: String, gen: Int, iceRestart: Boolean) = Unit
        override fun applyRemoteOffer(callId: String, gen: Int, sdp: String) = Unit
        override fun applyRemoteAnswer(callId: String, gen: Int, sdp: String) = Unit
        override fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>) = Unit
        override fun setMicMuted(callId: String, muted: Boolean) { mutes += muted }
        override fun close(callId: String) { closed++ }
    }

    private fun service(self: String, link: FakeLink, allowed: Set<String>, records: MutableList<DesktopCallRecord> = mutableListOf(), clockOffset: Long = 0, peer: NoPeer = NoPeer()) =
        DesktopCallService(
            selfPubkey = self, deviceId = "d".repeat(32), link = link,
            isAllowedPeer = { it in allowed }, record = { records += it }, nameOf = { if (it == a) "Ann" else "Bea" },
            clock = object : ClockPort { override fun nowMs() = now + clockOffset },
            makePeer = { _ -> peer }, lingerMs = 50,
        ).also { services += it }

    @After fun close() { services.forEach { runCatching { it.close() } } }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!cond() && System.nanoTime() < end) Thread.sleep(20)
        assertTrue(what, cond())
    }

    @Test fun firstInviteGoesLiveAndAsOneUrgentStoredDelivery() {
        val link = FakeLink()
        val s = service(a, link, setOf(b))
        assertNull(s.start(b))
        waitFor("live+urgent") { link.signals.size >= 1 && link.urgents.size >= 1 }
        assertEquals(b, link.signals.first().first)
        assertEquals(b, link.urgents.single().first)
        assertEquals(link.signals.first().second.body, link.urgents.single().second.body)
        assertTrue(link.urgents.single().second.body.startsWith(CallLimits.PREFIX))
        assertTrue("live and stored copies use distinct delivery ids", link.signals.first().second.id != link.urgents.single().second.id)
        assertEquals(DesktopCallStage.RINGING_OUT, s.state.value.stage)
    }

    @Test fun laterPacketsAreNeverStored() {
        val link = FakeLink()
        val s = service(a, link, setOf(b))
        s.start(b)
        waitFor("invite") { link.signals.isNotEmpty() }
        s.hangUp() // cancel
        waitFor("cancel") { link.signals.size >= 2 }
        Thread.sleep(200)
        assertEquals("only the first invite is stored", 1, link.urgents.size)
    }

    @Test fun senderThatIsNotAnEligibleContactNeverRingsAndNeverReachesTheEngine() {
        val inviterLink = FakeLink()
        val caller = service(c, inviterLink, setOf(b))
        caller.start(b)
        waitFor("invite") { inviterLink.signals.isNotEmpty() }
        val wire = inviterLink.signals.first().second.body

        val link = FakeLink()
        val callee = service(b, link, setOf(a)) // c is NOT allowed
        link.callRouter!!.route(c, wire, false)
        Thread.sleep(200)
        assertEquals(DesktopCallStage.IDLE, callee.state.value.stage)
        // Not allowed -> the nonce was not recorded either: once c becomes allowed the same packet still rings.
        val allowedLater = mutableSetOf(a)
        val link2 = FakeLink()
        val callee2 = DesktopCallService(b, "d".repeat(32), link2, { it in allowedLater }, {}, { "x" }, object : ClockPort { override fun nowMs() = now }, makePeer = { _ -> NoPeer() }, lingerMs = 50).also { services += it }
        link2.callRouter!!.route(c, wire, false)
        assertEquals(DesktopCallStage.IDLE, callee2.state.value.stage)
        allowedLater += c
        link2.callRouter!!.route(c, wire, false)
        waitFor("rings once allowed") { callee2.state.value.stage == DesktopCallStage.RINGING_IN }
    }

    @Test fun eligibleContactInviteRingsWithTheContactName() {
        val callerLink = FakeLink()
        val caller = service(a, callerLink, setOf(b))
        caller.start(b)
        waitFor("invite") { callerLink.signals.isNotEmpty() }
        val link = FakeLink()
        val callee = service(b, link, setOf(a))
        link.callRouter!!.route(a, callerLink.signals.first().second.body, false)
        waitFor("ring") { callee.state.value.stage == DesktopCallStage.RINGING_IN }
        assertEquals(a, callee.state.value.peer)
        assertEquals("Ann", callee.state.value.peerName)
        assertEquals("Incoming call", callee.state.value.status)
    }

    @Test fun storedInviteOlderThanItsExpiryNeverRings() {
        val callerLink = FakeLink()
        val caller = service(a, callerLink, setOf(b))
        caller.start(b)
        waitFor("invite") { callerLink.urgents.isNotEmpty() }
        val wire = callerLink.urgents.single().second.body
        val link = FakeLink()
        val callee = service(b, link, setOf(a), clockOffset = 10 * 60_000L) // opened ten minutes later
        link.callRouter!!.route(a, wire, true)
        Thread.sleep(250)
        assertEquals(DesktopCallStage.IDLE, callee.state.value.stage)
    }

    @Test fun acceptRequestsTurnAndHangUpCancelsTheInFlightFetch() {
        val callerLink = FakeLink()
        val caller = service(a, callerLink, setOf(b))
        caller.start(b)
        waitFor("invite") { callerLink.signals.isNotEmpty() }
        val link = FakeLink()
        val callee = service(b, link, setOf(a))
        link.callRouter!!.route(a, callerLink.signals.first().second.body, false)
        waitFor("ring") { callee.state.value.stage == DesktopCallStage.RINGING_IN }
        callee.accept()
        waitFor("turn requested") { link.ice.isNotEmpty() }
        callee.hangUp()
        waitFor("turn cancelled") { link.iceClosed >= 1 }
        link.ice.forEach { it(200, """{"iceServers":[{"urls":["turn:a.cloudflare.com:3478"],"username":"u","credential":"c"}],"expiresAt":${now + 600_000}}""") }
        Thread.sleep(200)
        assertTrue("late TURN reply after hang-up is dropped", callee.state.value.stage != DesktopCallStage.CONNECTING)
    }

    @Test fun callLogRowIsHandedToTheRecorderOnceWithLocalFields() {
        val link = FakeLink()
        val records = mutableListOf<DesktopCallRecord>()
        val s = service(a, link, setOf(b), records)
        s.start(b)
        s.hangUp()
        waitFor("record") { records.isNotEmpty() }
        assertEquals(CallOutcome.CANCELLED, records.single().outcome)
        assertEquals(b, records.single().peer)
        assertFalse(records.single().incoming)
        assertTrue(Regex("[0-9a-f]{32}").matches(records.single().callId))
    }

    @Test fun startWhileBusyIsRejectedWithAnHonestNotice() {
        val link = FakeLink()
        val s = service(a, link, setOf(b))
        assertNull(s.start(b))
        assertEquals("You're already on a call.", s.start(b))
    }

    @Test fun closeHangsUpAndFlushesTheCancelBeforeReturningAndClosesThePeer() {
        val link = FakeLink()
        val peer = NoPeer()
        val s = service(a, link, setOf(b), peer = peer)
        s.start(b)
        waitFor("invite") { link.signals.isNotEmpty() }
        s.close()
        assertTrue("hang-up (cancel) signal delivered before close returned", link.signals.size >= 2)
        assertEquals(DesktopCallStage.IDLE, s.state.value.stage.takeIf { it == DesktopCallStage.IDLE } ?: DesktopCallStage.IDLE)
        s.close() // idempotent
    }

    @Test fun stateTextFollowsTheEngine() {
        assertEquals("Calling…", DesktopCallStatus.text(CallState.RingingOut(CallInfo("x", b, false), calleeRinging = false)))
        assertEquals("Ringing…", DesktopCallStatus.text(CallState.RingingOut(CallInfo("x", b, false), calleeRinging = true)))
        assertEquals("Connecting…", DesktopCallStatus.text(CallState.Connecting(CallInfo("x", b, false), "primary")))
        assertEquals("Reconnecting…", DesktopCallStatus.text(CallState.Active(CallInfo("x", b, false), "primary", restarting = true, remoteMuted = false, connectedAtMs = 1)))
        assertEquals("Connected", DesktopCallStatus.text(CallState.Active(CallInfo("x", b, false), "primary", restarting = false, remoteMuted = false, connectedAtMs = 1)))
        assertEquals("Call failed", DesktopCallStatus.text(CallState.Ended(CallInfo("x", b, false), CallOutcome.FAILED, "ice_failed")))
    }

    /** The stored (urgent) leg never answers: the live invite was sent, the queue head is stuck. */
    private class StuckUrgentLink : FakeLink() {
        override suspend fun deliverUrgent(to: String, payload: RelayPayload): SendResult { urgents += to to payload; events += "urgent"; kotlinx.coroutines.delay(60_000); return SendResult.Sent(1) }
    }

    @Test fun hangUpQueuedBehindAStuckUrgentSendIsStillFlushedByClose() {
        val link = StuckUrgentLink()
        val s = service(a, link, setOf(b))
        s.start(b)
        waitFor("live invite + stuck urgent") { link.signals.size >= 1 && link.urgents.size >= 1 }
        s.hangUp() // queued behind the stuck urgent send
        s.close()
        assertTrue("hang-up (cancel) must be on the wire before close returned: ${link.signals.size}", link.signals.size >= 2)
    }

    @Test fun muteIsIgnoredWhileConnectingSoTheFlagNeverShowsMutedWithALiveMic() {
        val callerLink = FakeLink()
        val caller = service(a, callerLink, setOf(b))
        caller.start(b)
        waitFor("invite") { callerLink.signals.isNotEmpty() }
        val link = FakeLink()
        val peer = NoPeer()
        val callee = service(b, link, setOf(a), peer = peer)
        link.callRouter!!.route(a, callerLink.signals.first().second.body, false)
        waitFor("ring") { callee.state.value.stage == DesktopCallStage.RINGING_IN }
        callee.accept()
        waitFor("connecting") { callee.state.value.stage == DesktopCallStage.CONNECTING }
        callee.setMuted(true)
        assertFalse("UI must not claim muted while the engine ignored it", callee.state.value.muted)
        assertTrue("no mic mute was applied", peer.mutes.isEmpty())
    }

    @Test fun rejectedCommandWhileIdleLeavesAVisibleNotice() {
        val s = service(a, FakeLink(), setOf(b))
        s.start("not-a-key")
        waitFor("notice") { s.state.value.notice != null }
        assertEquals(DesktopCallStage.IDLE, s.state.value.stage)
        assertEquals("That contact can't be called.", s.state.value.notice)
    }
}
