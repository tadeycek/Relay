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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Seeded random event sequences against one engine; every step re-checks the safety invariants of the effect stream. */
class CallEngineFuzzTest {
    private val parties = listOf(ALICE, BOB, CAROL)
    private val devices = listOf(A_PHONE, B_PHONE, B_DESK, B_LAP, "primary")

    private class Checker(val engine: CallEngine, val seed: Long) {
        val openPeers = HashSet<String>()
        val ringing = HashSet<String>()
        val armed = HashSet<Pair<String, CallTimer>>()
        /** Mirrors the documented contract: the engine remembers the last 16 ended call ids; older ids may legitimately start again. */
        val endedIds = LinkedHashSet<String>()
        val endedOnce = HashSet<String>()
        var maxArmed = 0
        var steps = 0
        var recorded = 0
        var activeSteps = 0
        var glareLost = 0
        var lostRace = 0

        fun fail(msg: String): Nothing = throw AssertionError("seed=$seed step=$steps: $msg")

        fun fired(callId: String, t: CallTimer) { armed.remove(callId to t) }

        fun step(e: CallEvent, fx: List<CallEffect>, forbidden: Set<String>) {
            steps++
            val stateFx = fx.filterIsInstance<CallEffect.StateChanged>()
            if (stateFx.size > 1) fail("two StateChanged in one batch: $fx")
            if (stateFx.isNotEmpty() && fx.last() !is CallEffect.StateChanged) fail("StateChanged is not last: $fx")
            for (f in fx) {
                val id = idOf(f)
                if (id != null && id in forbidden) fail("effect for already-ended call $id: $f after $e")
                when (f) {
                    is CallEffect.OpenPeerConnection -> if (!openPeers.add(f.callId)) fail("peer opened twice")
                    is CallEffect.ClosePeerConnection -> if (!openPeers.remove(f.callId)) fail("close without open: $f")
                    is CallEffect.StartRing -> ringing.add(f.callId)
                    is CallEffect.StopRing -> ringing.remove(f.callId)
                    is CallEffect.StartTimer -> armed.add(f.callId to f.timer)
                    is CallEffect.CancelTimer -> if (!armed.remove(f.callId to f.timer)) fail("cancel of a timer that was not armed: $f")
                    is CallEffect.SendSignal -> CallBodyCodec.encode(f.packet) // must always be encodable
                    is CallEffect.RecordCallEvent -> recorded++
                    else -> Unit
                }
            }
            if (openPeers.size > 1) fail("two peer connections open: $openPeers")
            if (ringing.size > 1) fail("two rings: $ringing")
            val st = engine.state
            when (st) {
                is CallState.Ended -> {
                    if (stateFx.isNotEmpty()) {
                        if (st.call.id in forbidden) fail("call ${st.call.id} ended twice")
                        endedOnce.add(st.call.id)
                        endedIds.add(st.call.id)
                        while (endedIds.size > 16) { val it = endedIds.iterator(); it.next(); it.remove() }
                    }
                    if (openPeers.isNotEmpty() || ringing.isNotEmpty() || armed.isNotEmpty()) fail("leak after Ended: peers=$openPeers ring=$ringing timers=$armed")
                }
                CallState.Idle -> if (openPeers.isNotEmpty() || ringing.isNotEmpty() || armed.isNotEmpty()) fail("leak in Idle")
                is CallState.Connecting, is CallState.Active -> if (ringing.isNotEmpty()) fail("ringing while $st")
                else -> Unit
            }
            val live = (st as? CallState.RingingIn)?.call?.id ?: (st as? CallState.RingingOut)?.call?.id ?: (st as? CallState.Connecting)?.call?.id ?: (st as? CallState.Active)?.call?.id
            if (live != null) {
                if (openPeers.any { it != live } || armed.any { it.first != live } || ringing.any { it != live }) fail("resources of a different call alive in $st")
            }
            if (st is CallState.Active) activeSteps++
            if (st is CallState.Ended && stateFx.isNotEmpty()) { if (st.outcome == CallOutcome.GLARE_LOST) glareLost++; if (st.outcome == CallOutcome.LOST_RACE) lostRace++ }
            maxArmed = maxOf(maxArmed, armed.size)
            val s = engine.stats()
            if (s.getValue("localCandidates") > 64 || s.getValue("remoteCandidates") > 64 || s.getValue("recentEnded") > 16 ||
                s.getValue("armedTimers") > CallTimer.values().size || s.getValue("lateAccepts") > 8) fail("unbounded state $s")
            if (s.getValue("armedTimers") != armed.size) fail("engine thinks ${s.getValue("armedTimers")} timers armed, effects say ${armed.size}")
        }

