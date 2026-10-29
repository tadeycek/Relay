package com.relay.app.calls.group

import android.content.Context
import android.util.Log
import com.relay.app.call.IceServer
import com.relay.app.calls.TurnResponse
import com.relay.app.calls.TurnResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.relay.app.call.group.e2ee.ParticipantDevice
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DataChannel
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One ICE server exactly as it will be handed to `org.webrtc`: TURN URLs keep their issued transport
 * (`turn:` = UDP, `turns:` = TLS) and their short-lived credentials. [toString] never prints the credential.
 */
data class GroupIceServer(val urls: List<String>, val username: String?, val credential: String?) {
    val isTurn: Boolean get() = urls.any { it.startsWith("turn", ignoreCase = true) }

    override fun toString(): String =
        "GroupIceServer(urls=$urls, username=${if (username == null) "none" else "<issued>"}, credential=<redacted>)"

    fun toRtc(): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(urls)
        if (username != null && credential != null) builder.setUsername(username).setPassword(credential)
        return builder.createIceServer()
    }
}

/**
 * Maps the issued `GET /v1/call/ice` answer onto the peer's ICE servers — the exact same credential endpoint and
 * parser the 1:1 calls use ([TurnResponse]), so group rooms need no new Worker API.
 *
 * - TURN entries keep the credentials and every issued URL (UDP and TLS when the API returns both), because a
 *   strict NAT or a mobile network needs the relay; STUN is only the last-resort fallback.
 * - A TURN entry without a username+credential is dropped: it can never authenticate, and a half-configured
 *   relay that silently fails is worse than no relay at all.
 * - [plan] returns null when the relay is required but the answer has no usable TURN server, so the caller fails
 *   with an honest error instead of publishing to a peer across a NAT that can never connect.
 */
object GroupSfuIce {
    const val STUN_FALLBACK = "stun:stun.cloudflare.com:3478"
    private const val MAX_SERVERS = 8
    private const val MAX_URLS = 8
    private const val MAX_CREDENTIAL = 512
    private val URL = Regex("^(stun|turn|turns):[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{3,480}$", RegexOption.IGNORE_CASE)

    fun plan(issued: List<IceServer>, requireTurn: Boolean): List<GroupIceServer>? {
        val out = ArrayList<GroupIceServer>(issued.size + 1)
        for (server in issued.take(MAX_SERVERS)) {
            val urls = server.urls.filter { URL.matches(it) }.distinct().take(MAX_URLS)
            if (urls.isEmpty()) continue
            val username = server.username?.takeIf { it.isNotBlank() && it.length <= MAX_CREDENTIAL }
            val credential = server.credential?.takeIf { it.isNotBlank() && it.length <= MAX_CREDENTIAL }
            if (urls.any { it.startsWith("turn", ignoreCase = true) } && (username == null || credential == null)) continue
            out += GroupIceServer(urls, username, credential)
        }
        if (requireTurn && out.none { it.isTurn }) return null
        if (out.none { s -> s.urls.any { it.startsWith("stun:", ignoreCase = true) } }) {
            out += GroupIceServer(listOf(STUN_FALLBACK), null, null)
        }
        return out
    }
}

/**
 * One Android WebRTC peer for an SFU session: one local microphone track and every remote publisher track
 * the SFU hands us on the same peer connection.
 *
 * The Worker proxy exchanges SDP only (no trickle ICE operation), so both the offer and the answer are
 * gathered to completion before they are returned; anything later is already inside the SDP. That is the
 * equivalent of WP13's [RemoteCandidateQueue] for the SFU path: [addCandidate] is kept for a peer that does
 * deliver a candidate after the description, and is dropped once the generation changed.
 *
 * ICE servers come from the committed per-device TURN endpoint (`GET /v1/call/ice`, the 1:1 path): TURN with
 * short-lived credentials plus the STUN fallback. When [turnRequired] and the server issues no usable relay,
 * [prepareMicOffer] fails with `turn_unavailable` rather than publishing into a NAT the peer can't cross.
 *
 * The microphone is created only in [prepareMicOffer]; [reset] and [release] close the connection and
 * dispose the source/track exactly once. Audio levels for the speaking indicator come from the received
 * PCM through [AudioTrackSink], so the UI needs no microphone access for other people's speech.
 */
class GroupSfuPeer(context: Context, private val turnRequired: Boolean = true) : SfuPeer {

