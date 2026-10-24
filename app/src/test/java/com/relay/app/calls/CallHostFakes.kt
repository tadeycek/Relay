package com.relay.app.calls

import com.relay.app.call.CallEvent
import com.relay.app.call.CallState
import com.relay.app.call.CallEffect
import com.relay.app.call.CallTimer
import com.relay.app.call.ClockPort
import com.relay.app.call.IceCandidate
import com.relay.app.call.IceServer
import com.relay.app.call.PeerConnectionPort
import com.relay.app.call.PeerRole
import com.relay.app.call.RandomPort
import com.relay.app.call.SignalMode
import com.relay.app.call.SignalPort
import com.relay.app.call.TimerPort
import com.relay.app.call.TurnCredentialPort
import org.json.JSONObject

/** Deterministic ports for the host tests: real codec and real engine, fake platform. */
class TestClock(var now: Long = 1_800_000_000_000) : ClockPort {
    override fun nowMs(): Long = now
}

class SeqRandom : RandomPort {
    private var n = 0
    override fun hex(bytes: Int): String {
        n += 1
        return (n.toString(16).padStart(bytes * 2, '0')).takeLast(bytes * 2)
    }
}

/**
 * Records every outgoing signal. The wire is inspected as the codec's own base64 JSON (not re-decoded
 * with a receive context) so a test can assert on `op`, `to`, `device` and `mode` without depending on
 * the binding rules the engine already tests.
 */
class RecordingSignal : SignalPort {
    data class Sent(val to: String, val wire: String, val mode: SignalMode) {
        val json: JSONObject by lazy {
            val b64 = wire.removePrefix("TYPE:CALL|J:")
            JSONObject(String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8))
        }
        val op: String get() = json.getString("op")
        val call: String get() = json.getString("call")
    }
    val sent = mutableListOf<Sent>()
    override fun send(toIdentity: String, wire: String, mode: SignalMode) {
        sent += Sent(toIdentity, wire, mode)
    }
    fun ops(): List<String> = sent.map { it.op }
}

class RecordingTurn : TurnCredentialPort {
    private var sink: ((CallEvent) -> Unit)? = null
    val requested = mutableListOf<String>()
    override fun attach(sink: (CallEvent) -> Unit) { this.sink = sink }
    override fun request(callId: String) { requested += callId }
    fun ready(callId: String, servers: List<IceServer> = emptyList()) = sink!!(CallEvent.IceServersReady(callId, servers))
    fun failed(callId: String) = sink!!(CallEvent.IceServersFailed(callId))
}

class RecordingPeer : PeerConnectionPort {
    private var sink: ((CallEvent) -> Unit)? = null
    val opened = mutableListOf<String>()
    val closed = mutableListOf<String>()
    var micMuted: Boolean? = null
    override fun attach(sink: (CallEvent) -> Unit) { this.sink = sink }
    override fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean) { opened += callId }
    override fun createOffer(callId: String, gen: Int, iceRestart: Boolean) = Unit
    override fun applyRemoteOffer(callId: String, gen: Int, sdp: String) = Unit
    override fun applyRemoteAnswer(callId: String, gen: Int, sdp: String) = Unit
    override fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>) = Unit
    override fun setMicMuted(callId: String, muted: Boolean) { micMuted = muted }
    override fun close(callId: String) { closed += callId }
    fun peerState(callId: String, state: com.relay.app.call.IceState) = sink!!(CallEvent.PeerState(callId, state))
    fun localOffer(callId: String, gen: Int = 0, sdp: String = "v=0\r\n") =
        sink!!(CallEvent.LocalDescription(callId, gen, com.relay.app.call.SdpKind.OFFER, sdp))
}

class RecordingTimers : TimerPort {
    private var sink: ((CallEvent) -> Unit)? = null
    val armed = mutableMapOf<Pair<String, CallTimer>, Long>()
    override fun attach(sink: (CallEvent) -> Unit) { this.sink = sink }
    override fun start(callId: String, timer: CallTimer, delayMs: Long) { armed[callId to timer] = delayMs }
    override fun cancel(callId: String, timer: CallTimer) { armed.remove(callId to timer) }
    fun fire(callId: String, timer: CallTimer) = sink!!(CallEvent.TimerFired(callId, timer))
}

class RecordingUi : com.relay.app.call.CallUiPort {
    val rings = mutableListOf<Pair<String, Boolean>>()
    val stoppedRings = mutableListOf<String>()
    val states = mutableListOf<CallState>()
    val records = mutableListOf<CallEffect.RecordCallEvent>()
    val rejected = mutableListOf<String>()
    override fun startRing(callId: String, incoming: Boolean) { rings += (callId to incoming) }
    override fun stopRing(callId: String) { stoppedRings += callId }
    override fun stateChanged(state: CallState) { states += state }
    override fun record(event: CallEffect.RecordCallEvent) { records += event }
    override fun commandRejected(reason: String) { rejected += reason }
    fun lastState(): CallState = states.last()
}
