package com.relay.app.desktop

import com.relay.app.call.group.GroupTrackRef
import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCBundlePolicy
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCEncodedAudioFrame
import dev.onvoid.webrtc.RTCEncodedFrame
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioDeviceModuleBase
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.relay.app.call.group.e2ee.ParticipantDevice

/** ICE candidates that arrive before the remote description is applied wait here (bounded), then flush in order. */
class RemoteIceQueue(private val max: Int = 256) {
    private val queue = ArrayDeque<RTCIceCandidate>()
    private var ready = false
    /** Returns candidates that may be applied NOW (empty while queued). Throws when the pre-description backlog overflows. */
    @Synchronized fun offer(c: RTCIceCandidate): List<RTCIceCandidate> {
        if (ready) return listOf(c)
        check(queue.size < max) { "ICE backlog overflow" }
        queue.addLast(c); return emptyList()
    }
    @Synchronized fun markRemoteDescriptionSet(): List<RTCIceCandidate> { ready = true; return queue.toList().also { queue.clear() } }
    @Synchronized fun reset() { ready = false; queue.clear() }
}

/** Receiver audio levels (0..1) per pid from per-receiver inbound-rtp stats. */
object SfuLevels {
    fun level(stats: Collection<Map<String, Any?>>): Float {
        val v = stats.firstNotNullOfOrNull { (it["audioLevel"] as? Number)?.toFloat() } ?: return 0f
        return v.coerceIn(0f, 1f)
    }
}

/**
 * Native webrtc-java peer for ONE group call against the Cloudflare Realtime SFU, reached ONLY through the Worker proxy
 * ([SfuApi]). Sequence: `sessions-new` -> (publish) `tracks-new` local `mic` with our offer -> remote `tracks-new` per
 * publisher (provider offer, we answer via `renegotiate`) -> `tracks-close` on unsubscribe / leave.
 *
 * - **Serialised**: every negotiation step runs on ONE thread, one at a time, subscriptions are diffed in batches.
 * - **Generation fenced**: every step and every native callback checks the generation; [close] bumps it first, so nothing
 *   attaches or emits afterwards.
 * - **Mic only when publishing**: no audio source/track exists until [publish]; mute disables the track but keeps frames flowing
 *   (the SFU's 30 s no-packet expiry is a gate that was NOT measured here).
 * - **WP17 frame E2EE**: with [requireE2ee] the encoded audio path is sealed/opened by webrtc-java encoded transforms. Every
 *   failure (no provider, unknown sender/epoch, bad tag, replay) DROPS the frame; nothing is ever forwarded in the clear.
 *   Without a provider in that mode the microphone is never published and received audio is never decoded into a sink.
 * - **Disposal**: [close] closes the provider tracks (bounded), then disposes every native object and the audio module.
 */