    private val app = context.applicationContext
    private val muteLock = Any()
    private val generation = AtomicInteger(0)
    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var connection: PeerConnection? = null
    private var source: AudioSource? = null
    private var micTrack: AudioTrack? = null

    private class Remote(val track: AudioTrack, val sink: AudioTrackSink, val key: String,
        val receiver: RtpReceiver?, val participant: ParticipantDevice)
    private val remotes = ConcurrentHashMap<String, Remote>()
    private val levels = ConcurrentHashMap<String, Double>()
    private val levelAt = ConcurrentHashMap<String, Long>()
    private val volumes = ConcurrentHashMap<String, Float>()
    @Volatile private var deafened = false
    /** What the user asked for; the actual track is only enabled while a sender cryptor is attached. */
    @Volatile private var micEnabled = true

    // ---- WP17 frame E2EE ---------------------------------------------------------------------------
    // The epoch key/provider live in GroupCallE2ee (one per call, one provider per epoch). A cryptor is
    // bound to one RtpSender/RtpReceiver, so it lives here, next to the track it belongs to, and dies with
    // the connection generation. Without a provider there is no cryptor and nothing is published.
    @Volatile private var e2ee: GroupCallE2ee? = null
    private var micSender: RtpSender? = null
    private var senderCryptor: FrameCryptor? = null
    private val receiverCryptors = HashMap<String, FrameCryptor>() // participant id -> cryptor

    /** Binds this call's frame-E2EE runtime. Replacing it (epoch change) drops every cryptor first. */
    fun bindE2ee(session: GroupCallE2ee?) {
        synchronized(muteLock) {
            disposeCryptorsLocked()
            e2ee = session
            val sender = micSender
            if (sender != null && session != null && installSenderCryptorLocked(sender)) {
                runCatching { micTrack?.setEnabled(micEnabled) }
            }
        }
    }

    /** A new epoch/provider is in place: every cryptor is rebuilt against it, the mic never opens unsealed. */
    fun applyEpoch() {
        synchronized(muteLock) {
            val session = e2ee ?: return
            val provider = session.provider()
            runCatching { micTrack?.setEnabled(false) }
            disposeCryptorsLocked()
            if (provider == null) return
            val sender = micSender
            val sealed = sender == null || installSenderCryptorLocked(sender)
            if (sealed) runCatching { micTrack?.setEnabled(micEnabled) }
            for (remote in remotes.values.toList()) {
                val receiver = remote.receiver ?: continue
                attachReceiverCryptorLocked(receiver, remote.participant, provider)
            }
        }
    }

