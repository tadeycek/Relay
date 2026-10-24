package com.relay.app.call

import com.relay.app.call.CT.ALICE
import com.relay.app.call.CT.A_PHONE
import com.relay.app.call.CT.BOB
import com.relay.app.call.CT.B_DESK
import com.relay.app.call.CT.B_LAP
import com.relay.app.call.CT.B_PHONE
import com.relay.app.call.CT.CAROL
import com.relay.app.call.CT.callId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory mailbox: delivers a wire to every node of an identity; a stored-urgent send is delivered twice (signal + stored copy). */
class Bus(val clock: FakeClock) {
    val nodes = ArrayList<Node>()
    private val queue = ArrayDeque<() -> Unit>()
    val wires = ArrayList<String>()
    var drop: (from: Node, to: String, wire: String) -> Boolean = { _, _, _ -> false }

    fun node(identity: String, device: String, seed: Int, tweak: (CallEngineConfig) -> CallEngineConfig = { it }): Node = Node(this, identity, device, seed, tweak).also { nodes += it }

    fun enqueue(job: () -> Unit) { queue += job }

    /** Runs deliveries and due timers until nothing is left. */
    fun pump(maxSteps: Int = 10_000) {
        var steps = 0
        while (queue.isNotEmpty()) { queue.removeFirst()(); check(++steps < maxSteps) { "bus did not settle" } }
    }

    fun advance(ms: Long) {
        val target = clock.now + ms
        while (true) {
            val due = nodes.flatMap { n -> n.timers.pending.map { n to it } }.filter { it.second.dueAt <= target }.minByOrNull { it.second.dueAt } ?: break
            clock.now = maxOf(clock.now, due.second.dueAt)
            due.first.timers.fire(due.second)
            pump()
        }
        clock.now = target
        pump()
    }
}

class FakeSignal(val bus: Bus, val owner: Node) : SignalPort {
    override fun send(toIdentity: String, wire: String, mode: SignalMode) {
        assertTrue("wire ${wire.length} chars", wire.length <= CallLimits.MAX_WIRE_CHARS)
        bus.wires += wire
        if (bus.drop(owner, toIdentity, wire)) return
        val copies = if (mode == SignalMode.LIVE_AND_STORED_URGENT) 2 else 1
        repeat(copies) { bus.nodes.filter { it.identity == toIdentity }.forEach { t -> bus.enqueue { t.runtime.onWire(owner.identity, wire) } } }
    }
}

class FakeTimers : TimerPort {
    class P(val callId: String, val timer: CallTimer, val dueAt: Long)
    val pending = ArrayList<P>()
    lateinit var clock: FakeClock
    private var sink: (CallEvent) -> Unit = {}
    override fun attach(sink: (CallEvent) -> Unit) { this.sink = sink }
    override fun start(callId: String, timer: CallTimer, delayMs: Long) { cancel(callId, timer); pending += P(callId, timer, clock.now + delayMs) }
    override fun cancel(callId: String, timer: CallTimer) { pending.removeAll { it.callId == callId && it.timer == timer } }
    fun fire(p: P) { pending.remove(p); sink(CallEvent.TimerFired(p.callId, p.timer)) }
}

class FakeTurn : TurnCredentialPort {
    private var sink: (CallEvent) -> Unit = {}
    var fail = false
    val requests = ArrayList<String>()
    override fun attach(sink: (CallEvent) -> Unit) { this.sink = sink }
    override fun request(callId: String) { requests += callId; sink(if (fail) CallEvent.IceServersFailed(callId) else CallEvent.IceServersReady(callId, SERVERS)) }
}