        private fun idOf(f: CallEffect): String? = when (f) {
            is CallEffect.SendSignal -> f.packet.header.call; is CallEffect.StartRing -> f.callId; is CallEffect.StopRing -> f.callId
            is CallEffect.RequestIceServers -> f.callId; is CallEffect.OpenPeerConnection -> f.callId; is CallEffect.CreateOffer -> f.callId
            is CallEffect.ApplyRemoteOffer -> f.callId; is CallEffect.ApplyRemoteAnswer -> f.callId; is CallEffect.AddRemoteCandidates -> f.callId
            is CallEffect.SetMicMuted -> f.callId; is CallEffect.ClosePeerConnection -> f.callId; is CallEffect.StartTimer -> f.callId
            is CallEffect.CancelTimer -> f.callId; is CallEffect.RecordCallEvent -> null; is CallEffect.CommandRejected -> null; is CallEffect.StateChanged -> null
        }
    }

    private fun run(seed: Long, steps: Int): Checker {
        val rnd = Random(seed)
        val self = if (rnd.nextBoolean()) BOB else ALICE
        val dev = if (self == BOB) listOf(B_PHONE, B_DESK, B_LAP).random(kotlin.random.Random(seed)) else A_PHONE
        val clock = FakeClock(); val random = FakeRandom(0x1000L * (seed % 1000 + 1))
        val engine = CallEngine(CallEngineConfig(self, dev), clock, random)
        val chk = Checker(engine, seed)
        var nonce = 0
        fun <T> pick(l: List<T>) = l[rnd.nextInt(l.size)]
        fun liveCall(): Triple<String, String, String>? = when (val s = engine.state) { // id, caller, callee
            is CallState.RingingIn -> Triple(s.call.id, s.call.peer, self); is CallState.RingingOut -> Triple(s.call.id, self, s.call.peer)
            is CallState.Connecting -> if (s.call.incoming) Triple(s.call.id, s.call.peer, self) else Triple(s.call.id, self, s.call.peer)
            is CallState.Active -> if (s.call.incoming) Triple(s.call.id, s.call.peer, self) else Triple(s.call.id, self, s.call.peer)
            else -> null
        }
        fun curId(): String = liveCall()?.first ?: (engine.state as? CallState.Ended)?.call?.id ?: callId(1 + rnd.nextInt(4))

        repeat(steps) {
            clock.now += pick(listOf(0L, 0L, 1L, 250L, 5000L, 20_000L, 70_000L))
            val useLive = liveCall() != null && rnd.nextInt(10) < 7
            val id = if (useLive) liveCall()!!.first else if (rnd.nextInt(4) == 0) curId() else callId(1 + rnd.nextInt(4))
            var caller = if (useLive) liveCall()!!.second else pick(parties)
            var callee = if (useLive) liveCall()!!.third else if (rnd.nextBoolean()) self else pick(parties)
            if (!useLive && caller != self && callee != self && rnd.nextBoolean()) callee = self
            if (rnd.nextInt(25) == 0) { caller = pick(parties); callee = pick(parties) }
            val peer = if (caller == self) callee else caller
            val sender = if (rnd.nextInt(8) == 0) pick(parties) else if (rnd.nextInt(15) == 0) self else peer
            val header = CallHeader(id, clock.now, clock.now + pick(listOf(45_000L, 60_000L, 10_000L)), caller, callee, pick(devices), CT.nonce(nonce++))
            val to = pick(devices)
            val gen = rnd.nextInt(4)
            val pkt: CallPacket = when (rnd.nextInt(13)) {
                0 -> CallPacket.Invite(header); 1 -> CallPacket.Ringing(header, to); 2 -> CallPacket.Accept(header, if (rnd.nextInt(3) > 0) dev else to)
                3 -> CallPacket.Offer(header, if (rnd.nextInt(3) > 0) dev else to, gen, CT.SDP); 4 -> CallPacket.Answer(header, if (rnd.nextInt(3) > 0) dev else to, gen, CT.SDP)
                5 -> CallPacket.Ice(header, if (rnd.nextInt(3) > 0) dev else to, gen, listOf(CT.cand(rnd.nextInt(5))))
                6 -> CallPacket.AnsweredElsewhere(header, pick(devices), pick(listOf("answered", "declined")))
                7 -> CallPacket.Decline(header, if (rnd.nextInt(3) > 0) dev else to); 8 -> CallPacket.Busy(header, if (rnd.nextInt(3) > 0) dev else to)
                9 -> CallPacket.Cancel(header, pick(listOf(null, CallReasons.GLARE))); 10 -> CallPacket.End(header, if (rnd.nextInt(3) > 0) dev else to)
                11 -> CallPacket.Timeout(header); else -> CallPacket.Mute(header, if (rnd.nextInt(3) > 0) dev else to, rnd.nextBoolean())
            }
            val cur = curId()
            val event: CallEvent = when (rnd.nextInt(24)) {
                0 -> CallEvent.StartCall(pick(parties + "garbage")); 1 -> CallEvent.AcceptIncoming; 2 -> CallEvent.DeclineIncoming; 3 -> CallEvent.HangUp
                4 -> CallEvent.SetMuted(rnd.nextBoolean())
                in 5..12 -> CallEvent.SignalReceived(sender, pkt)
                in 13..15 -> { val t = pick(CallTimer.values().toList()); chk.fired(cur, t); CallEvent.TimerFired(cur, t) }
                16 -> CallEvent.IceServersReady(cur, SERVERS); 17 -> CallEvent.IceServersFailed(cur)
                18 -> CallEvent.LocalDescription(cur, gen, pick(SdpKind.values().toList()), CT.SDP)
                19 -> CallEvent.LocalCandidate(cur, gen, CT.cand(rnd.nextInt(30)))
                in 20..22 -> CallEvent.PeerState(cur, pick(IceState.values().toList()))
                else -> CallEvent.PeerFailed(cur)
            }
            if (rnd.nextInt(6) == 0 && event is CallEvent.StartCall) random.queue += callId(1 + rnd.nextInt(4))
            val forbidden = chk.endedIds.filter { engine.remembersEnded(it) }.toSet()
            val fx = engine.handle(event)
            chk.step(event, fx, forbidden)
        }
        return chk
    }