class DesktopSfuPeer(
    private val audio: () -> AudioDeviceModuleBase = ::AudioDeviceModule,
    private val makeSource: (PeerConnectionFactory) -> AudioTrackSource = { it.createAudioSource(AudioOptions().apply {
        echoCancellation = true; noiseSuppression = true; autoGainControl = true; highpassFilter = true
    }) },
    private val onPcm: (pid: String?, ByteArray) -> Unit = { _, _ -> },
    private val stunUrls: List<String> = listOf("stun:stun.cloudflare.com:3478"),
    private val makeFactory: (AudioDeviceModuleBase) -> PeerConnectionFactory = { PeerConnectionFactory(it) },
    private val providerCloseBudgetMs: Long = 2_000,
    /** Production callers pass true: no key means no microphone and no playback, never plaintext. */
    private val requireE2ee: Boolean = false,
) : GroupMediaPort {

    private class Sub(val ref: GroupTrackRef, val mid: String) {
        var transceiver: RTCRtpTransceiver? = null
        var receiver: RTCRtpReceiver? = null
        var track: AudioTrack? = null
        @Volatile var level = 0f
    }

    private class Native(
        val module: AudioDeviceModuleBase, val factory: PeerConnectionFactory, val connection: RTCPeerConnection,
        /** Completes when ICE gathering finishes, so the provider receives SDP with candidates (the proxy does not trickle). */
        val gathered: CompletableFuture<Unit>,
    ) {
        var source: AudioTrackSource? = null
        var track: AudioTrack? = null
        var sender: RTCRtpSender? = null
        val retired = ArrayList<AutoCloseable>()
    }

    private val negotiator = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-sfu-negotiator").apply { isDaemon = true } }
    private val closing = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile private var native: Native? = null
    @Volatile private var sfu: SfuApi? = null
    @Volatile private var emit: (GroupMediaEvent) -> Unit = {}
    @Volatile private var sessionId: String? = null
    @Volatile private var micMid: String? = null
    @Volatile private var micMuted = false
    @Volatile private var deafened = false
    private val lock = Any()
    private val wanted = LinkedHashSet<GroupTrackRef>()
    private val subs = LinkedHashMap<GroupTrackRef, Sub>()   // guarded by [lock]
    private val volumes = ConcurrentHashMap<String, Float>()
    private val ice = RemoteIceQueue()
    private var reconcileQueued = false
    private val failed = AtomicBoolean(false)

    /** Current epoch provider (one per epoch, replaced on rotation) and the "a frame really authenticated" callback. */
    @Volatile private var e2ee: GroupFrameE2eeProvider? = null
    @Volatile private var onE2eeFrame: ((ParticipantDevice, Int) -> Unit)? = null
    /** True only once the sender transform is installed on a real microphone track. */
    @Volatile private var sealing = false

    // ---- WP17 frame E2EE ------------------------------------------------------------------------

    /**
     * Swaps in this epoch's provider. The transforms below read [e2ee] on every frame, so a rotation is visible atomically
     * and a closed (stale-epoch) provider drops instead of decrypting with a retired key.
     */
    override fun setFrameE2ee(provider: GroupFrameE2eeProvider?, onAuthenticated: ((ParticipantDevice, Int) -> Unit)?) {
        e2ee = provider
        onE2eeFrame = onAuthenticated
    }

    /** Only true once the microphone track exists AND a provider is installed: the label must never overstate this. */
    override fun frameE2eeActive(): Boolean = e2ee != null && sealing

    /** Encoded payload copy; [RTCEncodedFrame.getData] is a view that must not be advanced. */
    private fun RTCEncodedFrame.payload(): ByteArray {
        val buffer = data
        val out = ByteArray(buffer.remaining())
        buffer.duplicate().get(out)
        return out
    }

    /** Seals every outbound Opus payload; a failed seal drops the frame rather than sending plaintext. */
    private fun installSenderTransform(sender: RTCRtpSender) {
        sender.setTransform { frame ->
            val p = e2ee
            if (p == null) { frame.drop(); return@setTransform }
            val sealed = runCatching { p.seal(frame.payload()) }.getOrNull()
            if (sealed == null) frame.drop() else frame.setData(sealed)
        }
        sealing = true
        // The sealing path is installed: this host may now claim its own device is covered (WP17: only when sealing).
        val p = e2ee
        if (p != null) runCatching { onE2eeFrame?.invoke(p.me, p.epoch) }
    }

    /**
     * Opens every inbound Opus payload for [ref]'s session. The sender device comes from the room roster mapping held by the
     * provider; an unattributable stream, a retired/unknown epoch, a bad tag or a replayed counter drops the frame.
     */
    private fun installReceiverTransform(receiver: RTCRtpReceiver, ref: GroupTrackRef) {
        receiver.setTransform { frame ->
            val p = e2ee
            if (p == null) { frame.drop(); return@setTransform }
            val sender = p.senderFor(ref.sessionId)
            if (sender == null) { frame.drop(); return@setTransform }
            val sequence = (frame as? RTCEncodedAudioFrame)?.sequenceNumber ?: -1
            val plain = runCatching { p.open(frame.payload(), sender, frame.ssrc, sequence) }.getOrNull()
            if (plain == null) { frame.drop(); return@setTransform }
            frame.setData(plain)
            runCatching { onE2eeFrame?.invoke(sender, p.epoch) }
        }
    }

    /** Test seam: the provider session id of the local `mic` (what other peers' subscribe refs name). */
    fun localSessionId(): String? = sessionId
    fun hasMicrophoneTrack(): Boolean = native?.track != null
    fun subscribedMids(): Map<GroupTrackRef, String> = synchronized(lock) { subs.mapValues { it.value.mid } }
    @Volatile var disposed = false; private set

    // ---- GroupMediaPort ------------------------------------------------------------------------

    override fun open(sfu: SfuApi, onEvent: (GroupMediaEvent) -> Unit) {
        this.sfu = sfu; this.emit = onEvent
        val gen = generation
        submit(gen) {
            var module: AudioDeviceModuleBase? = null; var factory: PeerConnectionFactory? = null
            try {
                module = audio(); factory = makeFactory(module)
                val config = RTCConfiguration().apply {
                    bundlePolicy = RTCBundlePolicy.MAX_BUNDLE
                    if (stunUrls.isNotEmpty()) iceServers.add(RTCIceServer().apply { urls.addAll(stunUrls) })
                }
                val gathered = CompletableFuture<Unit>()
                val connection = factory.createPeerConnection(config, observer(gen, gathered)) ?: error("no peer connection")
                native = Native(module, factory, connection, gathered)
            } catch (t: Throwable) {
                runCatching { factory?.dispose() }; runCatching { module?.dispose() }
                if (t is VirtualMachineError) throw t
                throw t
            }
            if (!alive(gen)) return@submit
            sessionId = api().call("sessions-new", JSONObject()).getString("sessionId")
        }
    }

    override fun publish() {
        val gen = generation
        submit(gen) {
            val n = native ?: return@submit
            val session = sessionId ?: error("no SFU session")
            if (n.track != null) return@submit
            val provider = e2ee
            // Fail closed: with no key this host has nothing to seal with, so it must not put a microphone on the wire.
            if (requireE2ee && provider == null) {
                fail("Group-call encryption is not ready, so the microphone stays off.")
                return@submit
            }
            val source = makeSource(n.factory); n.source = source
            val track = n.factory.createAudioTrack("relay-mic", source); n.track = track
            track.setEnabled(!micMuted)
            val sender = n.connection.addTrack(track, listOf("relay")); n.sender = sender
            // Only an attached provider installs a transform; a requireE2ee peer without one never reached this line.
            if (requireE2ee || e2ee != null) installSenderTransform(sender)
            val offer = createOffer(n.connection)
            setLocal(n.connection, offer)
            val mid = n.connection.transceivers.firstOrNull { it.sender.track?.id == track.id }?.mid ?: error("no mid for the microphone")
            if (!alive(gen)) return@submit
            val local = gatheredSdp(n, gen)
            val resp = api().call("tracks-new", JSONObject().put("sessionId", session)
                .put("sessionDescription", JSONObject().put("type", "offer").put("sdp", local))
                .put("tracks", JSONArray().put(JSONObject().put("location", "local").put("mid", mid).put("trackName", "mic"))))
            if (!alive(gen)) return@submit
            val answer = resp.optJSONObject("sessionDescription")?.optString("sdp").orEmpty().ifEmpty { error("SFU returned no answer") }
            setRemote(n.connection, RTCSessionDescription(RTCSdpType.ANSWER, answer))
            micMid = mid
            flushIce(n, gen)
        }
    }

    override fun setMicMuted(muted: Boolean) { micMuted = muted; native?.track?.let { runCatching { it.setEnabled(!muted) } } }

    override fun subscribe(ref: GroupTrackRef) { synchronized(lock) { wanted += ref }; scheduleReconcile() }
    override fun unsubscribe(ref: GroupTrackRef) { synchronized(lock) { wanted -= ref }; scheduleReconcile() }

    override fun setDeafened(deafened: Boolean) {
        this.deafened = deafened
        synchronized(lock) { subs.values.toList() }.forEach { applyPlayback(it) }
    }
    override fun setVolume(pid: String, volume: Float) {
        volumes[pid] = volume
        synchronized(lock) { subs.values.filter { it.ref.pid == pid } }.forEach { applyPlayback(it) }
    }

    private fun applyPlayback(sub: Sub) {
        val t = sub.track ?: return
        runCatching { t.setEnabled(!deafened); t.setVolume(if (deafened) 0.0 else (volumes[sub.ref.pid] ?: 1f).toDouble()) }
    }

    override fun levels(): Map<String, Float> {
        val n = native
        val gen = generation
        val current = synchronized(lock) { subs.values.toList() }
        if (n != null && !closing.get()) for (sub in current) {
            val receiver = sub.receiver ?: continue
            runCatching {
                n.connection.getStats(receiver) { report ->
                    if (alive(gen)) sub.level = SfuLevels.level(report.stats.values
                        .filter { it.type == RTCStatsType.INBOUND_RTP }.map { it.attributes })
                }
            }
        }
        return current.associate { it.ref.pid to it.level }
    }

    // ---- reconciliation ------------------------------------------------------------------------

    private fun scheduleReconcile() {
        synchronized(lock) { if (reconcileQueued) return; reconcileQueued = true }
        val gen = generation
        submit(gen) {
            synchronized(lock) { reconcileQueued = false }
            reconcile(gen)
        }
    }

    private fun reconcile(gen: Int) {
        val n = native ?: return
        val session = sessionId ?: return
        // A bounded number of passes: the wanted set may change while the provider calls are in flight.
        repeat(8) {
            if (!alive(gen)) return
            val (remove, add) = synchronized(lock) { subs.keys.filter { it !in wanted } to wanted.filter { it !in subs } }
            if (remove.isEmpty() && add.isEmpty()) return
            if (remove.isNotEmpty()) {
                val gone = synchronized(lock) { remove.mapNotNull { subs.remove(it) } }
                gone.forEach { s -> s.track?.let { runCatching { it.setEnabled(false); it.setVolume(0.0) } } }
                api().call("tracks-close", JSONObject().put("sessionId", session).put("mids", JSONArray(gone.map { it.mid })))
            }
            if (add.isNotEmpty()) {
                val resp = api().call("tracks-new", JSONObject().put("sessionId", session).put("tracks", JSONArray(add.map {
                    JSONObject().put("location", "remote").put("sessionId", it.sessionId).put("trackName", it.track)
                })))
                if (!alive(gen)) return
                val mids = resp.optJSONArray("tracks")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).optString("mid") } }.orEmpty()
                check(mids.size == add.size && mids.none { it.isEmpty() }) { "SFU returned ${mids.size} tracks for ${add.size}" }
                val description = resp.optJSONObject("sessionDescription")
                // Cloudflare always returns the provider offer for remote tracks; apply it even if the advisory flag is
                // missing, otherwise the transceivers exist but no media ever flows.
                if (description != null) {
                    if (description.optString("type") != "offer") error("SFU renegotiation was not an offer")
                    setRemote(n.connection, RTCSessionDescription(RTCSdpType.OFFER, description.getString("sdp")))
                    val answer = createAnswer(n.connection)
                    setLocal(n.connection, answer)
                    if (!alive(gen)) return
                    api().call("renegotiate", JSONObject().put("sessionId", session).put("answer", gatheredSdp(n, gen)))
                    flushIce(n, gen)
                }
                if (!alive(gen)) return
                add.forEachIndexed { i, ref ->
                    val sub = Sub(ref, mids[i])
                    n.connection.transceivers.firstOrNull { it.mid == mids[i] }?.let { t ->
                        sub.transceiver = t; sub.receiver = t.receiver
                        if (requireE2ee || e2ee != null) installReceiverTransform(t.receiver, ref)
                        (t.receiver.track as? AudioTrack)?.let { track ->
                            sub.track = track
                            track.addSink(AudioTrackSink { data, _, _, _, _ -> if (alive(gen)) onPcm(ref.pid, data) })
                            applyPlayback(sub)
                        }
                    }
                    synchronized(lock) { if (ref in wanted) subs[ref] = sub }
                }
            }
        }
    }

    // ---- ICE queue (providers that trickle candidates) -------------------------------------------

    /** Candidates from the provider side; queued until the remote description is set. */
    fun addRemoteCandidate(c: RTCIceCandidate) {
        val gen = generation
        submit(gen) { val n = native ?: return@submit; ice.offer(c).forEach { n.connection.addIceCandidate(it) } }
    }
    private fun flushIce(n: Native, gen: Int) { if (alive(gen)) ice.markRemoteDescriptionSet().forEach { n.connection.addIceCandidate(it) } }

    // ---- native helpers (negotiator thread only) --------------------------------------------------

    private fun observer(gen: Int, gathered: CompletableFuture<Unit>) = object : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) { /* SDP is sent after ICE gathering in the SFU exchange. */ }
        override fun onIceGatheringChange(state: RTCIceGatheringState) {
            if (state == RTCIceGatheringState.COMPLETE) gathered.complete(Unit)
        }
        override fun onIceConnectionChange(state: RTCIceConnectionState) {
            if (state == RTCIceConnectionState.FAILED && alive(gen)) fail("ice failed")
        }
    }

    /**
     * The Worker proxy forwards one SDP per operation and has no trickle channel, so the candidates must already be in
     * the local description. Gathering is bounded: an unreachable STUN server must delay, never deadlock, a call.
     */
    private fun gatheredSdp(n: Native, gen: Int): String {
        runCatching { n.gathered.get(3, TimeUnit.SECONDS) }
        if (!alive(gen)) error("closed")
        return n.connection.localDescription?.sdp ?: error("no local description")
    }

    private fun createOffer(pc: RTCPeerConnection): RTCSessionDescription {
        val f = CompletableFuture<RTCSessionDescription>()
        pc.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) { f.complete(description) }
            override fun onFailure(error: String) { f.completeExceptionally(IllegalStateException("offer: $error")) }
        })
        return f.get(15, TimeUnit.SECONDS)
    }
    private fun createAnswer(pc: RTCPeerConnection): RTCSessionDescription {
        val f = CompletableFuture<RTCSessionDescription>()
        pc.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) { f.complete(description) }
            override fun onFailure(error: String) { f.completeExceptionally(IllegalStateException("answer: $error")) }
        })
        return f.get(15, TimeUnit.SECONDS)
    }
    private fun setLocal(pc: RTCPeerConnection, d: RTCSessionDescription) {
        val f = CompletableFuture<Unit>()
        pc.setLocalDescription(d, object : SetSessionDescriptionObserver {
            override fun onSuccess() { f.complete(Unit) }
            override fun onFailure(error: String) { f.completeExceptionally(IllegalStateException("setLocal: $error")) }
        })
        f.get(15, TimeUnit.SECONDS)
    }
    private fun setRemote(pc: RTCPeerConnection, d: RTCSessionDescription) {
        val f = CompletableFuture<Unit>()
        pc.setRemoteDescription(d, object : SetSessionDescriptionObserver {
            override fun onSuccess() { f.complete(Unit) }
            override fun onFailure(error: String) { f.completeExceptionally(IllegalStateException("setRemote: $error")) }
        })
        f.get(15, TimeUnit.SECONDS)
    }

    private fun api(): SfuApi = sfu ?: error("not open")
    private fun alive(gen: Int) = generation == gen && !closing.get()

    private fun submit(gen: Int, block: () -> Unit) {
        try {
            negotiator.execute {
                if (!alive(gen)) return@execute
                try { block() } catch (t: Throwable) {
                    if (t is VirtualMachineError) throw t
                    if (alive(gen)) fail(t.message ?: t.javaClass.simpleName)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* closed concurrently */ }
    }

    private fun fail(reason: String) {
        if (failed.compareAndSet(false, true)) runCatching { emit(GroupMediaEvent.Failed(reason.take(120))) }
    }

    // ---- disposal --------------------------------------------------------------------------------

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        generation++
        // Tell the provider to drop our tracks (bounded; the server also force-closes on leave).
        val session = sessionId; val client = sfu
        if (session != null && client != null) {
            val mids = synchronized(lock) { subs.values.map { it.mid } } + listOfNotNull(micMid)
            if (mids.isNotEmpty()) runCatching {
                CompletableFuture.runAsync { client.call("tracks-close", JSONObject().put("sessionId", session).put("mids", JSONArray(mids))) }
                    .get(providerCloseBudgetMs, TimeUnit.MILLISECONDS)
            }
        }
        negotiator.shutdownNow()
        e2ee = null
        onE2eeFrame = null
        sealing = false
        runCatching { negotiator.awaitTermination(5, TimeUnit.SECONDS) }
        val n = native; native = null
        val current = synchronized(lock) { wanted.clear(); subs.values.toList().also { subs.clear() } }
        ice.reset()
        if (n != null) {
            runCatching { n.gathered.complete(Unit) } // never leave a bounded gather wait blocking disposal
            runCatching { n.track?.setEnabled(false) }
            runCatching { n.connection.close() }
            current.forEach { s ->
                runCatching { s.track?.setEnabled(false) }
                runCatching { s.track?.dispose() }; runCatching { s.receiver?.dispose() }; runCatching { s.transceiver?.dispose() }
            }
            runCatching { n.sender?.dispose() }; runCatching { n.track?.dispose() }; runCatching { n.source?.dispose() }
            runCatching { n.factory.dispose() }; runCatching { n.module.dispose() }
        }
        disposed = true
    }
}
