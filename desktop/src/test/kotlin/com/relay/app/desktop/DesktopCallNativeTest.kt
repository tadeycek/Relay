package com.relay.app.desktop

import com.relay.app.call.*
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import kotlin.math.sin

class DesktopCallNativeTest {
    @Test(timeout = 30000) fun staleRemoteOfferCallbackCannotCreateGenerationOneAnswer() {
        val aEvents = LinkedBlockingQueue<CallEvent>()
        val bEvents = LinkedBlockingQueue<CallEvent>()
        val held = LinkedBlockingQueue<() -> Unit>()
        val a = DesktopCallPeer({ aEvents.offer(it) }, { HeadlessAudioDeviceModule() }, { CustomAudioSource() })
        val b = DesktopCallPeer({ bEvents.offer(it) }, { HeadlessAudioDeviceModule() }, { CustomAudioSource() },
            callbackGate = { name, run -> if (name == "remote-offer-1") held.offer(run) else run() })
        val id = "e".repeat(32)
        try {
            a.open(id, PeerRole.OFFERER, emptyList(), false)
            b.open(id, PeerRole.ANSWERER, emptyList(), false)
            a.createOffer(id, 1, false)
            val offer = await<CallEvent.LocalDescription>(aEvents) { it.kind == SdpKind.OFFER }
            b.applyRemoteOffer(id, 1, offer.sdp)
            val stale = held.poll(5, TimeUnit.SECONDS) ?: error("missing gen1 remote callback")
            b.applyRemoteOffer(id, 2, offer.sdp)
            stale()
            Thread.sleep(1500) // let any (wrongly) issued gen1 native createAnswer/setLocal complete
            val events = mutableListOf<CallEvent>()
            bEvents.drainTo(events)
            assertFalse("gen1 callback emitted an answer", events.any { it is CallEvent.LocalDescription && it.gen == 1 })
            assertFalse("stale callback failed the current session", events.any { it is CallEvent.PeerFailed })
        } finally { a.close(); b.close() }
    }

    @Test(timeout = 30000) fun heldOnTrackCannotAttachAfterClose() {
        val aEvents = LinkedBlockingQueue<CallEvent>()
        val bEvents = LinkedBlockingQueue<CallEvent>()
        val held = LinkedBlockingQueue<() -> Unit>()

        val a = DesktopCallPeer({ aEvents.offer(it) }, { HeadlessAudioDeviceModule() }, { CustomAudioSource() },
            callbackGate = { name, run -> if (name == "track") held.offer(run) else run() })
        val b = DesktopCallPeer({ bEvents.offer(it) }, { HeadlessAudioDeviceModule() }, { CustomAudioSource() })
        val id = "d".repeat(32)
        try {
            a.open(id, PeerRole.OFFERER, emptyList(), false)
            b.open(id, PeerRole.ANSWERER, emptyList(), false)
            a.createOffer(id, 1, false)
            b.applyRemoteOffer(id, 1, await<CallEvent.LocalDescription>(aEvents) { it.kind == SdpKind.OFFER }.sdp)
            a.applyRemoteAnswer(id, 1, await<CallEvent.LocalDescription>(bEvents) { it.kind == SdpKind.ANSWER }.sdp)
            val callback = held.poll(5, TimeUnit.SECONDS) ?: error("missing onTrack callback")
            val oldSession = a.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(a)
            a.close()
            callback()
            val remoteTracks = oldSession.javaClass.getDeclaredField("remoteTracks").apply { isAccessible = true }.get(oldSession) as List<*>
            assertTrue("native track attached after dispose", remoteTracks.isEmpty())
            assertTrue("closed peer emitted an event", aEvents.none { it is CallEvent.PeerFailed })
        } finally { a.close(); b.close() }
    }
    @Test(timeout = 60000) fun twoHeadlessNativePeersExchangeGeneratedAudio() { exerciseAudio(false, false) }
    @Test(timeout = 60000) fun silentSourceAIsRejectedDespiteComfortNoise() { exerciseAudio(true, false) }
    @Test(timeout = 60000) fun silentSourceBIsRejectedDespiteComfortNoise() { exerciseAudio(false, true) }

