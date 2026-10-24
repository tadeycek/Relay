package com.relay.app.call

import com.relay.app.call.CT.ALICE
import com.relay.app.call.CT.A_PHONE
import com.relay.app.call.CT.BOB
import com.relay.app.call.CT.B_DESK
import com.relay.app.call.CT.B_PHONE

class FakeClock(var now: Long = CT.NOW) : ClockPort { override fun nowMs() = now }

class FakeRandom(private val base: Long = 0x5000_0000L) : RandomPort {
    val queue = ArrayDeque<String>()
    private var n = 0
    override fun hex(bytes: Int): String = queue.removeFirstOrNull() ?: "%0${bytes * 2}x".format(base + n++)
}

/** One engine under test plus the packets a remote peer would send it. */
class Rig(val self: String, val device: String, tweak: (CallEngineConfig) -> CallEngineConfig = { it }) {
    val clock = FakeClock()
    val random = FakeRandom()
    val engine = CallEngine(tweak(CallEngineConfig(self, device)), clock, random)
    val log = ArrayList<CallEffect>()
    private var nonceSeq = 1000

    fun send(e: CallEvent): List<CallEffect> = engine.handle(e).also { log += it }
    fun recv(sender: String, p: CallPacket) = send(CallEvent.SignalReceived(sender, p))
    fun tick(ms: Long) { clock.now += ms }
    fun nonce() = CT.nonce(nonceSeq++)

    fun h(call: Int, device: String, caller: String = ALICE, callee: String = BOB, life: Long = 60_000, ts: Long = clock.now) =
        CT.header(call = CT.callId(call), ts = ts, expires = ts + life, caller = caller, callee = callee, device = device, nonce = nonce())

    val state get() = engine.state

    inline fun <reified T : CallEffect> List<CallEffect>.all(): List<T> = filterIsInstance<T>()
    inline fun <reified T : CallPacket> List<CallEffect>.packets(): List<T> = filterIsInstance<CallEffect.SendSignal>().map { it.packet }.filterIsInstance<T>()
    fun List<CallEffect>.timers() = filterIsInstance<CallEffect.StartTimer>().map { it.timer }
}

/** callee (self = BOB / B_PHONE) with call 1 from ALICE/A_PHONE ringing. */
fun ringingInRig(call: Int = 1, tweak: (CallEngineConfig) -> CallEngineConfig = { it }): Rig {
    val r = Rig(BOB, B_PHONE, tweak)
    r.recv(ALICE, CallPacket.Invite(r.h(call, A_PHONE, life = 45_000)))
    check(r.state is CallState.RingingIn) { r.state }
    return r
}

/** callee that has accepted call 1, no offer yet, ICE servers not yet delivered. */
fun acceptedCalleeRig(call: Int = 1): Rig = ringingInRig(call).also { it.send(CallEvent.AcceptIncoming); check(it.state is CallState.Connecting) }

val SERVERS = listOf(IceServer(listOf("stun:stun.cloudflare.com:3478")), IceServer(listOf("turns:turn.example:443?transport=tcp"), "u", "p"))

/** callee that is Active on call 1 with peer ALICE/A_PHONE (gen 0 negotiated). */
fun activeCalleeRig(call: Int = 1): Rig {
    val r = acceptedCalleeRig(call)
    val id = CT.callId(call)
    r.send(CallEvent.IceServersReady(id, SERVERS))
    r.recv(ALICE, CallPacket.Offer(r.h(call, A_PHONE), B_PHONE, 0, CT.SDP))
    r.send(CallEvent.LocalDescription(id, 0, SdpKind.ANSWER, CT.SDP))
    r.send(CallEvent.PeerState(id, IceState.CONNECTED))
    check(r.state is CallState.Active) { r.state }
    return r
}

/** caller (self = ALICE / A_PHONE) ringing BOB with call 1. */
fun ringingOutRig(call: Int = 1, tweak: (CallEngineConfig) -> CallEngineConfig = { it }): Rig {
    val r = Rig(ALICE, A_PHONE, tweak)
    r.random.queue += CT.callId(call)
    r.send(CallEvent.StartCall(BOB))
    check(r.state is CallState.RingingOut) { r.state }
    return r
}

/** caller whose callee device B_PHONE accepted; ICE servers not yet delivered. */
fun acceptedCallerRig(call: Int = 1, winner: String = B_PHONE): Rig = ringingOutRig(call).also {
    it.recv(BOB, CallPacket.Accept(it.h(call, winner), A_PHONE)); check(it.state is CallState.Connecting)
}

/** caller that is Active on call 1 with peer BOB/B_PHONE (gen 0 negotiated). */
fun activeCallerRig(call: Int = 1): Rig {
    val r = acceptedCallerRig(call)
    val id = CT.callId(call)
    r.send(CallEvent.IceServersReady(id, SERVERS))
    r.send(CallEvent.LocalDescription(id, 0, SdpKind.OFFER, CT.SDP))
    r.recv(BOB, CallPacket.Answer(r.h(call, B_PHONE), A_PHONE, 0, CT.SDP))
    r.send(CallEvent.PeerState(id, IceState.CONNECTED))
    check(r.state is CallState.Active) { r.state }
    return r
}

@Suppress("unused") val UNUSED_DESK = B_DESK
