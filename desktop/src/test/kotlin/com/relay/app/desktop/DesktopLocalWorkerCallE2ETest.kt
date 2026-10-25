package com.relay.app.desktop

import com.relay.app.call.CallOutcome
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rust.nostr.sdk.Keys
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

/**
 * WP14 gated end-to-end: two desktop call hosts, each with its own real [DesktopMailboxTransport], talk through a LOCAL
 * `wrangler dev --local` Worker (real `signal` frames, real stored urgent invite, real NIP-98 auth) and exchange generated
 * audio over native webrtc-java peers. Skipped unless RELAY_E2E=1.
 *
 * TURN: a local Worker has no TURN secrets, so its real `GET /v1/call/ice` answers `503 not_configured` (asserted below).
 * The credentials the hosts actually use come from a fake HTTP server through the same authenticated fetch code path.
 * Media therefore flows over ICE host candidates on loopback; TURN-only relay is NOT exercised. No physical microphone.
 */
class DesktopLocalWorkerCallE2ETest {

    /** Real transport for signalling; ICE comes from a second transport pointed at a fake HTTP server. */
    private class Link(val real: DesktopMailboxTransport, val ice: DesktopMailboxTransport) : DesktopCallLink {
        override var callRouter: DesktopCallRouter?
            get() = real.callRouter
            set(v) { real.callRouter = v }
        val urgentResults = CopyOnWriteArrayList<SendResult>()
        override fun isOnline() = real.isOnline()
        override suspend fun signal(to: String, payload: RelayPayload) = real.signal(to, payload)
        override suspend fun deliverUrgent(to: String, payload: RelayPayload) = real.deliverUrgent(to, payload).also { urgentResults += it }
        override fun fetchIce(reply: (Int, String?) -> Unit) = ice.fetchIce(reply)
    }

