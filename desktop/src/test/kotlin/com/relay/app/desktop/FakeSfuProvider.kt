package com.relay.app.desktop

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for the Cloudflare Realtime SFU, built from real webrtc-java peer connections. NOT Cloudflare: it forwards
 * decoded PCM from a publisher's inbound track into each subscriber's outbound track (no mixing, as on the real SFU).
 * Used behind [SfuApi] for peer tests and, over HTTP in provider format, behind the real local Worker in the gated E2E.
 */
class FakeSfuProvider : AutoCloseable {
    private val module = HeadlessAudioDeviceModule()
    private val factory = PeerConnectionFactory(module)
    private class Sess(val id: String, val pc: RTCPeerConnection) { val ownedTracks = ArrayList<AudioTrack>() }
    private val sessions = ConcurrentHashMap<String, Sess>()
    private val taps = ConcurrentHashMap<String, CopyOnWriteArrayList<CustomAudioSource>>()
    private val counter = AtomicInteger()

    val log = CopyOnWriteArrayList<String>()
    val inFlight = AtomicInteger(); val maxInFlight = AtomicInteger()
    @Volatile var gate: (String) -> Unit = {}
    @Volatile var failOps = false

    data class Tracks(val sessionId: String, val tracks: List<Pair<String, String>>, val sdpType: String?, val sdp: String?, val renegotiate: Boolean)

    private inline fun <T> op(name: String, block: () -> T): T {
        val n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n, ::maxOf)
        try { log += name; gate(name); if (failOps) error("provider down"); return block() } finally { inFlight.decrementAndGet() }
    }

    fun newSession(): String = op("sessions-new") {
        val id = "sess" + counter.incrementAndGet()
        val pc = factory.createPeerConnection(RTCConfiguration(), object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) { }
            override fun onTrack(transceiver: RTCRtpTransceiver) {
                (transceiver.receiver.track as? AudioTrack)?.addSink(AudioTrackSink { data, bits, rate, channels, frames ->
                    taps[id]?.forEach { runCatching { it.pushAudio(data, bits, rate, channels, frames) } }
                })
            }
        })!!
        sessions[id] = Sess(id, pc); id
    }

    /** Publisher side: [offerSdp] carries the local mic; returns the answer. */
    fun publish(sessionId: String, offerSdp: String, mid: String): Tracks = op("tracks-new:local") {
        val s = sessions.getValue(sessionId)
        taps.putIfAbsent(sessionId, CopyOnWriteArrayList())
        set(s.pc, RTCSessionDescription(RTCSdpType.OFFER, offerSdp), remote = true)
        val answer = answer(s.pc); set(s.pc, answer, remote = false)
        Tracks(sessionId, listOf(mid to "mic"), "answer", gathered(s.pc), false)
    }

    /** Subscriber side: adds one outbound track per requested publisher session and returns the provider's offer. */
    fun pull(sessionId: String, publishers: List<String>): Tracks = op("tracks-new:remote") {
        val s = sessions.getValue(sessionId)
        val tracks = publishers.map { pub ->
            sessions[pub] ?: throw IllegalArgumentException("unknown publisher")
            val source = CustomAudioSource(); taps.getOrPut(pub) { CopyOnWriteArrayList() } += source
            val track = factory.createAudioTrack("fwd${counter.incrementAndGet()}", source); s.ownedTracks += track
            s.pc.addTrack(track, listOf("sfu")); track
        }
        val offer = offer(s.pc); set(s.pc, offer, remote = false)
        val mids = tracks.map { t -> s.pc.transceivers.first { it.sender.track?.id == t.id }.mid to "mic" }
        Tracks(sessionId, mids, "offer", gathered(s.pc), true)
    }

    fun renegotiate(sessionId: String, answerSdp: String) = op("renegotiate") {
        set(sessions.getValue(sessionId).pc, RTCSessionDescription(RTCSdpType.ANSWER, answerSdp), remote = true)
    }

    fun close(sessionId: String, mids: List<String>) = op("tracks-close") { /* best effort: nothing to release in the fake */ }

    // ---- Worker-shaped facade (what DesktopSfuPeer's SfuApi sees) ------------------------------------------------

    val workerApi = SfuApi { opName, body ->
        when (opName) {
            "sessions-new" -> JSONObject().put("v", 1).put("sessionId", newSession())
            "tracks-new" -> {
                val session = body.getString("sessionId"); val tracks = body.getJSONArray("tracks")
                val r = if (body.has("sessionDescription")) {
                    val mid = tracks.getJSONObject(0).getString("mid")
                    publish(session, body.getJSONObject("sessionDescription").getString("sdp"), mid)
                } else pull(session, (0 until tracks.length()).map { tracks.getJSONObject(it).getString("sessionId") })
                JSONObject().put("v", 1).put("sessionId", session)
                    .put("tracks", JSONArray(r.tracks.map { JSONObject().put("mid", it.first).put("trackName", it.second) }))
                    .put("sessionDescription", JSONObject().put("type", r.sdpType).put("sdp", r.sdp))
                    .put("requiresImmediateRenegotiation", r.renegotiate)
            }
            "renegotiate" -> { renegotiate(body.getString("sessionId"), body.getString("answer")); JSONObject().put("v", 1) }
            "tracks-close" -> { close(body.getString("sessionId"), body.getJSONArray("mids").let { a -> (0 until a.length()).map { a.getString(it) } }); JSONObject().put("v", 1) }
            else -> throw SfuException(400, "bad_request", "op")
        }
    }

    // ---- native helpers ---------------------------------------------------------------------------------

    private fun offer(pc: RTCPeerConnection): RTCSessionDescription {
        val f = CompletableFuture<RTCSessionDescription>()
        pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(d: RTCSessionDescription) { f.complete(d) }
            override fun onFailure(e: String) { f.completeExceptionally(IllegalStateException(e)) }
        }); return f.get(15, TimeUnit.SECONDS)
    }
    private fun answer(pc: RTCPeerConnection): RTCSessionDescription {
        val f = CompletableFuture<RTCSessionDescription>()
        pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(d: RTCSessionDescription) { f.complete(d) }
            override fun onFailure(e: String) { f.completeExceptionally(IllegalStateException(e)) }
        }); return f.get(15, TimeUnit.SECONDS)
    }
    private fun set(pc: RTCPeerConnection, d: RTCSessionDescription, remote: Boolean) {
        val f = CompletableFuture<Unit>()
        val o = object : SetSessionDescriptionObserver {
            override fun onSuccess() { f.complete(Unit) }
            override fun onFailure(e: String) { f.completeExceptionally(IllegalStateException(e)) }
        }
        if (remote) pc.setRemoteDescription(d, o) else pc.setLocalDescription(d, o)
        f.get(15, TimeUnit.SECONDS)
    }
    /** The local description once candidate gathering finished (no trickle: like the real SFU's answer). */
    private fun gathered(pc: RTCPeerConnection): String {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (pc.iceGatheringState != RTCIceGatheringState.COMPLETE && System.nanoTime() < end) Thread.sleep(20)
        return pc.localDescription.sdp
    }

    override fun close() {
        sessions.values.forEach { s ->
            runCatching { s.pc.close() }
            s.ownedTracks.forEach { runCatching { it.dispose() } }
        }
        sessions.clear()
        runCatching { factory.dispose() }; runCatching { module.dispose() }
    }
}
