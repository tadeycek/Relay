package com.relay.app.calls.group

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

data class SfuTrack(val mid: String?, val trackName: String?)
data class SfuReply(
    val status: Int, val sessionId: String? = null, val tracks: List<SfuTrack> = emptyList(),
    val sdp: String? = null, val sdpType: String? = null, val requiresImmediateRenegotiation: Boolean = false,
    val errorCode: String? = null, val retryable: Boolean = false,
)
class SfuFailure(val status: Int, val code: String?, val retryable: Boolean, message: String? = null) : Exception(message ?: "sfu $status ${code ?: ""}")

interface SfuApi {
    suspend fun sessionsNew(): SfuReply
    suspend fun tracksNewLocal(sessionId: String, offerSdp: String, mid: String): SfuReply
    suspend fun tracksNewRemote(sessionId: String, publisherSessions: List<String>): SfuReply
    suspend fun renegotiate(sessionId: String, answerSdp: String): SfuReply
    suspend fun tracksClose(sessionId: String, mids: List<String>): SfuReply
}
data class MicOffer(val sdp: String, val mid: String)
interface SfuPeer {
    suspend fun prepareMicOffer(): MicOffer
    suspend fun applyAnswer(sdp: String)
    suspend fun answerRemoteOffer(offerSdp: String): String
    fun setMicEnabled(enabled: Boolean)
    /** Local playback only: silences every received audio track without telling anyone. */
    fun setDeafened(deafened: Boolean) {}
    /** Per-person playback volume for the publisher sessions that belong to that person. */
    fun setParticipantVolume(publisherSessions: List<String>, volume: Float) {}
    /** Throws away the peer connection and microphone; a later [prepareMicOffer] builds a new one. */
    fun reset()
    fun release()
}

/**
 * One participant's Cloudflare-SFU session, driven only through the Worker proxy ([SfuApi]); the provider is never
 * addressed directly. Every mutation runs under one mutex, so publish / subscribe / unsubscribe / renegotiate
 * never overlap (the provider requires serial mutations per session). The microphone is opened by [publish]
 * (through [SfuPeer.prepareMicOffer]) and nowhere else. [dispose] needs no lock: it fences everything in flight,
 * releases the peer (mic included) at once and closes the tracks best effort.
 */
class SfuSession(private val api: SfuApi, private val peer: SfuPeer) {
    private val lock = Mutex()
    private val disposed = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile private var sessionId: String? = null
    @Volatile private var local: String? = null
    private val remotes = LinkedHashMap<String, String>() // publisher session id -> our mid

    val localMid: String? get() = local
    val subscribedSessions: Set<String> get() = synchronized(remotes) { remotes.keys.toSet() }

    private fun ok(reply: SfuReply): SfuReply {
        if (reply.status != 200) throw SfuFailure(reply.status, reply.errorCode, reply.retryable)
        return reply
    }
    private fun alive(gen: Int) = !disposed.get() && gen == generation

    suspend fun publish() = lock.withLock { publishLocked() }

    private suspend fun publishLocked() {
        val gen = generation
        if (!alive(gen)) return
        val sid = ok(api.sessionsNew()).sessionId ?: throw SfuFailure(200, "bad_response", false)
        if (!alive(gen)) return
        val offer = peer.prepareMicOffer()
        if (!alive(gen)) { peer.release(); return } // the mic was opened by a publish that lost the race to dispose
        val reply = ok(api.tracksNewLocal(sid, offer.sdp, offer.mid))
        if (!alive(gen)) { peer.release(); runCatching { api.tracksClose(sid, listOf(offer.mid)) }; return }
        peer.applyAnswer(reply.sdp ?: throw SfuFailure(200, "bad_response", false))
        sessionId = sid
        local = reply.tracks.firstOrNull()?.mid ?: offer.mid
    }

    suspend fun subscribe(publisherSessions: List<String>) = lock.withLock { subscribeLocked(publisherSessions) }

    private suspend fun subscribeLocked(publisherSessions: List<String>) {
        val gen = generation
        if (!alive(gen)) return
        val sid = sessionId ?: throw SfuFailure(0, "not_published", false)
        val fresh = publisherSessions.distinct().filter { synchronized(remotes) { it !in remotes } }
        for (chunk in fresh.chunked(64)) {
            val reply = ok(api.tracksNewRemote(sid, chunk))
            if (!alive(gen)) return
            if (reply.tracks.size != chunk.size || reply.tracks.any { it.mid.isNullOrEmpty() }) throw SfuFailure(200, "bad_response", false)
            if (reply.requiresImmediateRenegotiation) {
                val offer = reply.sdp ?: throw SfuFailure(200, "bad_response", false)
                val answer = peer.answerRemoteOffer(offer)
                ok(api.renegotiate(sid, answer))
                if (!alive(gen)) return
            }
            synchronized(remotes) { chunk.forEachIndexed { i, key -> remotes[key] = reply.tracks[i].mid!! } }
        }
    }

    suspend fun unsubscribe(publisherSessions: List<String>) = lock.withLock {
        if (disposed.get()) return@withLock
        val sid = sessionId ?: return@withLock
        val known = synchronized(remotes) { publisherSessions.distinct().filter { it in remotes } }
        if (known.isEmpty()) return@withLock
        val mids = synchronized(remotes) { known.map { remotes.getValue(it) } }
        ok(api.tracksClose(sid, mids))
        synchronized(remotes) { known.forEach(remotes::remove) }
    }

    /** New peer connection, new SFU session: for a lost session, a fresh-join pid or a mute that starved the track. */
    suspend fun rebuild() = lock.withLock {
        if (disposed.get()) return@withLock
        val sid = sessionId
        val old = listOfNotNull(local) + synchronized(remotes) { remotes.values.toList() }
        val again = synchronized(remotes) { remotes.keys.toList().also { remotes.clear() } }
        generation++
        local = null; sessionId = null
        if (sid != null && old.isNotEmpty()) runCatching { api.tracksClose(sid, old) }
        peer.reset()
        publishLocked()
        if (again.isNotEmpty()) subscribeLocked(again)
    }

    /** Muting disables the track; it never closes it, so the sender keeps its (silence) packet flow. */
    fun setMuted(muted: Boolean) { if (!disposed.get()) peer.setMicEnabled(!muted) }

    fun setDeafened(deafened: Boolean) { if (!disposed.get()) peer.setDeafened(deafened) }

    fun setParticipantVolume(publisherSessions: List<String>, volume: Float) {
        if (!disposed.get()) peer.setParticipantVolume(publisherSessions, volume)
    }

    suspend fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        generation++
        val sid = sessionId
        val mids = listOfNotNull(local) + synchronized(remotes) { remotes.values.toList().also { remotes.clear() } }
        local = null; sessionId = null
        runCatching { peer.release() }
        if (sid != null && mids.isNotEmpty()) withTimeoutOrNull(3_000) { runCatching { api.tracksClose(sid, mids) } }
    }
}