class FakePeer : PeerConnectionPort {
    private var sink: (CallEvent) -> Unit = {}
    val open = HashSet<String>(); val closed = ArrayList<String>()
    val remoteCandidates = ArrayList<IceCandidate>()
    val mic = ArrayList<Boolean>()
    var autoConnect = true
    override fun attach(sink: (CallEvent) -> Unit) { this.sink = sink }
    override fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean) { check(open.add(callId)) }
    override fun createOffer(callId: String, gen: Int, iceRestart: Boolean) {
        sink(CallEvent.LocalDescription(callId, gen, SdpKind.OFFER, CT.SDP))
        sink(CallEvent.LocalCandidate(callId, gen, CT.cand(gen + 1)))
    }
    override fun applyRemoteOffer(callId: String, gen: Int, sdp: String) {
        sink(CallEvent.LocalDescription(callId, gen, SdpKind.ANSWER, CT.SDP))
        sink(CallEvent.LocalCandidate(callId, gen, CT.cand(gen + 10)))
        if (autoConnect) sink(CallEvent.PeerState(callId, IceState.CONNECTED))
    }
    override fun applyRemoteAnswer(callId: String, gen: Int, sdp: String) { if (autoConnect) sink(CallEvent.PeerState(callId, IceState.CONNECTED)) }
    override fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>) { remoteCandidates += candidates }
    override fun setMicMuted(callId: String, muted: Boolean) { mic += muted }
    override fun close(callId: String) { check(open.remove(callId)); closed += callId }
    fun report(e: CallEvent) = sink(e)
}

class FakeUi : CallUiPort {
    val ringing = HashSet<String>(); val states = ArrayList<CallState>(); val records = ArrayList<CallEffect.RecordCallEvent>(); val rejected = ArrayList<String>()
    override fun startRing(callId: String, incoming: Boolean) { ringing += callId }
    override fun stopRing(callId: String) { ringing -= callId }
    override fun stateChanged(state: CallState) { states += state }
    override fun record(event: CallEffect.RecordCallEvent) { records += event }
    override fun commandRejected(reason: String) { rejected += reason }
}

class Node(val bus: Bus, val identity: String, val device: String, seed: Int, tweak: (CallEngineConfig) -> CallEngineConfig) {
    val random = FakeRandom(0x1000_0000L * (seed + 1))
    val timers = FakeTimers().also { it.clock = bus.clock }
    val peer = FakePeer(); val turn = FakeTurn(); val ui = FakeUi()
    val runtime = CallRuntime(tweak(CallEngineConfig(identity, device)), bus.clock, random, FakeSignal(bus, this), turn, peer, timers, ui)
    val state get() = runtime.state
    val ended get() = state as CallState.Ended
    fun cmd(e: CallEvent) { runtime.dispatch(e); bus.pump() }
    fun call(id: Int) { random.queue += callId(id); cmd(CallEvent.StartCall(if (identity == ALICE) BOB else ALICE)) }
}

class CallRuntimeTest {
    private fun world(): Triple<Bus, Node, List<Node>> {
        val bus = Bus(FakeClock())
        val alice = bus.node(ALICE, A_PHONE, 1)
        val bob = listOf(bus.node(BOB, B_PHONE, 2), bus.node(BOB, B_DESK, 3), bus.node(BOB, B_LAP, 4))
        return Triple(bus, alice, bob)
    }

    @Test fun phoneCallsEveryCalleeDevice_oneAccepts_othersStopSilently_thenBothHangUpCleanly() {
        val (bus, alice, bob) = world()
        alice.call(1)
        assertTrue(bob.all { it.state is CallState.RingingIn }); assertTrue(bob.all { callId(1) in it.ui.ringing })
        assertTrue("caller hears ringback", (alice.state as CallState.RingingOut).calleeRinging)
        bob[1].cmd(CallEvent.AcceptIncoming)
        assertTrue(alice.state is CallState.Active); assertTrue(bob[1].state is CallState.Active)
        for (i in listOf(0, 2)) {
            assertEquals(CallOutcome.ANSWERED_ELSEWHERE, bob[i].ended.outcome)
            assertTrue("no missed-call record for ${bob[i].device}", bob[i].ui.records.isEmpty()); assertTrue(bob[i].ui.ringing.isEmpty())
        }
        bus.advance(30_000) // candidates are flushed by the 250 ms timer
        assertTrue(alice.peer.remoteCandidates.isNotEmpty() && bob[1].peer.remoteCandidates.isNotEmpty())
        alice.cmd(CallEvent.HangUp)
        assertEquals(CallOutcome.COMPLETED, alice.ended.outcome); assertEquals(CallOutcome.COMPLETED, bob[1].ended.outcome)
        for (n in listOf(alice, bob[1])) { assertTrue(n.peer.open.isEmpty()); assertEquals(30_000L, n.ui.records.single().durationMs); assertTrue(n.timers.pending.isEmpty()) }
        assertTrue(bus.nodes.all { it.timers.pending.isEmpty() })
    }

