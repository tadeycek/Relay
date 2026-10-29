package com.relay.app.calls.group

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** WP16 step 3: serialised Cloudflare-SFU negotiation through the Worker proxy, mic only on publish, full disposal. */
class SfuSessionTest {
    class FakeApi : SfuApi {
        val log = mutableListOf<String>(); var inFlight = 0; var maxInFlight = 0
        var failRemote: SfuReply? = null; var gate: CompletableDeferred<Unit>? = null; var sessionCounter = 0
        private var midCounter = 10
        private suspend fun <T> op(name: String, block: () -> T): T {
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight); log += name
            try { gate?.await(); delay(5); return block() } finally { inFlight-- }
        }
        override suspend fun sessionsNew() = op("sessions-new") { SfuReply(200, sessionId = "s-me-${++sessionCounter}") }
        override suspend fun tracksNewLocal(sessionId: String, offerSdp: String, mid: String) =
            op("tracks-new-local:$sessionId:$mid") { SfuReply(200, tracks = listOf(SfuTrack(mid, "mic")), sdp = "ANSWER", sdpType = "answer") }
        override suspend fun tracksNewRemote(sessionId: String, publisherSessions: List<String>) =
            op("tracks-new-remote:${publisherSessions.joinToString(",")}") {
                failRemote ?: SfuReply(200, tracks = publisherSessions.map { SfuTrack("m${midCounter++}", "mic") }, sdp = "OFFER", sdpType = "offer", requiresImmediateRenegotiation = true)
            }
        override suspend fun renegotiate(sessionId: String, answerSdp: String) = op("renegotiate:$answerSdp") { SfuReply(200) }
        override suspend fun tracksClose(sessionId: String, mids: List<String>) = op("tracks-close:${mids.joinToString(",")}") { SfuReply(200) }
    }
    class FakePeer(val api: FakeApi) : SfuPeer {
        var micOpens = 0; var released = 0; var resets = 0; var micEnabled: Boolean? = null; val applied = mutableListOf<String>()
        override suspend fun prepareMicOffer(): MicOffer { micOpens++; return MicOffer("OFFER-SDP", "0") }
        override suspend fun applyAnswer(sdp: String) { applied += sdp; api.log += "applyAnswer" }
        override suspend fun answerRemoteOffer(offerSdp: String): String { api.log += "answerRemote:$offerSdp"; return "ANS-$offerSdp" }
        override fun setMicEnabled(enabled: Boolean) { micEnabled = enabled }
        override fun reset() { resets++ }
        override fun release() { released++ }
    }
    private val api = FakeApi(); private val peer = FakePeer(api); private val sfu = SfuSession(api, peer)

    @Test fun microphoneIsOpenedOnlyWhenPublishingNotWhenConstructedOrSubscribing() = runBlocking {
        assertEquals(0, peer.micOpens)
        sfu.publish()
        assertEquals(1, peer.micOpens)
        assertEquals(listOf("sessions-new", "tracks-new-local:s-me-1:0", "applyAnswer"), api.log)
        assertEquals("0", sfu.localMid)
    }

    @Test fun subscribingAnswersTheSfuOfferAndRenegotiatesInOrder() = runBlocking {
        sfu.publish(); api.log.clear()
        sfu.subscribe(listOf("s-bob"))
        assertEquals(listOf("tracks-new-remote:s-bob", "answerRemote:OFFER", "renegotiate:ANS-OFFER"), api.log)
        assertEquals(setOf("s-bob"), sfu.subscribedSessions)
    }

    @Test fun subscribingTwiceToTheSameTrackSendsOneRequest() = runBlocking {
        sfu.publish(); api.log.clear()
        sfu.subscribe(listOf("s-bob")); sfu.subscribe(listOf("s-bob"))
        assertEquals(1, api.log.count { it.startsWith("tracks-new-remote") })
    }

    @Test fun unsubscribeClosesTheMidTheSfuAssignedAndIgnoresUnknownTracks() = runBlocking {
        sfu.publish(); sfu.subscribe(listOf("s-bob", "s-carol")); api.log.clear()
        sfu.unsubscribe(listOf("s-carol", "s-nobody"))
        assertEquals(listOf("tracks-close:m11"), api.log)
        assertEquals(setOf("s-bob"), sfu.subscribedSessions)
    }

    @Test fun concurrentCommandsNeverOverlapAtTheSfu() = runBlocking {
        sfu.publish()
        listOf(async { sfu.subscribe(listOf("s-a")) }, async { sfu.subscribe(listOf("s-b")) }, async { sfu.unsubscribe(listOf("s-a")) }, async { sfu.subscribe(listOf("s-c")) }).awaitAll()
        assertEquals("negotiation is serialised", 1, api.maxInFlight)
        assertEquals(setOf("s-b", "s-c"), sfu.subscribedSessions)
    }

    @Test fun aFailedSubscribeIsNotRecordedAndCanBeRetried() = runBlocking {
        sfu.publish()
        api.failRemote = SfuReply(502, errorCode = "upstream_failed", retryable = true)
        val failure = try { sfu.subscribe(listOf("s-bob")); null } catch (e: SfuFailure) { e }
        assertNotNull(failure); assertTrue(failure!!.retryable); assertTrue(sfu.subscribedSessions.isEmpty())
        api.failRemote = null
        sfu.subscribe(listOf("s-bob")); assertEquals(setOf("s-bob"), sfu.subscribedSessions)
    }

    @Test fun muteDisablesTheTrackButNeverClosesIt() = runBlocking {
        sfu.publish(); api.log.clear()
        sfu.setMuted(true)
        assertEquals(false, peer.micEnabled); assertTrue(api.log.isEmpty()); assertEquals("0", sfu.localMid)
        sfu.setMuted(false); assertEquals(true, peer.micEnabled)
    }

    @Test fun disposeReleasesThePeerOnceAndFencesAPublishThatWasInFlight() = runBlocking {
        api.gate = CompletableDeferred()
        val inFlight = async { runCatching { sfu.publish() } }
        delay(30)
        sfu.dispose()
        api.gate!!.complete(Unit)
        inFlight.await()
        sfu.dispose()
        assertEquals("peer released exactly once", 1, peer.released)
        assertTrue("an in-flight publish that lost the race must not leave a mic or a local track behind", peer.micOpens == 0 || peer.released >= 1)
        assertNull(sfu.localMid)
        api.log.clear(); runCatching { sfu.subscribe(listOf("s-late")) }
        assertTrue("no SFU traffic after dispose", api.log.none { it.startsWith("tracks-new") })
    }

    @Test fun disposeClosesTheLocalAndSubscribedTracksAtTheSfu() = runBlocking {
        sfu.publish(); sfu.subscribe(listOf("s-bob")); api.log.clear()
        sfu.dispose()
        assertEquals(listOf("tracks-close:0,m10"), api.log)
    }

    @Test fun rebuildClosesTheOldTrackBuildsANewSessionAndResubscribes() = runBlocking {
        sfu.publish(); sfu.subscribe(listOf("s-bob")); api.log.clear()
        sfu.rebuild()
        assertEquals(1, peer.resets); assertEquals(2, peer.micOpens)
        assertEquals(listOf("tracks-close:0,m10", "sessions-new", "tracks-new-local:s-me-2:0", "applyAnswer", "tracks-new-remote:s-bob", "answerRemote:OFFER", "renegotiate:ANS-OFFER"), api.log)
        assertEquals(setOf("s-bob"), sfu.subscribedSessions)
    }
}