    /**
     * Sender cryptor on the RtpSender `addTrack` returned. Kept under [muteLock]; returns false (and leaves the
     * mic disabled) when no provider exists or the native cryptor could not be created.
     */
    private fun installSenderCryptorLocked(sender: RtpSender): Boolean {
        val session = e2ee ?: return false
        val provider = session.provider() ?: return false
        val f = factory ?: return false
        return try {
            val cryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
                f, sender, session.self.frameCryptorParticipantId(), FrameCryptorAlgorithm.AES_GCM, provider)
            cryptor.setKeyIndex(session.keyIndex)
            cryptor.setObserver(object : FrameCryptor.Observer {
                override fun onFrameCryptionStateChanged(participantId: String?, state: FrameCryptor.FrameCryptionState?) {
                    onSendCryptionState(state)
                }
            })
            cryptor.setEnabled(true)
            val previous = senderCryptor
            senderCryptor = cryptor
            runCatching { previous?.dispose() }
            session.noteSenderInstalled()
            // Our own device is part of the coverage set: the cryptor is installed and sealing, so it is
            // verified at this epoch. (Frames we send are sealed from here on.)
            session.verify(session.self)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "sender cryptor failed: ${t.javaClass.simpleName}")
            session.noteSendFailure(GroupCallE2ee.SEND_FAILED)
            false
        }
    }

    /** Receiver cryptor for one publisher; the caller already resolved the participant from the room roster. */
    private fun attachReceiverCryptorLocked(receiver: RtpReceiver, participant: ParticipantDevice, provider: org.webrtc.FrameCryptorKeyProvider): Boolean {
        val session = e2ee ?: return false
        val f = factory ?: return false
        val id = participant.frameCryptorParticipantId()
        return try {
            val cryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
                f, receiver, id, FrameCryptorAlgorithm.AES_GCM, provider)
            cryptor.setKeyIndex(session.keyIndex)
            cryptor.setObserver(object : FrameCryptor.Observer {
                override fun onFrameCryptionStateChanged(participantId: String?, state: FrameCryptor.FrameCryptionState?) {
                    onReceiveCryptionState(participant, state)
                }
            })
            cryptor.setEnabled(true)
            runCatching { receiverCryptors.put(id, cryptor)?.dispose() }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "receiver cryptor failed: ${t.javaClass.simpleName}")
            session.invalidate(participant)
            false
        }
    }

    private fun disposeCryptorsLocked() {
        runCatching { senderCryptor?.dispose() }
        senderCryptor = null
        receiverCryptors.values.forEach { runCatching { it.dispose() } }
        receiverCryptors.clear()
    }

    private fun onSendCryptionState(state: FrameCryptor.FrameCryptionState?) {
        val session = e2ee ?: return
        when (state) {
            FrameCryptor.FrameCryptionState.OK, FrameCryptor.FrameCryptionState.KEYRATCHETED -> session.noteSendOk()
            FrameCryptor.FrameCryptionState.MISSINGKEY, FrameCryptor.FrameCryptionState.ENCRYPTIONFAILED,
            FrameCryptor.FrameCryptionState.INTERNALERROR -> {
                // Fail closed: never keep publishing audio that is not being sealed.
                synchronized(muteLock) { runCatching { micTrack?.setEnabled(false) } }
                session.noteSendFailure(GroupCallE2ee.SEND_FAILED)
            }
            else -> Unit
        }
    }

    private fun onReceiveCryptionState(participant: ParticipantDevice, state: FrameCryptor.FrameCryptionState?) {
        val session = e2ee ?: return
        when (state) {
            FrameCryptor.FrameCryptionState.OK, FrameCryptor.FrameCryptionState.KEYRATCHETED -> {
                // A frame that decrypted under this epoch's key is the proof GroupCallSecurityLabel wants.
                session.verify(participant)
                setRemotesEnabled(participant, true)
            }
            FrameCryptor.FrameCryptionState.DECRYPTIONFAILED, FrameCryptor.FrameCryptionState.MISSINGKEY,
            FrameCryptor.FrameCryptionState.INTERNALERROR -> {
                session.invalidate(participant)
                setRemotesEnabled(participant, false)
            }
            else -> Unit
        }
    }

    private fun setRemotesEnabled(participant: ParticipantDevice, enabled: Boolean) {
        val id = participant.frameCryptorParticipantId()
        remotes.values.filter { it.participant.frameCryptorParticipantId() == id }
            .forEach { runCatching { it.track.setEnabled(enabled) } }
    }

    /** Last PCM RMS (0..1) per publisher session (or track id when the SDP gave no session msid). */
    fun audioLevels(): Map<String, Double> {
        val now = System.nanoTime()
        levels.entries.removeIf { (key, _) -> now - (levelAt[key] ?: 0L) > LEVEL_TTL_NS }
        return levels.toMap()
    }

    override suspend fun prepareMicOffer(): MicOffer {
        // Fail closed before the microphone is opened: no key/provider, no publish. A plaintext track is
        // never created as a fallback.
        val session = e2ee ?: throw SfuFailure(0, "e2ee_missing", false)
        // canSend() is false when there is no provider, encryption failed, or a re-key could not be applied:
        // in all three cases the microphone must stay shut rather than fall back to plaintext.
        if (!session.canSend()) throw SfuFailure(0, "e2ee_unavailable", false)
        // Reuse the committed 1:1 TURN endpoint: short-lived per-device credentials, never hardcoded.
        val ice = GroupSfuIce.plan(issuedIceServers(), turnRequired)
            ?: throw SfuFailure(0, "turn_unavailable", true,
                "Group calls need the TURN relay, but this server didn't issue TURN credentials.")
        val gen = generation.incrementAndGet()
        val pc = newConnection(gen, ice)
        val offer = synchronized(muteLock) {
            check(connection === pc) { "peer replaced during offer" }
            val f = factory ?: error("factory missing")
            val src = f.createAudioSource(audioConstraints())
            val track = f.createAudioTrack("relay-group-mic", src)
            track.setEnabled(false) // opened muted: the task only starts once a cryptor seals it
            val rtpSender = pc.addTrack(track, listOf("relay-group"))
            source = src
            micTrack = track
            micSender = rtpSender
            if (!installSenderCryptorLocked(rtpSender)) {
                throw SfuFailure(0, "e2ee_failed", false)
            }
            track.setEnabled(micEnabled)
            Triple(src, track, pc)
        }
        val description = createOffer(offer.third)
        setLocal(offer.third, SessionDescription(SessionDescription.Type.OFFER, description))
        awaitGathering(offer.third, gen)
        val sdp = offer.third.getLocalDescription()?.description ?: throw SfuFailure(0, "no_local_sdp", false)
        return MicOffer(sdp, firstAudioMid(sdp) ?: "0")
    }

    override suspend fun applyAnswer(sdp: String) {
        val gen = generation.get()
        val pc = current(gen)
        setRemote(pc, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override suspend fun answerRemoteOffer(offerSdp: String): String {
        val gen = generation.get()
        val pc = current(gen)
        setRemote(pc, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        val answer = createAnswer(pc)
        setLocal(pc, SessionDescription(SessionDescription.Type.ANSWER, answer))
        awaitGathering(pc, gen)
        return pc.getLocalDescription()?.description ?: throw SfuFailure(0, "no_local_sdp", false)
    }

    override fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        synchronized(muteLock) {
            // A muted-by-policy track (no cryptor / failed encryption) stays muted whatever the user asks.
            val sealed = senderCryptor != null && e2ee?.canSend() == true
            runCatching { micTrack?.setEnabled(enabled && sealed) }
        }
    }

    override fun setDeafened(deafened: Boolean) {
        this.deafened = deafened
        applyPlayback()
    }

    override fun setParticipantVolume(publisherSessions: List<String>, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        publisherSessions.forEach { volumes[it] = v }
        applyPlayback()
    }

    override fun reset() {
        generation.incrementAndGet()
        closeConnection()
    }

    override fun release() {
        generation.incrementAndGet()
        closeConnection()
        synchronized(muteLock) {
            runCatching { factory?.dispose() }
            runCatching { audioModule?.release() }
            factory = null
            audioModule = null
        }
    }

    /** A late candidate from a remote description (kept for the shared WP13 pattern; the proxy does not trickle). */
    fun addCandidate(candidate: IceCandidate, gen: Int = generation.get()) {
        if (gen != generation.get()) return
        runCatching { connection?.addIceCandidate(candidate) }
    }

    private fun applyPlayback() {
        val snapshot = remotes.values.toList()
        for (remote in snapshot) {
            val volume = if (deafened) 0f else volumes[remote.key] ?: 1f
            runCatching { remote.track.setVolume(volume.toDouble()) }
        }
    }

    private fun current(gen: Int): PeerConnection {
        if (gen != generation.get()) throw SfuFailure(0, "disposed", false)
        return connection ?: throw SfuFailure(0, "no_connection", false)
    }

    private fun newConnection(gen: Int, ice: List<GroupIceServer>): PeerConnection {
        closeConnection()
        if (gen != generation.get()) throw SfuFailure(0, "disposed", false)
        val f = ensureFactory()
        val config = PeerConnection.RTCConfiguration(ice.map { it.toRtc() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }
        val pc = f.createPeerConnection(config, Observer(gen))
            ?: throw SfuFailure(0, "peer_create_failed", false)
        synchronized(muteLock) { connection = pc }
        return pc
    }

    // ---- TURN credentials (the committed 1:1 path) -------------------------------------------------

    @Volatile private var cachedIce: List<IceServer> = emptyList()
    @Volatile private var cachedIceExpiresAtMs = 0L

    /**
     * `GET /v1/call/ice`, NIP-98 signed with the enrolled device key exactly like the 1:1 calls, parsed by the
     * same [TurnResponse]. A cached answer is reused until shortly before it expires, so a rebuild does not
     * mint a new credential per attempt. An empty list means "no relay issued"; the caller decides whether
     * that is fatal. Nothing here logs or returns the credential anywhere but the peer configuration.
     */
    private suspend fun issuedIceServers(): List<IceServer> {
        val cached = cachedIce
        if (cached.isNotEmpty() && System.currentTimeMillis() < cachedIceExpiresAtMs - ICE_REUSE_MARGIN_MS) return cached
        return withContext(Dispatchers.IO) {
            val answer = runCatching {
                AndroidGroupCallHttp(app).request("GET", "/v1/call/ice?device=$GROUP_DEVICE", null)
            }.getOrNull() ?: return@withContext cached
            when (val result = runCatching { TurnResponse.parse(answer.status, answer.body) }.getOrNull()) {
                is TurnResult.Ok -> {
                    cachedIce = result.servers
                    cachedIceExpiresAtMs = result.expiresAtMs ?: (System.currentTimeMillis() + ICE_FALLBACK_TTL_MS)
                    result.servers
                }
                else -> {
                    Log.w(TAG, "call/ice issued no usable relay (status=${answer.status})")
                    cached // stale is better than nothing; the plan still refuses without TURN when required
                }
            }
        }
    }

    // ---- outbound stats (the watchdog's only input) -------------------------------------------------

    /**
     * Real `outbound-rtp` counters for the published microphone sender, read from the peer connection's stats.
     * Null when nothing is published or the stats callback doesn't answer within [STATS_TIMEOUT_MS]; the
     * watchdog then does nothing rather than acting on a guess.
     */
    override suspend fun outboundSample(): OutboundSample? {
        val pc = connection ?: return null
        val sender = synchronized(muteLock) { micSender } ?: return null
        return withTimeoutOrNull(STATS_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = RTCStatsCollectorCallback { report ->
                    var packets: Long? = null
                    var bytes: Long? = null
                    for (stats in report.statsMap.values) {
                        if (stats.type != "outbound-rtp") continue
                        packets = (stats.members["packetsSent"] as? Number)?.toLong()
                        bytes = (stats.members["bytesSent"] as? Number)?.toLong()
                    }
                    if (cont.isActive) {
                        cont.resume(if (packets == null && bytes == null) null else OutboundSample(packets, bytes))
                    }
                }
                runCatching { pc.getStats(sender, callback) }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun ensureFactory(): PeerConnectionFactory = synchronized(muteLock) {
        factory ?: run {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app)
                    .createInitializationOptions(),
            )
            val adm = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            audioModule = adm
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
                .also { factory = it }
        }
    }

    private fun closeConnection() {
        val pc: PeerConnection?
        val track: AudioTrack?
        val src: AudioSource?
        synchronized(muteLock) {
            pc = connection
            track = micTrack
            src = source
            connection = null
            micTrack = null
            source = null
        }
        disposeCryptorsLocked()
        runCatching { micSender = null }
        remotes.values.forEach { runCatching { it.track.removeSink(it.sink) } }
        remotes.clear()
        levels.clear()
        levelAt.clear()
        runCatching { track?.setEnabled(false) }
        runCatching { pc?.close() }
        runCatching { track?.dispose() }
        runCatching { src?.dispose() }
        runCatching { pc?.dispose() }
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "false"))
    }

    private suspend fun createOffer(pc: PeerConnection): String = suspendCancellableCoroutine { cont ->
        pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (cont.isActive) cont.resume(description.description)
            }
            override fun onCreateFailure(error: String?) = fail(cont, error)
        }, audioConstraints())
    }

    private suspend fun createAnswer(pc: PeerConnection): String = suspendCancellableCoroutine { cont ->
        pc.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                if (cont.isActive) cont.resume(description.description)
            }
            override fun onCreateFailure(error: String?) = fail(cont, error)
        }, audioConstraints())
    }

    private suspend fun setLocal(pc: PeerConnection, description: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        gatheringLatch = CountDownLatch(1)
        pc.setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) = fail(cont, error)
        }, description)
    }

    private suspend fun setRemote(pc: PeerConnection, description: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        pc.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) = fail(cont, error)
        }, description)
    }

    private fun fail(cont: kotlinx.coroutines.CancellableContinuation<*>, error: String?) {
        if (cont.isActive) cont.resumeWithException(SfuFailure(0, "sdp_failed", false, error))
    }

    private suspend fun awaitGathering(pc: PeerConnection, gen: Int) {
        if (gen != generation.get()) throw SfuFailure(0, "disposed", false)
        if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return
        withContext(Dispatchers.IO) { gatheringLatch.await(4, TimeUnit.SECONDS) }
        if (gen != generation.get()) throw SfuFailure(0, "disposed", false)
    }

    @Volatile private var gatheringLatch = CountDownLatch(0)

    private inner class Observer(private val gen: Int) : PeerConnection.Observer {
        init { gatheringLatch = CountDownLatch(1) }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringLatch.countDown()
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<MediaStream>?) {
            if (gen != generation.get()) return
            val rtp = receiver ?: return
            val track = rtp.track() as? AudioTrack ?: return
            // Cloudflare's SDP names the stream after the publisher session when it can; otherwise the
            // track id is the best key the speaking indicator can use.
            val streamId = streams?.firstOrNull()?.id
            val key = streamId?.takeIf { it.matches(SESSION) }
                ?: (track.id() as? String)?.takeIf { it.matches(SESSION) } ?: "track:${track.id()}"
            // WP17: a received track is only played when its publisher is a known, keyed participant of
            // this epoch. Anything else is muted here, never played in the clear.
            val session = e2ee
            val participant = session?.participantForSession(key)
            if (session == null || participant == null) {
                runCatching { track.setEnabled(false) }
                session?.blockedUnknownSource()
                Log.w(TAG, "received track without a keyed participant; muting it")
                return
            }
            synchronized(muteLock) {
                if (gen != generation.get()) return
                if (remotes.containsKey(key)) {
                    remotes.remove(key)?.let { runCatching { it.track.removeSink(it.sink) } }
                }
                val provider = session.provider() ?: run {
                    runCatching { track.setEnabled(false) }
                    session.blockedUnknownSource()
                    return
                }
                if (!attachReceiverCryptorLocked(rtp, participant, provider)) {
                    runCatching { track.setEnabled(false) }
                    return
                }
            }
            val sink = object : AudioTrackSink {
                override fun onData(audioData: ByteBuffer?, bitsPerSample: Int, sampleRate: Int,
                    numberOfChannels: Int, numberOfFrames: Int, absoluteCaptureTimeNanos: Long) {
                    val data = audioData ?: return
                    if (numberOfFrames <= 0) return
                    val samples = data.duplicate().asShortBuffer()
                    var sum = 0.0
                    var count = 0
                    while (samples.hasRemaining() && count < 4_800) {
                        val s = samples.get() / 32768.0
                        sum += s * s
                        count++
                    }
                    if (count > 0) {
                        levels[key] = kotlin.math.sqrt(sum / count)
                        levelAt[key] = System.nanoTime()
                    }
                }
            }
            track.addSink(sink)
            remotes[key] = Remote(track, sink, key, rtp, participant)
            applyPlayback()
        }

        override fun onRemoveTrack(receiver: RtpReceiver?) {
            val track = receiver?.track() as? AudioTrack ?: return
            remotes.entries.firstOrNull { it.value.track === track }?.let { (key, remote) ->
                runCatching { track.removeSink(remote.sink) }
                synchronized(muteLock) {
                    val id = remote.participant.frameCryptorParticipantId()
                    if (remotes.values.none { it !== remote && it.participant.frameCryptorParticipantId() == id }) {
                        runCatching { receiverCryptors.remove(id)?.dispose() }
                    }
                }
                remotes.remove(key); levels.remove(key)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            // The proxy exchanges SDP only and gathers before sending, so a late candidate is normally
            // absent. Keep the WP13 behaviour if one does arrive.
            candidate?.let { addCandidate(it, gen) }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private abstract class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private fun firstAudioMid(sdp: String): String? {
        var inAudio = false
        for (line in sdp.lineSequence()) {
            when {
                line.startsWith("m=audio") -> inAudio = true
                line.startsWith("m=") -> inAudio = false
                inAudio && line.startsWith("a=mid:") -> return line.removePrefix("a=mid:").trim().takeIf { it.matches(MID) }
            }
        }
        return null
    }

    private companion object {
        const val TAG = "RelayGroupSfuPeer"
        const val LEVEL_TTL_NS = 1_000_000_000L
        /** Reuse an issued TURN credential until this long before it expires (minting is per device, not per rebuild). */
        const val ICE_REUSE_MARGIN_MS = 30_000L
        /** When the endpoint gives no `expiresAt`, keep the answer for ten minutes at most. */
        const val ICE_FALLBACK_TTL_MS = 10 * 60_000L
        const val STATS_TIMEOUT_MS = 2_000L
        val SESSION = Regex("[A-Za-z0-9_-]{1,256}")
        val MID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