    @Test fun acceptRace_everyOrderOfThreeDevices_exactlyTheFirstAccepterWins() {
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            val (bus, alice, bob) = world()
            alice.call(1)
            // all three users tap accept "at the same time": the commands are processed before any network delivery
            for (i in order) bob[i].runtime.dispatch(CallEvent.AcceptIncoming)
            bus.pump()
            val winner = bob[order.first()]
            assertTrue("order $order", winner.state is CallState.Active); assertTrue(alice.state is CallState.Active)
            assertEquals(winner.device, (alice.state as CallState.Active).peerDevice)
            for (i in order.drop(1)) { val loser = bob[i]; assertEquals("order $order", CallOutcome.LOST_RACE, loser.ended.outcome); assertTrue(loser.peer.open.isEmpty()); assertTrue(loser.ui.records.isEmpty()) }
            assertEquals(1, bus.nodes.count { it.state is CallState.Active && it.identity == BOB })
        }
    }

    @Test fun duplicateStoredAndLiveInvite_ringsOnce() {
        val (_, alice, bob) = world()
        alice.call(1)
        assertEquals(1, bob[0].ui.states.count { it is CallState.RingingIn })
    }

    @Test fun inviteResendsReachADeviceThatConnectedLate() {
        val (bus, alice, bob) = world()
        bus.drop = { _, to, _ -> to == BOB && bob[2].state is CallState.Idle && bus.clock.now < CT.NOW + 4000 && false }
        // bob[2] "was offline" when the first invite went out
        val offline = bob[2]; bus.nodes.remove(offline)
        alice.call(1)
        assertTrue(offline.state is CallState.Idle)
        bus.nodes.add(offline)
        bus.advance(5000)
        assertTrue(offline.state is CallState.RingingIn)
        assertEquals(callId(1), (offline.state as CallState.RingingIn).call.id)
    }

    @Test fun glare_bothCallAtOnce_exactlyTheLowerCallIdSurvivesForBothOrderings() {
        for ((aId, bId) in listOf(1 to 2, 2 to 1)) {
            val bus = Bus(FakeClock())
            val alice = bus.node(ALICE, A_PHONE, 1); val bobPhone = bus.node(BOB, B_PHONE, 2); val bobDesk = bus.node(BOB, B_DESK, 3)
            alice.random.queue += callId(aId); bobPhone.random.queue += callId(bId)
            alice.runtime.dispatch(CallEvent.StartCall(BOB)); bobPhone.runtime.dispatch(CallEvent.StartCall(ALICE))
            bus.pump()
            val survivor = callId(minOf(aId, bId))
            assertTrue("aId=$aId", alice.state is CallState.Active); assertTrue(bobPhone.state is CallState.Active)
            assertEquals(survivor, (alice.state as CallState.Active).call.id); assertEquals(survivor, (bobPhone.state as CallState.Active).call.id)
            assertEquals(survivor, bobPhone.state.let { (it as CallState.Active).call.id })
            // nobody logs a missed call for the loser, the sibling device stops ringing
            assertTrue(alice.ui.records.isEmpty() && bobPhone.ui.records.isEmpty()); assertTrue(alice.ui.ringing.isEmpty() && bobPhone.ui.ringing.isEmpty())
            assertTrue(bobDesk.state is CallState.Ended); assertTrue(bobDesk.ui.records.none { it.outcome == CallOutcome.MISSED })
            assertTrue(bus.nodes.all { it.peer.open.size <= 1 }); assertTrue(bus.nodes.all { it.timers.pending.none { p -> p.callId != survivor } })
        }
    }

    /**
     * Review finding: the busy reply of a device that is already in another call used to end the new call
     * immediately, even though the identity's other devices could answer (LP-D10). The caller keeps ringing
     * and ends BUSY at the ring timeout; the callee records the missed call and its active call is untouched.
     */
    @Test fun busy_inviteWhileInAnotherCall() {
        val bus = Bus(FakeClock())
        val alice = bus.node(ALICE, A_PHONE, 1); val bob = bus.node(BOB, B_PHONE, 2); val carol = bus.node(CAROL, "55".repeat(16), 3)
        carol.random.queue += callId(5); carol.cmd(CallEvent.StartCall(BOB)); bob.cmd(CallEvent.AcceptIncoming)
        assertTrue(bob.state is CallState.Active)
        alice.call(1)
        assertTrue("the caller keeps ringing while the identity has other devices", alice.state is CallState.RingingOut)
        assertEquals(CallOutcome.MISSED, bob.ui.records.single().outcome); assertEquals(CallReasons.BUSY, bob.ui.records.single().reason)
        assertTrue(bob.state is CallState.Active)
        bus.advance(46_000)
        assertEquals("ring timeout is what ends a busy call", CallOutcome.BUSY, alice.ended.outcome)
    }

    @Test fun declineOnOneDevice_endsTheCallerAndSilencesSiblings() {
        val (_, alice, bob) = world()
        alice.call(1); bob[0].cmd(CallEvent.DeclineIncoming)
        assertEquals(CallOutcome.DECLINED, alice.ended.outcome)
        assertEquals(CallOutcome.DECLINED, bob[0].ended.outcome)
        for (i in 1..2) { assertEquals(CallOutcome.ANSWERED_ELSEWHERE, bob[i].ended.outcome); assertTrue(bob[i].ui.records.isEmpty()) }
    }

    @Test fun noAnswer_ringTimeoutEndsEverybodyAndRecordsMissedOnTheCalleeDevices() {
        val (bus, alice, bob) = world()
        alice.call(1); bus.advance(45_000)
        assertEquals(CallOutcome.NO_ANSWER, alice.ended.outcome)
        for (b in bob) { assertEquals(CallOutcome.MISSED, b.ended.outcome); assertEquals(1, b.ui.records.size) }
        assertTrue(bus.nodes.all { it.timers.pending.isEmpty() && it.ui.ringing.isEmpty() })
    }

    @Test fun callerCancels_calleeDevicesRecordMissed() {
        val (_, alice, bob) = world()
        alice.call(1); alice.cmd(CallEvent.HangUp)
        assertEquals(CallOutcome.CANCELLED, alice.ended.outcome)
        assertTrue(bob.all { it.ended.outcome == CallOutcome.MISSED && it.ui.records.size == 1 })
    }

    @Test fun calleeNeverGetsAnOffer_losingDeviceGivesUpAfterFiveSecondsEvenIfAnsweredElsewhereIsLost() {
        val (bus, alice, bob) = world()
        alice.call(1)
        bus.drop = { from, _, wire -> from === alice && (wire.length > 0) && CallBodyCodec.decode(wire, CT.ctx(BOB, ALICE, bus.clock.now), null).let { it is CallParseResult.Ok && it.packet is CallPacket.AnsweredElsewhere } }
        bob[0].runtime.dispatch(CallEvent.AcceptIncoming); bob[1].runtime.dispatch(CallEvent.AcceptIncoming); bus.pump()
        assertTrue(bob[1].state is CallState.Connecting)
        bus.advance(5000)
        assertEquals(CallOutcome.LOST_RACE, bob[1].ended.outcome); assertEquals(CallReasons.NO_OFFER, bob[1].ended.reason)
        assertTrue(bob[0].state is CallState.Active)
    }

    @Test fun iceRestartAfterHandover_recoversBothSides() {
        val (bus, alice, bob) = world()
        alice.call(1); bob[0].cmd(CallEvent.AcceptIncoming)
        assertTrue(alice.state is CallState.Active)
        alice.peer.report(CallEvent.PeerState(callId(1), IceState.DISCONNECTED)); bus.pump()
        // fake peer auto-connects when the restart answer is applied
        assertFalse((alice.state as CallState.Active).restarting); assertTrue(bob[0].state is CallState.Active)
        assertTrue(alice.timers.pending.none { it.timer == CallTimer.ICE_RESTART })
        assertTrue(bus.wires.map { CT.json(it) }.any { "\"gen\":1" in it })
    }

    @Test fun iceRestartThatNeverRecovers_endsBothSidesAfterTwentySeconds() {
        val (bus, alice, bob) = world()
        alice.call(1); bob[0].cmd(CallEvent.AcceptIncoming)
        alice.peer.autoConnect = false; bob[0].peer.autoConnect = false
        alice.peer.report(CallEvent.PeerState(callId(1), IceState.DISCONNECTED)); bus.pump()
        assertTrue((alice.state as CallState.Active).restarting)
        bus.advance(20_000)
        assertEquals(CallReasons.RESTART_TIMEOUT, alice.ended.reason); assertEquals(CallOutcome.FAILED, bob[0].ended.outcome)
    }

    @Test fun turnFailure_endsTheCallOnBothSides() {
        val (_, alice, bob) = world()
        alice.turn.fail = true
        alice.call(1); bob[0].cmd(CallEvent.AcceptIncoming)
        assertEquals(CallReasons.TURN_FAILED, alice.ended.reason)
        assertEquals(CallOutcome.FAILED, bob[0].ended.outcome)
    }

    @Test fun connectTimeout_whenMediaNeverConnects() {
        val (bus, alice, bob) = world()
        alice.peer.autoConnect = false; bob[0].peer.autoConnect = false
        alice.call(1); bob[0].cmd(CallEvent.AcceptIncoming)
        assertTrue(alice.state is CallState.Connecting)
        bus.advance(15_000)
        assertEquals(CallReasons.CONNECT_TIMEOUT, alice.ended.reason); assertTrue(bob[0].state is CallState.Ended)
        assertTrue(alice.peer.open.isEmpty() && bob[0].peer.open.isEmpty())
    }

    @Test fun onWire_hostileInputIsRejectedWithoutEffects() {
        val (bus, _, bob) = world()
        val n = bob[0]
        val fresh = CallBodyCodec.encode(CallPacket.Invite(CT.header(ts = bus.clock.now, expires = bus.clock.now + 45_000)))
        for (w in listOf("", "hello", "TYPE:CALL|J:@@", CallLimits.PREFIX + "A".repeat(5000))) assertTrue(w, n.runtime.onWire(ALICE, w) is CallParseResult.Rejected)
        assertTrue(n.runtime.onWire(CAROL, fresh) is CallParseResult.Rejected) // sender is not a party
        assertEquals(CallState.Idle, n.state)
        val expiredWire = CallBodyCodec.encode(CallPacket.Invite(CT.header(ts = bus.clock.now - 100_000, expires = bus.clock.now - 55_000)))
        assertEquals(CallRejectReason.EXPIRED, (n.runtime.onWire(ALICE, expiredWire) as CallParseResult.Rejected).reason)
        assertTrue(n.runtime.onWire(ALICE, fresh) is CallParseResult.Ok); assertTrue(n.state is CallState.RingingIn)
        assertEquals(CallRejectReason.REPLAY, (n.runtime.onWire(ALICE, fresh) as CallParseResult.Rejected).reason)
        bus.pump()
    }

    @Test fun commandRejectedReachesTheUi() {
        val (_, alice, _) = world()
        alice.call(1); alice.random.queue += callId(2); alice.cmd(CallEvent.StartCall(BOB))
        assertEquals(listOf(CallReasons.BUSY_SELF), alice.ui.rejected)
    }

    @Test fun everyWireOnTheBusIsWithinTheSizeBound() {
        val (bus, alice, bob) = world()
        alice.call(1); bob[0].cmd(CallEvent.AcceptIncoming); alice.cmd(CallEvent.SetMuted(true)); alice.cmd(CallEvent.HangUp)
        assertTrue(bus.wires.size >= 8); assertTrue(bus.wires.all { it.length <= CallLimits.MAX_WIRE_CHARS })
        assertEquals(bus.wires.size, bus.wires.map { CT.json(it) }.size)
    }
}
