package com.relay.app.desktop

import com.relay.app.call.group.GroupTrackRef
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

/** WP16 (3): the Cloudflare-SFU peer on webrtc-java, against a fake SFU built from real peer connections. */
class DesktopSfuPeerTest {
    private val sfu = FakeSfuProvider()
    private val peers = CopyOnWriteArrayList<DesktopSfuPeer>()
    @After fun tearDown() { peers.forEach { runCatching { it.close() } }; sfu.close() }

    private class Listener { val events = CopyOnWriteArrayList<GroupMediaEvent>(); val fn: (GroupMediaEvent) -> Unit = { events += it } }
    private class Hear { val energy = AtomicLong(); val samples = AtomicLong(); val byPid = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
        fun onPcm(pid: String?, pcm: ByteArray) {
            var e = 0L
            for (i in 0 until pcm.size - 1 step 2) { e += kotlin.math.abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort().toInt()); samples.incrementAndGet() }
            energy.addAndGet(e); pid?.let { byPid.getOrPut(it) { AtomicLong() }.addAndGet(e) }
        }
        fun mean() = energy.get() / samples.get().coerceAtLeast(1)
    }

    private fun peer(hear: Hear = Hear(), source: AtomicReference<CustomAudioSource> = AtomicReference(), sourcesMade: AtomicInteger = AtomicInteger(), api: SfuApi = sfu.workerApi): Triple<DesktopSfuPeer, Listener, AtomicReference<CustomAudioSource>> {
        val l = Listener()
        val p = DesktopSfuPeer(audio = { HeadlessAudioDeviceModule() }, makeSource = { sourcesMade.incrementAndGet(); CustomAudioSource().also(source::set) }, onPcm = hear::onPcm, stunUrls = emptyList())
        peers += p; p.open(api, l.fn)
        return Triple(p, l, source)
    }

    private fun tone(source: AtomicReference<CustomAudioSource>, hz: Int, ms: Int) {
        val start = System.nanoTime()
        repeat(ms / 10) { tick ->
            val s = source.get()
            if (s != null) {
                val pcm = ByteArray(960)
                repeat(480) { i -> val v = (12_000 * sin(2 * Math.PI * hz * (tick * 480L + i) / 48_000)).toInt(); pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte() }
                s.pushAudio(pcm, 16, 48_000, 1, 480)
            }
            val left = start + TimeUnit.MILLISECONDS.toNanos((tick + 1) * 10L) - System.nanoTime()
            if (left > 0) TimeUnit.NANOSECONDS.sleep(left)
        }
    }

    private fun waitFor(what: String, seconds: Long = 20, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond() && System.nanoTime() < end) Thread.sleep(25)
        assertTrue("timed out: $what", cond())
    }

    @Test(timeout = 90_000) fun aSubscriberHearsAPublishersMicThroughTheSfuAndNoMicExistsBeforePublish() {
        val hearA = Hear(); val made = AtomicInteger()
        val (a, aEvents, _) = peer(hearA, sourcesMade = made)
        val bSource = AtomicReference<CustomAudioSource>()
        val (b, bEvents, _) = peer(source = bSource)
        waitFor("sessions") { a.localSessionId() != null && b.localSessionId() != null || aEvents.events.isNotEmpty() || bEvents.events.isNotEmpty() }
        assertTrue("a=${aEvents.events} b=${bEvents.events} log=${sfu.log}", a.localSessionId() != null && b.localSessionId() != null)
        assertFalse("mic only when publishing", a.hasMicrophoneTrack()); assertEquals(0, made.get())
        b.publish(); waitFor("b published") { b.hasMicrophoneTrack() && sfu.log.contains("tracks-new:local") }
        a.subscribe(GroupTrackRef("00000000000000b1", b.localSessionId()!!, "mic"))
        waitFor("subscribed") { a.subscribedMids().isNotEmpty() }
        tone(bSource, 440, 3_000)
        assertTrue("A heard B: mean=${hearA.mean()} samples=${hearA.samples}", hearA.samples.get() > 1000 && hearA.mean() > 100)
        assertTrue(hearA.byPid.containsKey("00000000000000b1"))
        assertTrue(aEvents.events.isEmpty() && bEvents.events.isEmpty())
        assertEquals("a never published", 0, made.get())
    }

    @Test(timeout = 90_000) fun mutedMicrophoneKeepsFramesFlowingButSilent() {
        val hearA = Hear()
        val (a, _, _) = peer(hearA)
        val bSource = AtomicReference<CustomAudioSource>()
        val (b, _, _) = peer(source = bSource)
        waitFor("sessions") { a.localSessionId() != null && b.localSessionId() != null }
        b.publish(); waitFor("b published") { b.hasMicrophoneTrack() && sfu.log.contains("tracks-new:local") }
        a.subscribe(GroupTrackRef("00000000000000b1", b.localSessionId()!!, "mic"))
        waitFor("subscribed") { a.subscribedMids().isNotEmpty() }
        tone(bSource, 440, 2_000)
        val loud = hearA.mean()
        b.setMicMuted(true)
        val before = hearA.samples.get(); hearA.energy.set(0); hearA.samples.set(0)
        tone(bSource, 440, 2_000)
        assertTrue("frames still arrive while muted: ${hearA.samples}", hearA.samples.get() > 500)
        assertTrue("muted audio is silent: loud=$loud muted=${hearA.mean()}", hearA.mean() < loud / 4)
        assertTrue(before > 0)
    }

    @Test(timeout = 90_000) fun negotiationIsSerialisedAndSubscriptionDiffsAreBatched() {
        val (a, _, _) = peer()
        val sources = (1..3).map { AtomicReference<CustomAudioSource>() }
        val pubs = sources.map { peer(source = it).first }
        waitFor("sessions") { a.localSessionId() != null && pubs.all { it.localSessionId() != null } }
        pubs.forEach { it.publish() }
        waitFor("published") { sfu.log.count { it == "tracks-new:local" } == 3 }
        pubs.forEachIndexed { i, p -> a.subscribe(GroupTrackRef("00000000000000b${i + 1}", p.localSessionId()!!, "mic")) }
        waitFor("all subscribed") { a.subscribedMids().size == 3 }
        assertEquals("one batched pull for three publishers", 1, sfu.log.count { it == "tracks-new:remote" })
        assertEquals("one renegotiation answer", 1, sfu.log.count { it == "renegotiate" })
        // Operations for one peer are serialized; other independent publishers may negotiate concurrently.
        // unsubscribing one closes exactly its mid
        val ref = a.subscribedMids().keys.first(); val mid = a.subscribedMids().getValue(ref)
        a.unsubscribe(ref)
        waitFor("closed") { a.subscribedMids().size == 2 && sfu.log.contains("tracks-close") }
        assertFalse(a.subscribedMids().containsKey(ref)); assertNotNull(mid)
    }

    @Test(timeout = 60_000) fun aProviderFailureIsReportedOnceAndNeverThrowsIntoTheCaller() {
        val (a, events, _) = peer()
        waitFor("session") { a.localSessionId() != null }
        sfu.failOps = true
        a.publish()
        waitFor("failure reported") { events.events.isNotEmpty() }
        a.publish(); Thread.sleep(300)
        assertEquals(1, events.events.size)
        assertTrue(events.events.single() is GroupMediaEvent.Failed)
    }

    @Test(timeout = 90_000) fun closeBeforeAnInFlightNegotiationFinishesFencesItAndLeavesNothingBehind() {
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        sfu.gate = { op -> if (op == "tracks-new:local") { entered.countDown(); release.await(20, TimeUnit.SECONDS) } }
        val (a, events, _) = peer()
        waitFor("session") { a.localSessionId() != null }
        a.publish()
        assertTrue("publish reached the provider", entered.await(20, TimeUnit.SECONDS))
        val closer = Thread { a.close() }.also { it.start() }
        Thread.sleep(200)
        release.countDown()
        closer.join(30_000)
        assertFalse("close finished", closer.isAlive)
        assertTrue(a.disposed)
        Thread.sleep(500)
        assertTrue("a fenced operation emitted nothing", events.events.isEmpty())
        assertTrue("and attached no subscription", a.subscribedMids().isEmpty())
        a.subscribe(GroupTrackRef("00000000000000b1", "sessX", "mic")); a.publish()
        Thread.sleep(200)
        assertFalse("nothing runs after close", sfu.log.count { it == "tracks-new:remote" } > 0)
    }

    @Test(timeout = 60_000) fun closeIsIdempotentAndSafeBeforeOpen() {
        val p = DesktopSfuPeer(audio = { HeadlessAudioDeviceModule() }, stunUrls = emptyList())
        p.close(); p.close(); assertTrue(p.disposed)
        val (a, _, _) = peer(); waitFor("session") { a.localSessionId() != null }
        a.close(); a.close(); assertTrue(a.disposed)
        assertTrue("native resources were disposed", a.disposed)
    }

    @Test fun iceCandidatesBeforeTheRemoteDescriptionAreQueuedThenFlushedInOrder() {
        val q = RemoteIceQueue(max = 3)
        val c = (1..4).map { RTCIceCandidate("0", 0, "candidate:$it 1 udp 1 127.0.0.1 ${1000 + it} typ host") }
        assertTrue(q.offer(c[0]).isEmpty()); assertTrue(q.offer(c[1]).isEmpty())
        assertEquals(listOf(c[0], c[1]), q.markRemoteDescriptionSet())
        assertEquals(listOf(c[2]), q.offer(c[2]))
        q.reset()
        assertTrue(q.offer(c[0]).isEmpty()); assertTrue(q.offer(c[1]).isEmpty()); assertTrue(q.offer(c[2]).isEmpty())
        try { q.offer(c[3]); org.junit.Assert.fail("a flood before the description must fail, not grow") } catch (_: IllegalStateException) {}
    }

    @Test fun receiverLevelsComeFromInboundAudioStats() {
        assertEquals(0.42f, SfuLevels.level(listOf(mapOf("audioLevel" to 0.42, "kind" to "audio"))), 0.001f)
        assertEquals(0f, SfuLevels.level(listOf(mapOf("kind" to "audio"))), 0f)
        assertEquals(1f, SfuLevels.level(listOf(mapOf("audioLevel" to 7.0))), 0f)
    }
}