    private fun exerciseAudio(silentA: Boolean, silentB: Boolean) {
        val aEvents = LinkedBlockingQueue<CallEvent>()
        val bEvents = LinkedBlockingQueue<CallEvent>()
        val aEnergy = AtomicLong()
        val bEnergy = AtomicLong()
        val aSamples = AtomicLong()
        val bSamples = AtomicLong()
        val aSource = java.util.concurrent.atomic.AtomicReference<CustomAudioSource>()
        val bSource = java.util.concurrent.atomic.AtomicReference<CustomAudioSource>()
        fun energy(pcm: ByteArray, sum: AtomicLong, count: AtomicLong) {
            for (i in 0 until pcm.size - 1 step 2) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort().toInt()
                sum.addAndGet(kotlin.math.abs(sample).toLong()); count.incrementAndGet()
            }
        }
        val a = DesktopCallPeer({ aEvents.offer(it) }, { HeadlessAudioDeviceModule() }, { CustomAudioSource().also(aSource::set) }, { energy(it, aEnergy, aSamples) })
        val b = DesktopCallPeer({ bEvents.offer(it) }, { HeadlessAudioDeviceModule() }, { CustomAudioSource().also(bSource::set) }, { energy(it, bEnergy, bSamples) })
        val id = "f".repeat(32)
        try {
            a.open(id, PeerRole.OFFERER, emptyList(), false)
            b.open(id, PeerRole.ANSWERER, emptyList(), false)
            a.createOffer(id, 1, false)
            val offer = await<CallEvent.LocalDescription>(aEvents) { it.kind == SdpKind.OFFER }
            b.applyRemoteOffer(id, 1, offer.sdp)
            val answer = await<CallEvent.LocalDescription>(bEvents) { it.kind == SdpKind.ANSWER }
            a.applyRemoteAnswer(id, 1, answer.sdp)
            val start = System.nanoTime()
            repeat(400) { tick ->
                transfer(aEvents, b); transfer(bEvents, a)
                for ((source, hz) in listOf(aSource.get() to (if (silentA) 0 else 440), bSource.get() to (if (silentB) 0 else 660))) {
                    val pcm = ByteArray(960)
                    repeat(480) { i ->
                        val sample = (12_000 * sin(2 * Math.PI * hz * (tick * 480L + i) / 48_000)).toInt()
                        pcm[2 * i] = sample.toByte(); pcm[2 * i + 1] = (sample shr 8).toByte()
                    }
                    source.pushAudio(pcm, 16, 48_000, 1, 480)
                }
                val left = start + TimeUnit.MILLISECONDS.toNanos((tick + 1) * 10L) - System.nanoTime()
                if (left > 0) TimeUnit.NANOSECONDS.sleep(left)
            }
            val aRtp = a.inboundAudioRtp().get(5, TimeUnit.SECONDS)
            val bRtp = b.inboundAudioRtp().get(5, TimeUnit.SECONDS)
            assertTrue("A inbound audio RTP packets and bytes: $aRtp", aRtp.first > 0 && aRtp.second > 0)
            assertTrue("B inbound audio RTP packets and bytes: $bRtp", bRtp.first > 0 && bRtp.second > 0)
            val aMean = aEnergy.get() / aSamples.get().coerceAtLeast(1)
            val bMean = bEnergy.get() / bSamples.get().coerceAtLeast(1)
            assertTrue("A must decode audio frames", aSamples.get() > 1000)
            assertTrue("B must decode audio frames", bSamples.get() > 1000)
            assertEquals("A decoded energy (B source), RTP=$aRtp mean=$aMean", !silentB, aMean > 100)
            assertEquals("B decoded energy (A source), RTP=$bRtp mean=$bMean", !silentA, bMean > 100)
        } finally { a.close(); b.close() }
    }
    private inline fun <reified T : CallEvent> await(queue: LinkedBlockingQueue<CallEvent>, predicate: (T) -> Boolean): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val saved = mutableListOf<CallEvent.LocalCandidate>()
        while (System.nanoTime() < deadline) {
            val e = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (e is CallEvent.PeerFailed) fail("Native peer failure")
            if (e is CallEvent.LocalCandidate) saved.add(e)
            if (e is T && predicate(e)) { saved.forEach(queue::offer); return e }
        }
        error("Timed out awaiting ${T::class.simpleName}")
    }
    private fun transfer(events: LinkedBlockingQueue<CallEvent>, destination: DesktopCallPeer) {
        val pending = mutableListOf<CallEvent>(); events.drainTo(pending)
        pending.filterIsInstance<CallEvent.LocalCandidate>().forEach {
            assertEquals("candidate stamped with the negotiated generation", 1, it.gen)
            destination.addRemoteCandidates(it.callId, it.gen, listOf(it.candidate))
        }
        if (pending.any { it is CallEvent.PeerFailed }) fail("Native peer failure")
    }
}