    @Test fun randomSequences_neverBreakTheEffectInvariants() {
        var calls = 0L; var activeSteps = 0; var records = 0
        for (seed in 1L..600L) {
            val chk = run(seed, 400)
            calls += chk.endedOnce.size; records += chk.recorded; activeSteps += chk.activeSteps
        }
        assertTrue("fuzz must actually exercise calls, got $calls ended calls", calls > 300)
        assertTrue(records > 100)
        assertTrue("fuzz must reach Active, got $activeSteps steps", activeSteps > 50)
    }

    @Test fun glareAndAcceptRaceTables_neverLeaveTwoLiveCalls() {
        // every pair of call ids, both roles, with the packets arriving before or after the local command
        val ids = listOf(1, 2, 3, 0xffff)
        for (a in ids) for (b in ids) for (cmdFirst in listOf(true, false)) {
            val clock = FakeClock(); val eng = CallEngine(CallEngineConfig(ALICE, A_PHONE), clock, FakeRandom().also { it.queue += callId(a) })
            val inv = CallPacket.Invite(CallHeader(callId(b), clock.now, clock.now + 45_000, BOB, ALICE, B_PHONE, CT.nonce(1)))
            if (cmdFirst) { eng.handle(CallEvent.StartCall(BOB)); eng.handle(CallEvent.SignalReceived(BOB, inv)) }
            else { eng.handle(CallEvent.SignalReceived(BOB, inv)); eng.handle(CallEvent.StartCall(BOB)) }
            val s = eng.state
            val liveIds = listOfNotNull((s as? CallState.RingingOut)?.call?.id, (s as? CallState.RingingIn)?.call?.id, (s as? CallState.Connecting)?.call?.id)
            assertTrue("a=$a b=$b cmdFirst=$cmdFirst -> $s", liveIds.size <= 1)
            if (a == b && cmdFirst) assertEquals(callId(a), liveIds.single())
        }
    }

    @Test fun memoryStaysBoundedUnderALongHostileRun() {
        val chk = run(99_999L, 20_000)
        assertTrue(chk.maxArmed <= CallTimer.values().size)
        assertTrue(chk.steps == 20_000)
    }
}