    private class Audio {
        val source = AtomicReference<CustomAudioSource>()
        val energy = AtomicLong(); val samples = AtomicLong()
        fun onPcm(pcm: ByteArray) {
            for (i in 0 until pcm.size - 1 step 2) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 255)).toShort().toInt()
                energy.addAndGet(kotlin.math.abs(sample).toLong()); samples.incrementAndGet()
            }
        }
        fun mean() = energy.get() / samples.get().coerceAtLeast(1)
    }

    @Test(timeout = 240_000)
    fun twoDesktopHostsRingAnswerExchangeGeneratedAudioAndHangUpThroughTheLocalWorker() = runBlocking {
        assumeTrue("Set RELAY_E2E=1 to run the local Worker end-to-end test", System.getenv("RELAY_E2E") == "1")
        val repoRoot = Path.of("..").toAbsolutePath().normalize()
        val workerDir = System.getenv("RELAY_WORKER_DIR")?.let { Path.of(it) } ?: repoRoot.resolve("server/cloudflare")
        val node = System.getenv("RELAY_NODE") ?: "/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node"
        assumeTrue("Node 24+ not found at $node", Files.isRegularFile(Path.of(node)))
        assumeTrue("wrangler not installed", Files.isRegularFile(workerDir.resolve("node_modules/wrangler/bin/wrangler.js")))

        val port = 8794
        val persist = Files.createTempDirectory("relay-call-e2e-worker")
        val log = Files.createTempFile("relay-call-e2e-worker", ".log")
        val aKeys = Keys.generate(); val bKeys = Keys.generate()
        val a = aKeys.publicKey().toHex(); val b = bKeys.publicKey().toHex()
        val worker = ProcessBuilder(node, "node_modules/wrangler/bin/wrangler.js", "dev", "--local", "--port", port.toString(),
            "--var", "ALLOWED_PUBKEYS:$a,$b", "--persist-to", persist.toString())
            .directory(workerDir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
            .apply { environment()["WRANGLER_SEND_METRICS"] = "false" }.start()

        val fakeTurn = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody(
                    """{"v":1,"iceServers":[{"urls":["stun:127.0.0.1:9"]},{"urls":["turn:127.0.0.1:9?transport=udp"],"username":"stub","credential":"stub"}],"expiresAt":${System.currentTimeMillis() + 600_000}}""")
            }
            start()
        }
        val temp = Files.createTempDirectory("relay-call-e2e")
        val stores = mutableListOf<DesktopMessageStore>()
        val transports = mutableListOf<DesktopMailboxTransport>()
        val services = mutableListOf<DesktopCallService>()
        try {
            waitForHealth(port, worker, log)
            fun party(label: String, keys: Keys): Triple<DesktopMailboxTransport, DesktopMailboxTransport, DesktopMessageStore> {
                val paths = DesktopPaths(temp.resolve("$label-config"), temp.resolve("$label-data"))
                val secrets = FileSecretStore(paths.secretFile)
                val store = DesktopMessageStore(paths.databaseFile, secrets).also { stores += it }
                val real = DesktopMailboxTransport("http://127.0.0.1:$port", keys, DesktopNostrSigner(keys), store).also { transports += it }
                val ice = DesktopMailboxTransport(fakeTurn.url("/").toString(), keys, DesktopNostrSigner(keys), store).also { transports += it }
                return Triple(real, ice, store)
            }
            val (aReal, aIce, aStore) = party("a", aKeys)
            val (bReal, bIce, bStore) = party("b", bKeys)
            aReal.start(); bReal.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while ((!aReal.isOnline() || !bReal.isOnline()) && System.nanoTime() < deadline) delay(50)
            assertTrue("both sockets online", aReal.isOnline() && bReal.isOnline())

            // The real Worker endpoint is reachable and authenticated; locally it has no TURN secrets.
            val realIce = java.util.concurrent.LinkedBlockingQueue<Pair<Int, String?>>()
            aReal.fetchIce { s, body -> realIce.add(s to body) }
            val (iceStatus, iceBody) = realIce.poll(15, TimeUnit.SECONDS)!!
            assertEquals("local Worker /v1/call/ice without secrets", 503, iceStatus)
            assertTrue(iceBody!!.contains("not_configured"))

            val aAudio = Audio(); val bAudio = Audio()
            val aLink = Link(aReal, aIce); val bLink = Link(bReal, bIce)
            val aRecords = CopyOnWriteArrayList<DesktopCallRecord>(); val bRecords = CopyOnWriteArrayList<DesktopCallRecord>()
            fun svc(self: String, other: String, link: Link, audio: Audio, rec: MutableList<DesktopCallRecord>) = DesktopCallService(
                self, "d".repeat(32), link, { it == other }, { rec += it }, { "peer" }, DesktopCallEpochClock,
                makePeer = { post -> DesktopCallPeer(post, { HeadlessAudioDeviceModule() }, { CustomAudioSource().also(audio.source::set) }, audio::onPcm) },
                lingerMs = 200,
            ).also { services += it }
            val aSvc = svc(a, b, aLink, aAudio, aRecords)
            val bSvc = svc(b, a, bLink, bAudio, bRecords)

            assertEquals(null, aSvc.start(b))
            waitUntil("B rings", 20) { bSvc.state.value.stage == DesktopCallStage.RINGING_IN }
            val callId = bSvc.state.value.callId
            assertEquals(a, bSvc.state.value.peer)
            waitUntil("urgent stored invite accepted by the Worker", 10) { aLink.urgentResults.isNotEmpty() }
            assertTrue("urgent: ${aLink.urgentResults}", aLink.urgentResults.first() is SendResult.Sent)
            delay(1500) // the stored copy also reaches B over its socket; the replay guard must not ring twice or restart the call
            assertEquals(callId, bSvc.state.value.callId)
            assertEquals(DesktopCallStage.RINGING_IN, bSvc.state.value.stage)

            bSvc.accept()
            waitUntil("both active", 60) { aSvc.state.value.stage == DesktopCallStage.ACTIVE && bSvc.state.value.stage == DesktopCallStage.ACTIVE }

            val pump = Thread {
                val start = System.nanoTime()
                repeat(500) { tick ->
                    for ((audio, hz) in listOf(aAudio to 440, bAudio to 660)) {
                        val source = audio.source.get() ?: continue
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
            }.also { it.start() }
            pump.join()
            assertTrue("A decoded frames ${aAudio.samples}", aAudio.samples.get() > 1000)
            assertTrue("B decoded frames ${bAudio.samples}", bAudio.samples.get() > 1000)
            assertTrue("A heard B's tone, mean=${aAudio.mean()}", aAudio.mean() > 100)
            assertTrue("B heard A's tone, mean=${bAudio.mean()}", bAudio.mean() > 100)

            aSvc.setMuted(true)
            waitUntil("B sees the mute hint", 10) { bSvc.state.value.status.contains("muted") }
            assertTrue(aSvc.state.value.muted)

            aSvc.hangUp()
            val over = setOf(DesktopCallStage.ENDED, DesktopCallStage.IDLE)
            waitUntil("both ended", 15) { aSvc.state.value.stage in over && bSvc.state.value.stage in over }
            waitUntil("records", 10) { aRecords.isNotEmpty() && bRecords.isNotEmpty() }
            assertEquals(CallOutcome.COMPLETED, aRecords.single().outcome); assertEquals(false, aRecords.single().incoming)
            assertEquals(CallOutcome.COMPLETED, bRecords.single().outcome); assertEquals(true, bRecords.single().incoming)
            assertTrue(aRecords.single().durationMs > 0)
            // The stored invite was handled and removed from B's raw queue (no stuck call packet).
            val rawDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (bStore.pending().isNotEmpty() && System.nanoTime() < rawDeadline) delay(100)
            assertEquals(0, bStore.pending().size)
            // Signalling never became a chat row on either side.
            assertEquals(0, bStore.messages(a).size); assertEquals(0, aStore.messages(b).size)
            println("CALL E2E OK a=${a.take(8)} b=${b.take(8)} callId=$callId aMean=${aAudio.mean()} bMean=${bAudio.mean()} durationMs=${aRecords.single().durationMs}")
        } finally {
            services.forEach { runCatching { it.close() } }
            transports.forEach { runCatching { runBlocking { it.stop() } } }
            stores.forEach { runCatching { it.close() } }
            runCatching { fakeTurn.shutdown() }
            val handle = worker.toHandle()
            handle.descendants().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
            worker.waitFor(15, TimeUnit.SECONDS)
        }
    }

    private suspend fun waitUntil(what: String, seconds: Long, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!cond() && System.nanoTime() < end) delay(50)
        assertTrue("timed out waiting for: $what", cond())
    }

    private suspend fun waitForHealth(port: Int, worker: Process, log: Path) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < end) {
            check(worker.isAlive) { "Local Worker exited; log:\n" + Files.readString(log) }
            try {
                val conn = URI("http://127.0.0.1:$port/v1/health").toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 2000; conn.readTimeout = 2000
                if (conn.responseCode == 200 && conn.inputStream.bufferedReader().readText().contains("\"ok\":true")) return
            } catch (_: Exception) {}
            delay(250)
        }
        error("Local Worker did not become healthy in 90s")
    }
}
