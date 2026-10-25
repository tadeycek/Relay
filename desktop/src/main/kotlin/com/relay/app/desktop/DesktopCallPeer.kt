package com.relay.app.desktop

import com.relay.app.call.*
import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.AudioDevice
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

/** Native webrtc-java 0.20.0 peer. All public operations run on the host's serial executor. */
class DesktopCallPeer(
    private val post: (CallEvent) -> Unit,
    private val audio: () -> AudioDeviceModuleBase = ::AudioDeviceModule,
    private val makeSource: (PeerConnectionFactory) -> AudioTrackSource = { it.createAudioSource(AudioOptions().apply {
        echoCancellation = true; noiseSuppression = true; autoGainControl = true; highpassFilter = true
    }) },
    private val onPcm: (ByteArray) -> Unit = {},
    private val callbackGate: (String, () -> Unit) -> Unit = { _, run -> run() },
    private val makeFactory: (AudioDeviceModuleBase) -> PeerConnectionFactory = { PeerConnectionFactory(it) },
) : PeerConnectionPort, AutoCloseable {
    private var sink: ((CallEvent) -> Unit)? = null
    @Volatile private var session: Session? = null
    private class Session(val id: String, val module: AudioDeviceModuleBase, val factory: PeerConnectionFactory,
        val connection: RTCPeerConnection, val source: AudioTrackSource, val track: AudioTrack,
        val sender: RTCRtpSender) {
        val closed = AtomicBoolean(false)
        /** Guards lifecycle/state only. Callbacks take this lock, NEVER the peer monitor: public operations hold
         *  the monitor across synchronous native calls that wait for the signaling thread running callbacks. */
        val lock = java.util.concurrent.locks.ReentrantLock()
        @Volatile var gen = 0
        @Volatile var remoteReady = false
        val ice = ArrayDeque<RTCIceCandidate>()
        val remoteTracks = java.util.concurrent.CopyOnWriteArrayList<AudioTrack>()
        val receivers = java.util.concurrent.CopyOnWriteArrayList<RTCRtpReceiver>()
        val transceivers = java.util.concurrent.CopyOnWriteArrayList<RTCRtpTransceiver>()
    }
    override fun attach(sink: (CallEvent) -> Unit) { this.sink = sink }
    private fun emit(event: CallEvent) = post(event)
    private fun alive(s: Session, gen: Int = s.gen) = session === s && !s.closed.get() && s.gen == gen
    /** Public (serial-host) thread failure: tear down, then report. */
    private fun fail(s: Session, gen: Int) { if (alive(s, gen)) { close(s.id); emit(CallEvent.PeerFailed(s.id)) } }
    /** Native-callback failure: never disposes from inside the callback; the engine answers PeerFailed with close(). */
    private fun failAsync(s: Session, gen: Int) { s.lock.withLock { if (alive(s, gen)) emit(CallEvent.PeerFailed(s.id)) } }
    private inline fun <T> Session.guard(block: () -> T): T = lock.withLock(block)
    private fun fail(id: String) { close(id); emit(CallEvent.PeerFailed(id)) }

    @Synchronized override fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean) {
        if (session != null) { fail(callId); return }
        var module: AudioDeviceModuleBase? = null
        var factory: PeerConnectionFactory? = null
        var connection: RTCPeerConnection? = null
        var source: AudioTrackSource? = null
        var track: AudioTrack? = null
        var sender: RTCRtpSender? = null
        // Callbacks resolve the session through this holder (not `session`, nulled by close) so only s.closed fences them.
        val holder = java.util.concurrent.atomic.AtomicReference<Session?>()
        try {
            module = audio() // first acquisition: never constructed before OpenPeerConnection
            factory = makeFactory(module)
            val config = RTCConfiguration().apply {
                iceTransportPolicy = if (relayOnly) RTCIceTransportPolicy.RELAY else RTCIceTransportPolicy.ALL
                iceServers.forEach { server -> addIceServer(server) }
            }
            connection = factory.createPeerConnection(config, object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    if (candidate.sdp.isNotBlank()) {
                        val s = holder.get() ?: return
                        s.guard { if (!s.closed.get()) emit(CallEvent.LocalCandidate(callId, s.gen, IceCandidate(candidate.sdpMid ?: "", candidate.sdpMLineIndex, candidate.sdp))) }
                    }
                }
                override fun onIceConnectionChange(state: RTCIceConnectionState) {
                    val mapped = when (state) {
                        RTCIceConnectionState.CONNECTED, RTCIceConnectionState.COMPLETED -> IceState.CONNECTED
                        RTCIceConnectionState.DISCONNECTED -> IceState.DISCONNECTED
                        RTCIceConnectionState.FAILED -> IceState.FAILED
                        RTCIceConnectionState.CLOSED -> IceState.CLOSED
                        else -> return
                    }
                    val s = holder.get() ?: return
                    s.guard { if (!s.closed.get()) emit(CallEvent.PeerState(callId, mapped)) }
                }
                override fun onTrack(transceiver: RTCRtpTransceiver) {
                    callbackGate("track") {
                    val s = holder.get() ?: return@callbackGate
                    s.guard {
                        if (s.closed.get()) return@guard
                        s.transceivers.add(transceiver)
                        val receiver = transceiver.receiver
                        s.receivers.add(receiver)
                        (receiver.track as? AudioTrack)?.let { track ->
                            s.remoteTracks.add(track)
                            track.addSink(AudioTrackSink { data, _, _, _, _ -> onPcm(data) })
                        }
                    }
                    }
                }
            })
            source = makeSource(factory)
            track = factory.createAudioTrack("relay-audio", source)
            sender = connection.addTrack(track, listOf("relay"))
            session = Session(callId, module, factory, connection, source, track, sender).also(holder::set)
        } catch (t: Throwable) {
            runCatching { sender?.dispose() }; runCatching { track?.dispose() }; runCatching { source?.dispose() }
            runCatching { connection?.close() }; runCatching { factory?.dispose() }; runCatching { module?.dispose() }
            // OOM/StackOverflow are not recoverable here; link/class-load Errors are reported as PeerFailed.
            if (t is VirtualMachineError) throw t
            fail(callId)
        }
    }
    private fun RTCConfiguration.addIceServer(s: IceServer) {
        iceServers.add(RTCIceServer().apply { urls.addAll(s.urls); username = s.username; password = s.credential })
    }
    @Synchronized override fun createOffer(callId: String, gen: Int, iceRestart: Boolean) {
        val s = session?.takeIf { it.id == callId } ?: return fail(callId)
        s.guard { s.gen = gen; s.remoteReady = false; s.ice.clear() }
        try {
            s.connection.createOffer(RTCOfferOptions().apply { this.iceRestart = iceRestart }, object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) { s.guard { if (alive(s, gen)) setLocal(s, gen, description, SdpKind.OFFER) } }
                override fun onFailure(error: String) { failAsync(s, gen) }
            })
        } catch (_: Exception) { fail(s, gen) }
    }
    private fun setLocal(s: Session, gen: Int, description: RTCSessionDescription, kind: SdpKind) {
        if (!alive(s, gen)) return
        try {
            s.connection.setLocalDescription(description, object : SetSessionDescriptionObserver {
                override fun onSuccess() { callbackGate("local-$gen") { s.guard { if (alive(s, gen)) emit(CallEvent.LocalDescription(s.id, gen, kind, description.sdp)) } } }
                override fun onFailure(error: String) { failAsync(s, gen) }
            })
        } catch (_: Exception) { failAsync(s, gen) }
    }
    @Synchronized override fun applyRemoteOffer(callId: String, gen: Int, sdp: String) {
        val s = session?.takeIf { it.id == callId } ?: return fail(callId)
        s.guard { s.gen = gen; s.remoteReady = false; s.ice.clear() }
        // Unified Plan implicit rollback is enabled for glare; do not race explicit async rollback with setRemote.
        try { s.connection.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, sdp), object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                callbackGate("remote-offer-$gen") {
                s.guard {
                    if (!alive(s, gen)) return@guard
                    flush(s, gen)
                    if (!alive(s, gen)) return@guard
                    s.connection.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                        override fun onSuccess(description: RTCSessionDescription) { s.guard { if (alive(s, gen)) setLocal(s, gen, description, SdpKind.ANSWER) } }
                        override fun onFailure(error: String) { failAsync(s, gen) }
                    })
                }
                }
            }
            override fun onFailure(error: String) { failAsync(s, gen) }
        }) } catch (_: Exception) { fail(s, gen) }
    }
    @Synchronized override fun applyRemoteAnswer(callId: String, gen: Int, sdp: String) {
        val s = session?.takeIf { it.id == callId } ?: return fail(callId)
        s.guard { s.gen = gen; s.remoteReady = false }
        try { s.connection.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, sdp), object : SetSessionDescriptionObserver {
            override fun onSuccess() { callbackGate("remote-answer-$gen") { s.guard { if (alive(s, gen)) flush(s, gen) } } }
            override fun onFailure(error: String) { failAsync(s, gen) }
        }) } catch (_: Exception) { fail(s, gen) }
    }
    private fun flush(s: Session, gen: Int) {
        s.guard {
            if (s.closed.get() || s.gen != gen) return@guard
            s.remoteReady = true
            try { while (s.ice.isNotEmpty()) s.connection.addIceCandidate(s.ice.removeFirst()) }
            catch (_: Exception) { failAsync(s, gen) }
        }
    }
    @Synchronized override fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>) {
        val s = session?.takeIf { it.id == callId && it.gen == gen } ?: return
        val now = ArrayList<RTCIceCandidate>()
        var overflow = false
        s.guard {
            if (s.closed.get()) return
            for (c in candidates) {
                val candidate = RTCIceCandidate(c.mid, c.index, c.candidate)
                if (s.remoteReady) now.add(candidate)
                else if (s.ice.size < 256) s.ice.addLast(candidate) else { overflow = true; break }
            }
        }
        if (overflow) return fail(s, gen)
        // Native add runs outside s.lock: it may wait on the signaling thread, which may be waiting for s.lock.
        try { now.forEach { if (alive(s, gen)) s.connection.addIceCandidate(it) } } catch (_: Exception) { fail(s, gen) }
    }
    @Synchronized override fun setMicMuted(callId: String, muted: Boolean) { session?.takeIf { it.id == callId && !it.closed.get() }?.track?.setEnabled(!muted) }
    override fun close(callId: String) {
        val s = synchronized(this) {
            val current = session?.takeIf { it.id == callId } ?: return
            session = null
            current
        }
        s.guard { // callbacks hold this lock for every check+effect, so none can attach after this returns
            if (!s.closed.compareAndSet(false, true)) return
            s.ice.clear()
        }
        runCatching { s.track.setEnabled(false) }
        runCatching { s.connection.close() }
        runCatching { s.remoteTracks.forEach { it.dispose() } }
        runCatching { s.receivers.forEach { it.dispose() } }
        runCatching { s.transceivers.forEach { it.dispose() } }
        runCatching { s.sender.dispose() }
        runCatching { s.track.dispose() }; runCatching { s.source.dispose() }
        runCatching { s.factory.dispose() }; runCatching { s.module.dispose() }
    }
    override fun close() { session?.id?.let(::close) }

    /** Native inbound audio RTP counters, sampled before disposal by the headless test. */
    fun inboundAudioRtp(): java.util.concurrent.CompletableFuture<Pair<Long, Long>> {
        val result = java.util.concurrent.CompletableFuture<Pair<Long, Long>>()
        synchronized(this) {
            val s = session ?: return result.apply { completeExceptionally(IllegalStateException("No call")) }
            try {
                s.connection.getStats { report ->
                    val audio = report.stats.values.filter { stat ->
                        stat.type == RTCStatsType.INBOUND_RTP &&
                            (stat.attributes["kind"] == "audio" || stat.attributes["mediaType"] == "audio")
                    }
                    result.complete(audio.sumOf { (it.attributes["packetsReceived"] as? Number)?.toLong() ?: 0L } to
                        audio.sumOf { (it.attributes["bytesReceived"] as? Number)?.toLong() ?: 0L })
                }
            } catch (t: Throwable) { result.completeExceptionally(t) }
        }
        return result
    }

    /** Device picker seam; enumerate only when invoked, and fail if a descriptor is no longer present. */
    @Synchronized fun devices(recording: Boolean): List<Pair<String, String>> {
        val m = session?.module ?: return emptyList()
        return (if (recording) m.recordingDevices else m.playoutDevices).map { it.descriptor to it.name }
    }
    @Synchronized fun selectDevice(recording: Boolean, descriptor: String): Boolean {
        val m = session?.module ?: return false
        val found: AudioDevice = (if (recording) m.recordingDevices else m.playoutDevices)
            .firstOrNull { it.descriptor == descriptor } ?: return false
        if (recording) m.setRecordingDevice(found) else m.setPlayoutDevice(found)
        return true
    }
}
