package com.relay.app.desktop

import com.relay.app.transport.SendResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rust.nostr.sdk.Keys
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Acceptance (2): real end-to-end text through a LOCAL `wrangler dev --local` Worker.
 *
 * This test is skipped unless RELAY_E2E=1, so the default :desktop:test stays fast and hermetic.
 * Run it with:
 *   RELAY_E2E=1 ./gradlew :desktop:test --tests '*DesktopLocalWorkerE2ETest'
 *
 * The "JVM peer" is a second DesktopEngine with its own identity and encrypted store. That exercises
 * the same wire path as linux-port/spikes/jvm-transport without depending on the emulator harness.
 */
class DesktopLocalWorkerE2ETest {

    @Test
    fun `desktop sends and receives encrypted text through the local Worker`() = runBlocking {
        assumeTrue("Set RELAY_E2E=1 to run the local Worker end-to-end test", System.getenv("RELAY_E2E") == "1")

        val repoRoot = Path.of("..").toAbsolutePath().normalize()
        val workerDir = System.getenv("RELAY_WORKER_DIR")?.let { Path.of(it) }
            ?: repoRoot.resolve("server/cloudflare")
        val node = System.getenv("RELAY_NODE") ?: "/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node"
        assumeTrue("Node 24+ not found at $node", Files.isRegularFile(Path.of(node)))
        assumeTrue("wrangler not installed", Files.isRegularFile(workerDir.resolve("node_modules/wrangler/bin/wrangler.js")))

        val port = 8791
        // WP06 evidence uses .local/wp06-worker-state; a temp dir keeps the default test hermetic.
        val persist = System.getenv("RELAY_E2E_PERSIST")?.let {
            Path.of(it).toAbsolutePath().also { p -> Files.createDirectories(p) }
        } ?: Files.createTempDirectory("relay-e2e-worker")
        val log = Files.createTempFile("relay-e2e-worker", ".log")
        val desktopKeys = Keys.generate()
        val peerKeys = Keys.generate()
        val allowlist = desktopKeys.publicKey().toHex() + "," + peerKeys.publicKey().toHex()

        val worker = ProcessBuilder(
            node,
            "node_modules/wrangler/bin/wrangler.js",
            "dev",
            "--local",
            "--port",
            port.toString(),
            "--var",
            "ALLOWED_PUBKEYS:$allowlist",
            "--persist-to",
            persist.toString(),
        )
            .directory(workerDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .apply { environment()["WRANGLER_SEND_METRICS"] = "false" }
            .start()

        try {
            waitForHealth(port, worker, log, 90)

            val temp = Files.createTempDirectory("relay-e2e")
            val desktopPaths = DesktopPaths(temp.resolve("desktop-config"), temp.resolve("desktop-data"))
            val peerPaths = DesktopPaths(temp.resolve("peer-config"), temp.resolve("peer-data"))
            val desktopSecrets = FileSecretStore(desktopPaths.secretFile)
            val peerSecrets = FileSecretStore(peerPaths.secretFile)
            val desktopIdentity = DesktopIdentity(desktopPaths, desktopSecrets, desktopKeys)
            val peerIdentity = DesktopIdentity(peerPaths, peerSecrets, peerKeys)
            val desktopKeyMaterial = DesktopKeyMaterial(desktopPaths, desktopSecrets)
            val peerKeyMaterial = DesktopKeyMaterial(peerPaths, peerSecrets)
            val desktopStore = DesktopMessageStore(desktopPaths.databaseFile, desktopSecrets)
            val peerStore = DesktopMessageStore(peerPaths.databaseFile, peerSecrets)
            val desktopPub = desktopIdentity.publicKeyHex()
            val peerPub = peerIdentity.publicKeyHex()

            val desktopTransport = DesktopMailboxTransport("http://127.0.0.1:$port", desktopIdentity.keys(), desktopIdentity.signer(), desktopStore)
            val peerTransport = DesktopMailboxTransport("http://127.0.0.1:$port", peerIdentity.keys(), peerIdentity.signer(), peerStore)

            val desktopEngine = DesktopEngine(
                desktopIdentity, desktopKeyMaterial, desktopStore, desktopTransport,
                peerInnerKeys = mapOf(peerPub to peerKeyMaterial.publicKeyBase64()),
            )
            val peerEngine = DesktopEngine(
                peerIdentity, peerKeyMaterial, peerStore, peerTransport,
                peerInnerKeys = mapOf(desktopPub to desktopKeyMaterial.publicKeyBase64()),
            )

            try {
                desktopEngine.start()
                peerEngine.start()

                val outbound = desktopEngine.sendText(peerPub, "hello from the desktop")
                assertTrue("desktop send failed: $outbound", outbound is SendResult.Sent)
                waitForMessage(peerEngine, desktopPub, "hello from the desktop", 30)

                val reply = peerEngine.sendText(desktopPub, "hello back from the JVM peer")
                assertTrue("peer send failed: $reply", reply is SendResult.Sent)
                waitForMessage(desktopEngine, peerPub, "hello back from the JVM peer", 30)

                val peerIncoming = peerEngine.messages(desktopPub).single { it.body == "hello from the desktop" }
                assertTrue(!peerIncoming.outgoing)
                val desktopIncoming = desktopEngine.messages(peerPub).single { it.body == "hello back from the JVM peer" }
                assertTrue(!desktopIncoming.outgoing)
                assertEquals(1, desktopEngine.messages(peerPub).count { it.body == "hello back from the JVM peer" })

                println("E2E OK desktop=$desktopPub peer=$peerPub port=$port persist=$persist")
            } finally {
                desktopEngine.close()
                peerEngine.close()
                desktopStore.close()
                peerStore.close()
            }
        } finally {
            worker.destroy()
            if (!worker.waitFor(15, TimeUnit.SECONDS)) worker.destroyForcibly()
        }
    }

    private suspend fun waitForHealth(port: Int, worker: Process, log: Path, timeoutSeconds: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        var lastError: Exception? = null
        while (System.nanoTime() < deadline) {
            if (!worker.isAlive) {
                error("Local Worker exited; log:\n" + Files.readString(log))
            }
            try {
                val conn = URI("http://127.0.0.1:$port/v1/health").toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                if (conn.responseCode == 200 && conn.inputStream.bufferedReader().readText().contains("\"ok\":true")) return
            } catch (e: Exception) {
                lastError = e
            }
            delay(250)
        }
        error("Local Worker did not become healthy in ${timeoutSeconds}s: $lastError\n" + runCatching { Files.readString(log) }.getOrElse { "" })
    }

    private suspend fun waitForMessage(engine: DesktopEngine, peer: String, body: String, timeoutSeconds: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        while (System.nanoTime() < deadline) {
            if (engine.messages(peer).any { it.body == body }) return
            delay(100)
        }
        error("Message '$body' did not arrive within ${timeoutSeconds}s")
    }
}
