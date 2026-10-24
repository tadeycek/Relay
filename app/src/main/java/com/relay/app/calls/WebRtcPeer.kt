package com.relay.app.calls

import android.content.Context
import android.util.Log
import com.relay.app.call.CallEvent
import com.relay.app.call.IceCandidate
import com.relay.app.call.IceServer
import com.relay.app.call.IceState
import com.relay.app.call.PeerConnectionPort
import com.relay.app.call.PeerRole
import com.relay.app.call.SdpKind
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate as RtcIceCandidate
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `PeerConnectionPort` over the native WebRTC library the call spikes already use
 * (`io.github.webrtc-sdk:android`).
 *
 * Audio only. The microphone is opened by [open] — never earlier — and released by [close], which is
 * idempotent. WebRTC's own DTLS-SRTP handles the E2EE binding; the SDP fingerprint verification the spec
 * relies on is libwebrtc's.
 *
 * Every event is posted through [post], which must land on the engine's single serial context
 * (`CALL_ENGINE.md` rule 5): the engine never waits for the platform, so this class is asynchronous and
 * re-entrant-safe.
 */
class WebRtcPeer(
    private val context: Context,
    private val post: (CallEvent) -> Unit,
) : PeerConnectionPort {

    private var sink: ((CallEvent) -> Unit)? = null
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null

    private class Session(
        val connection: PeerConnection,
        val track: AudioTrack,
        val source: AudioSource,
        val remoteCandidates: RemoteCandidateQueue<RtcIceCandidate>,
        val gen: AtomicInteger = AtomicInteger(0),
    ) {
        @Volatile var closed = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    override fun attach(sink: (CallEvent) -> Unit) {
        this.sink = sink
    }

    override fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean) {
        if (sessions.containsKey(callId)) return
        val guard = OpenGuard()
        try {
            val f = factory ?: buildFactory().also { factory = it }
            val config = PeerConnection.RTCConfiguration(iceServers.map { it.toRtc() }).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            }
            val connection = f.createPeerConnection(config, Observer(callId))
                ?: throw IllegalStateException("createPeerConnection returned null")
            guard.track(connection) { it.close(); it.dispose() }
            val source = f.createAudioSource(audioConstraints())
            guard.track(source) { it.dispose() }
            val track = f.createAudioTrack("relay-audio", source)
            guard.track(track) { it.setEnabled(false); it.dispose() }
            track.setEnabled(true)
            connection.addTrack(track, listOf("relay"))
            sessions[callId] = Session(connection, track, source,
                RemoteCandidateQueue(256) { candidate ->
                    if (sessions.containsKey(callId)) runCatching { connection.addIceCandidate(candidate) }
                })
            guard.commit()
            Log.i(TAG, "peer opened ($role, relayOnly=$relayOnly, ${iceServers.size} ICE servers)")
        } catch (e: Exception) {
            Log.e(TAG, "could not open the peer connection", e)
            guard.rollback() // releases the source/track/connection that never reached a stored session
            post(CallEvent.PeerFailed(callId))
        }
    }

    override fun createOffer(callId: String, gen: Int, iceRestart: Boolean) {
        val session = sessions[callId] ?: return fail(callId)
        session.gen.set(gen)
        session.remoteCandidates.begin(gen)
        try {
            val constraints = audioConstraints().apply {
                if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("iceRestart", "true"))
            }
            session.connection.createOffer(object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    val tuned = SessionDescription(description.type, CallSdp.audioTuning(description.description))
                    session.connection.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() = report(callId, gen, SdpKind.OFFER, tuned.description)
                        override fun onSetFailure(error: String?) = fail(callId, error)
                    }, tuned)
                }

                override fun onCreateFailure(error: String?) = fail(callId, error)
            }, constraints)
        } catch (e: Exception) {
            fail(callId, e.message)
        }
    }

    override fun applyRemoteOffer(callId: String, gen: Int, sdp: String) {
        val session = sessions[callId] ?: return fail(callId)
        session.gen.set(gen)
        session.remoteCandidates.begin(gen)
        try {
            // Roll back any local pending offer before taking theirs; Unified Plan supports it and a
            // failure here is not fatal (there is usually nothing to roll back).
            runCatching {
                session.connection.setLocalDescription(SdpObserverAdapter(), SessionDescription(SessionDescription.Type.ROLLBACK, ""))
            }
            val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
            session.connection.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    session.remoteCandidates.remoteDescriptionSet(gen)
                    session.connection.createAnswer(object : SdpObserverAdapter() {
                        override fun onCreateSuccess(description: SessionDescription) {
                            val tuned = SessionDescription(description.type, CallSdp.audioTuning(description.description))
                            session.connection.setLocalDescription(object : SdpObserverAdapter() {
                                override fun onSetSuccess() = report(callId, gen, SdpKind.ANSWER, tuned.description)
                                override fun onSetFailure(error: String?) = fail(callId, error)
                            }, tuned)
                        }

                        override fun onCreateFailure(error: String?) = fail(callId, error)
                    }, audioConstraints())
                }

                override fun onSetFailure(error: String?) = fail(callId, error)
            }, offer)
        } catch (e: Exception) {
            fail(callId, e.message)
        }
    }

    override fun applyRemoteAnswer(callId: String, gen: Int, sdp: String) {
        val session = sessions[callId] ?: return fail(callId)
        session.gen.set(gen)
        session.remoteCandidates.begin(gen)
        try {
            session.connection.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() = session.remoteCandidates.remoteDescriptionSet(gen)
                override fun onSetFailure(error: String?) = fail(callId, error)
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        } catch (e: Exception) {
            fail(callId, e.message)
        }
    }

    override fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>) {
        val session = sessions[callId] ?: return fail(callId)
        if (session.gen.get() != gen) return // candidates for an older generation are stale
        session.remoteCandidates.add(gen, candidates.map { RtcIceCandidate(it.mid, it.index, it.candidate) })
    }

    override fun setMicMuted(callId: String, muted: Boolean) {
        sessions[callId]?.track?.setEnabled(!muted)
    }

    /** Closes the peer connection and releases the microphone. Idempotent. */
    override fun close(callId: String) {
        val session = sessions.remove(callId) ?: return
        if (session.closed) return
        session.closed = true
        session.remoteCandidates.close()
        runCatching { session.track.setEnabled(false) }
        runCatching { session.connection.close() }
        runCatching { session.track.dispose() }
        runCatching { session.source.dispose() }
        runCatching { session.connection.dispose() }
        Log.i(TAG, "peer closed and microphone released")
    }

    /** Releases the process-wide factory; call when the last call is gone (e.g. app shutdown). */
    fun releaseFactory() {
        sessions.keys.toList().forEach(::close)
        runCatching { factory?.dispose() }
        runCatching { audioModule?.release() }
        factory = null
        audioModule = null
    }

    private fun report(callId: String, gen: Int, kind: SdpKind, sdp: String) {
        post(CallEvent.LocalDescription(callId, gen, kind, sdp))
    }

    private fun fail(callId: String, message: String? = null) {
        Log.e(TAG, "peer failure for $callId: ${message ?: "unknown"}")
        close(callId)
        post(CallEvent.PeerFailed(callId))
    }

    private fun buildFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        audioModule = adm
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "false"))
        optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
    }

    private inner class Observer(private val callId: String) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: RtcIceCandidate?) {
            val c = candidate ?: return
            // The empty end-of-candidates marker is dropped: the codec rejects it.
            if (c.sdp.isNullOrBlank() || c.sdp.isEmpty()) return
            val gen = sessions[callId]?.gen?.get() ?: return
            post(CallEvent.LocalCandidate(callId, gen, IceCandidate(c.sdpMid ?: "", c.sdpMLineIndex, c.sdp)))
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            val mapped = when (newState) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> IceState.CONNECTED
                PeerConnection.IceConnectionState.DISCONNECTED -> IceState.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> IceState.FAILED
                PeerConnection.IceConnectionState.CLOSED -> IceState.CLOSED
                else -> return
            }
            post(CallEvent.PeerState(callId, mapped))
        }

        override fun onIceCandidatesRemoved(candidates: Array<out RtcIceCandidate>?) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        override fun onTrack(transceiver: RtpTransceiver?) = Unit
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private fun IceServer.toRtc(): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(urls)
        username?.let(builder::setUsername)
        credential?.let(builder::setPassword)
        return builder.createIceServer()
    }

    private companion object { const val TAG = "RelayCallPeer" }
}
