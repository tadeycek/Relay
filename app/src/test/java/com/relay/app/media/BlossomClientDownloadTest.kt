package com.relay.app.media

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Exercises the download path (hash check, size cap, redirects, error status) against a minimal
 * raw-socket HTTP server. (com.sun.net.httpserver is not visible to Android unit-test compilation.)
 */
class BlossomClientDownloadTest {

    private lateinit var listener: ServerSocket
    private val blob = ByteArray(2000) { (it % 200).toByte() }
    private val blobSha = MediaCrypto.sha256Hex(blob)
    private val base get() = "http://127.0.0.1:${listener.localPort}"
    private val client by lazy { BlossomClient(urlCheck = { it.startsWith(base) }) }

    @Before
    fun start() {
        listener = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                val s = try { listener.accept() } catch (e: Exception) { return@thread }
                thread(isDaemon = true) { handle(s) }
            }
        }
    }

    @After
    fun stop() = listener.close()

    private fun handle(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) { val l = reader.readLine(); if (l.isNullOrEmpty()) break }
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            val out = s.getOutputStream()
            fun respond(status: String, headers: List<String> = emptyList(), body: ByteArray = ByteArray(0), contentLength: Boolean = true) {
                val head = ByteArrayOutputStream()
                head.write("HTTP/1.1 $status\r\n".toByteArray())
                headers.forEach { head.write("$it\r\n".toByteArray()) }
                if (contentLength) head.write("Content-Length: ${body.size}\r\n".toByteArray())
                head.write("Connection: close\r\n\r\n".toByteArray())
                out.write(head.toByteArray()); out.write(body); out.flush()
            }
            when (path) {
                "/ok" -> respond("200 OK", body = blob)
                "/missing" -> respond("404 Not Found")
                "/chunked" -> respond("200 OK", body = ByteArray(50_000), contentLength = false) // unknown length
                "/redir1" -> respond("302 Found", listOf("Location: $base/ok"))
                "/loop" -> respond("302 Found", listOf("Location: $base/loop"))
                "/evil" -> respond("302 Found", listOf("Location: http://evil.example/x"))
                else -> respond("404 Not Found")
            }
        }
    }

    @Test
    fun returnsBytesWhenHashMatches() {
        assertArrayEquals(blob, client.download("$base/ok", blobSha, 10_000))
    }

    @Test
    fun uppercaseExpectedHashStillMatches() {
        assertArrayEquals(blob, client.download("$base/ok", blobSha.uppercase(), 10_000))
    }

    @Test
    fun rejectsWhenHashDiffers() {
        assertNull(client.download("$base/ok", "00".repeat(32), 10_000))
    }

    @Test
    fun rejectsWhenLargerThanCap() {
        assertNull("content-length above cap", client.download("$base/ok", blobSha, 1_000))
        assertNull(
            "streamed body above cap with no content-length",
            client.download("$base/chunked", MediaCrypto.sha256Hex(ByteArray(50_000)), 10_000),
        )
    }

    @Test
    fun rejectsErrorStatus() {
        assertNull(client.download("$base/missing", blobSha, 10_000))
    }

    @Test
    fun followsAValidRedirectButNotLoopsOrDisallowedTargets() {
        assertArrayEquals(blob, client.download("$base/redir1", blobSha, 10_000))
        assertNull("redirect loop is bounded", client.download("$base/loop", blobSha, 10_000))
        assertNull("redirect to a disallowed URL", client.download("$base/evil", blobSha, 10_000))
    }

    @Test
    fun refusesDisallowedUrlUpFront() {
        assertNull(client.download("http://elsewhere.example/ok", blobSha, 10_000))
    }
}
