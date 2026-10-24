package com.relay.app.transport.mailbox

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class MailboxStreamingTest {
    private val base = "https://mailbox.example"
    private val device = "1".repeat(32)
    private val recipient = "b".repeat(64)
    private val bytes = ByteArray(180_123) { (it % 251).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private class Signer : NostrSigner {
        val signed = mutableListOf<List<List<String>>>()
        override fun signEventJson(kind: Int, content: String, tags: List<List<String>>): String {
            assertEquals(27235, kind); signed.add(tags); return "{}"
        }
    }
    private fun client(signer: Signer = Signer(), handler: (Request) -> Response): MailboxMedia.Client =
        MailboxMedia.Client(MailboxMedia.RequestContext(signer, device), base,
            OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build())
    private fun reply(request: Request, code: Int = 200, body: ResponseBody = ByteArray(0).toResponseBody()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build()
    private fun staging(): File {
        val dir = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), "wp09-mailbox-").toFile()
        dir.deleteOnExit()
        return File(dir, "part.private").also { it.deleteOnExit() }
    }
    private inline fun fails(block: () -> Unit): Throwable {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected failure")
    }

    @Test fun transportHasCompatibleMediaReferenceSeam() {
        assertTrue("Transport must carry media refs without changing existing send overrides",
            com.relay.app.transport.Transport::class.java.methods.any { it.name == "sendWithMedia" })
    }

    @Test fun defaultTransportNeverSilentlyDiscardsMediaReferences() = kotlinx.coroutines.runBlocking {
        var sends = 0
        val transport = object : com.relay.app.transport.Transport {
            override val status = kotlinx.coroutines.flow.MutableStateFlow(com.relay.app.transport.TransportStatus.STOPPED)
            override val incoming = kotlinx.coroutines.flow.emptyFlow<com.relay.app.transport.IncomingEnvelope>()
            override suspend fun start() {}
            override suspend fun stop() {}
            override suspend fun reconnect() {}
            override suspend fun send(recipientPubkeyHex: String, payload: com.relay.app.transport.RelayPayload,
                                      relayHints: List<String>, ephemeral: Boolean): com.relay.app.transport.SendResult {
                assertEquals(recipient, recipientPubkeyHex)
                assertEquals(listOf("hint"), relayHints); assertFalse(ephemeral)
                sends++; return com.relay.app.transport.SendResult.Sent(1)
            }
        }
        val payload = com.relay.app.transport.RelayPayload("id", 1L, "opaque")
        assertEquals(com.relay.app.transport.SendResult.Sent(1), transport.sendWithMedia(recipient, payload, emptyList(), listOf("hint")))
        val result = transport.sendWithMedia(recipient, payload, listOf(sha(bytes))) as com.relay.app.transport.SendResult.Failed
        assertFalse(result.retryable); assertEquals(1, sends)
    }

    @Test fun sendBodyPreservesLegacyBytesAndBoundsMediaReferences() {
        val legacy = MailboxProtocol.sendBody("r", "id", "e")
        assertEquals(legacy, MailboxProtocol.sendBody("r", "id", "e", emptyList()))
        val original = JSONObject().put("v", 1).put("deliveries", org.json.JSONArray().put(
            JSONObject().put("to", "r").put("id", "id").put("envelope", "e"))).toString()
        assertEquals(original, legacy)
        val hashes = (0..7).map { it.toString(16).padStart(64, '0') }
        assertEquals(8, JSONObject(MailboxProtocol.sendBody("r", "id", "e", hashes)).getJSONArray("media").length())
        for (bad in listOf(listOf("x"), listOf("A".repeat(64)), listOf(hashes[0], hashes[0]), hashes + "f".repeat(64))) {
            assertTrue(fails { MailboxProtocol.sendBody("r", "id", "e", bad) } is IllegalArgumentException)
        }
    }

    @Test fun precomputedDigestSignsExactQueryAndRejectsInvalidDigest() {
        val signer = Signer(); val url = "$base/v1/media/${sha(bytes)}?device=$device"
        MailboxAuth.headerWithDigest(signer, "PUT", url, sha(bytes))
        assertEquals(listOf("u", url), signer.signed.single()[0])
        assertEquals(listOf("payload", sha(bytes)), signer.signed.single()[2])
        assertTrue(fails { MailboxAuth.headerWithDigest(signer, "PUT", url, "A".repeat(64)) } is IllegalArgumentException)
        assertEquals(1, signer.signed.size)
    }

    @Test fun uploadReadsInBoundedChunksSignsDigestAndIsReplayable() {
        val signer = Signer(); var opens = 0; var closes = 0; var requests = 0
        val source = MailboxMedia.RawSource(bytes.size.toLong()) {
            opens++
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    assertTrue("unbounded source read $len", len <= 64 * 1024)
                    return super.read(b, off, len)
                }
                override fun close() { closes++; super.close() }
            }
        }
        val c = client(signer) { request ->
            requests++
            assertEquals("application/octet-stream", request.body!!.contentType().toString())
            assertEquals("$base/v1/media/${sha(bytes)}?device=$device", request.url.toString())
            repeat(2) { // OkHttp may replay a non-one-shot request body after a connection retry.
                val out = Buffer(); request.body!!.writeTo(out)
                assertArrayEquals(bytes, out.readByteArray())
            }
            reply(request)
        }
        assertEquals("$base/v1/media/${sha(bytes)}", c.upload(source, sha(bytes), listOf(recipient)))
        assertEquals(3, opens); assertEquals(opens, closes); assertEquals(1, requests)
        assertEquals(listOf("payload", sha(bytes)), signer.signed.single()[2])
    }

    @Test fun explicitRetryReopensSourceAndSignsFreshNonce() {
        val signer = Signer(); var calls = 0; var opens = 0
        val c = client(signer) { r -> r.body!!.writeTo(Buffer()); reply(r, if (calls++ == 0) 503 else 200) }
        val source = MailboxMedia.RawSource(bytes.size.toLong()) { opens++; bytes.inputStream() }
        assertEquals(503, (fails { c.upload(source, sha(bytes), listOf(recipient)) } as MailboxMedia.HttpFailure).status)
        c.upload(source, sha(bytes), listOf(recipient))
        assertEquals(4, opens); assertEquals(2, signer.signed.size)
        assertNotEquals(signer.signed[0].last(), signer.signed[1].last())
    }

    @Test fun maximumStoredBlobStreamsWithoutWholeBodyAllocation() {
        val size = MailboxMedia.MAX_STORED_BYTES
        val block = ByteArray(64 * 1024) { 37 }
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = size
        while (remaining > 0) { val n = minOf(remaining, block.size.toLong()).toInt(); digest.update(block, 0, n); remaining -= n }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        var opens = 0
        val source = MailboxMedia.RawSource(size) {
            opens++
            object : InputStream() {
                var left = size
                override fun read(): Int = throw AssertionError("bulk reads required")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    assertTrue(len <= 64 * 1024)
                    if (left == 0L) return -1
                    val n = minOf(left, len.toLong()).toInt()
                    java.util.Arrays.fill(b, off, off + n, 37.toByte()); left -= n; return n
                }
            }
        }
        val c = client { r ->
            assertEquals(size, r.body!!.contentLength())
            okio.blackholeSink().buffer().use { r.body!!.writeTo(it) }
            reply(r)
        }
        c.upload(source, hash, listOf(recipient))
        assertEquals(2, opens)
    }

    @Test fun mismatchAndInvalidLengthFailBeforeNetwork() {
        var requested = false
        val c = client { requested = true; reply(it) }
        assertTrue(fails { c.upload(MailboxMedia.RawSource(bytes.size.toLong()) { bytes.inputStream() }, "0".repeat(64), listOf(recipient)) } is IOException)
        assertTrue(fails { c.upload(MailboxMedia.RawSource(1) { bytes.inputStream() }, sha(bytes), listOf(recipient)) } is IOException)
        assertTrue(fails { c.upload(MailboxMedia.RawSource(MailboxMedia.MAX_STORED_BYTES + 1) { bytes.inputStream() }, sha(bytes), listOf(recipient)) } is IllegalArgumentException)
        assertFalse(requested)
    }

    @Test fun changingSourceOnReplayCannotSucceed() {
        var opens = 0
        val c = client { r -> r.body!!.writeTo(Buffer()); reply(r) }
        val changed = bytes.clone().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(fails {
            c.upload(MailboxMedia.RawSource(bytes.size.toLong()) { if (opens++ == 0) bytes.inputStream() else changed.inputStream() }, sha(bytes), listOf(recipient))
        } is IOException)
    }

    @Test fun fileUploadUsesOpaqueRawBytes() {
        val f = staging(); f.writeBytes(bytes)
        try {
            val c = client { r -> val out = Buffer(); r.body!!.writeTo(out); assertArrayEquals(bytes, out.readByteArray()); reply(r) }
            c.upload(f, sha(bytes), listOf(recipient))
        } finally { f.delete() }
    }

    @Test fun cancelClosesActiveUploadSourceAndPreventsNetwork() {
        val cancel = MailboxMedia.Cancellation(); var closed = false; var requested = false
        val c = client { requested = true; reply(it) }
        val error = fails { c.upload(MailboxMedia.RawSource(bytes.size.toLong()) {
            object : ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int { cancel.cancel(); return super.read(b, off, len) }
                override fun close() { closed = true; super.close() }
            }
        }, sha(bytes), listOf(recipient), cancel) }
        assertTrue(error is java.io.InterruptedIOException); assertTrue(closed); assertFalse(requested)
    }

    @Test fun crossThreadCancelUnblocksAndClosesBlockedSource() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val cancellation = MailboxMedia.Cancellation()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val c = client { throw AssertionError("Cancelled preflight must never reach HTTP") }
        val worker = Thread {
            try {
                c.upload(MailboxMedia.RawSource(1) {
                    object : InputStream() {
                        override fun read(): Int = throw AssertionError("must use bounded bulk read")
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            entered.countDown()
                            if (!closed.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw IOException("close did not unblock read")
                            throw IOException("closed")
                        }
                        override fun close() { closed.countDown() }
                    }
                }, "0".repeat(64), listOf(recipient), cancellation)
            } catch (e: Throwable) { failure.set(e) }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            cancellation.cancel()
            worker.join(2000)
            assertFalse("cancellation must unblock the source", worker.isAlive)
            assertTrue(failure.get() is java.io.InterruptedIOException)
            assertEquals(0L, closed.count)
        } finally { closed.countDown(); worker.join(6000) }
    }

    @Test fun successfulDownloadCreatesOnlyPrivateVerifiedStagingAndUsesDeviceQuery() {
        val signer = Signer(); val f = staging()
        val c = client(signer) { r -> assertEquals("$base/v1/media/${sha(bytes)}?device=$device", r.url.toString()); reply(r, body = bytes.toResponseBody()) }
        try {
            assertEquals(bytes.size.toLong(), c.downloadTo("$base/v1/media/${sha(bytes)}", sha(bytes), f, bytes.size.toLong()))
            assertArrayEquals(bytes, f.readBytes())
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
            assertFalse(signer.signed.single().any { it[0] == "payload" })
        } finally { f.delete() }
    }

    @Test fun oversizedAndLyingResponsesAreBoundedAndPartialStagingIsRemoved() {
        for (declared in listOf(-1L, 1L, bytes.size.toLong() + 100)) {
            var readBytes = 0L; var closed = false
            val body = object : ResponseBody() {
                val input = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        assertTrue(byteCount <= 64 * 1024)
                        val n = minOf(byteCount, bytes.size - readBytes).toInt()
                        if (n == 0) return -1
                        sink.write(bytes, readBytes.toInt(), n); readBytes += n; return n.toLong()
                    }
                    override fun close() { closed = true }
                }.buffer()
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = declared
                override fun source(): BufferedSource = input
            }
            val f = staging(); val c = client { reply(it, body = body) }
            assertTrue(fails { c.downloadTo("$base/v1/media/${sha(bytes)}", sha(bytes), f, 100) } is IOException)
            assertFalse(f.exists()); assertTrue(closed)
            assertTrue("readBytes=$readBytes", readBytes <= 8192)
        }
    }

    @Test fun wrongDigestOrDisconnectedDownloadRemovesPartialFile() {
        for (disconnect in listOf(false, true)) {
            val body = if (!disconnect) bytes.toResponseBody() else object : ResponseBody() {
                val input = object : Source {
                    var first = true
                    override fun timeout() = Timeout.NONE
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (!first) throw IOException("disconnected")
                        first = false; sink.write(bytes, 0, 32); return 32
                    }
                    override fun close() {}
                }.buffer()
                override fun contentType(): MediaType? = null
                override fun contentLength() = -1L
                override fun source() = input
            }
            val hash = if (disconnect) sha(bytes) else "0".repeat(64)
            val f = staging(); val c = client { reply(it, body = body) }
            assertTrue(fails { c.downloadTo("$base/v1/media/$hash", hash, f, bytes.size.toLong()) } is IOException)
            assertFalse(f.exists())
        }
    }

    @Test fun cancellationClosesDownloadAndRemovesStaging() {
        val cancel = MailboxMedia.Cancellation(); var closed = false
        val body = object : ResponseBody() {
            val input = object : Source {
                override fun timeout() = Timeout.NONE
                override fun read(sink: Buffer, byteCount: Long): Long { cancel.cancel(); sink.writeByte(1); return 1 }
                override fun close() { closed = true }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = input
        }
        val f = staging(); val c = client { reply(it, body = body) }
        assertTrue(fails { c.downloadTo("$base/v1/media/${sha(bytes)}", sha(bytes), f, 100, cancel) } is java.io.InterruptedIOException)
        assertTrue(closed); assertFalse(f.exists())
    }

    @Test fun legacyAndPrimaryContextsKeepExactSignedUrlAndRejectMalformedDeviceIds() {
        for (id in listOf(null, "primary", device)) {
            val signer = Signer()
            val c = MailboxMedia.Client(MailboxMedia.RequestContext(signer, id), base,
                OkHttpClient.Builder().addInterceptor { chain -> reply(chain.request(), 204) }.build())
            val url = "$base/v1/media/${sha(bytes)}"
            assertEquals(MailboxMedia.Confirmation.Confirmed, c.confirm(url))
            assertEquals(listOf("u", url + if (id == null) "" else "?device=$id"), signer.signed.single()[0])
            assertEquals(listOf("method", "DELETE"), signer.signed.single()[1])
        }
        for (bad in listOf("", "PRIMARY", "a".repeat(31), "A".repeat(32), "primary&device=x")) {
            assertTrue(fails { MailboxMedia.RequestContext(Signer(), bad) } is IllegalArgumentException)
        }
        for (badOrigin in listOf("http://mailbox.example", "https://user@mailbox.example", "$base/path", "$base/?x=1")) {
            assertTrue(fails { MailboxMedia.Client(MailboxMedia.RequestContext(Signer()), badOrigin) } is IllegalArgumentException)
        }
    }

    @Test fun lyingContentLengthWithinByteCapCannotPublishStaging() {
        for (declared in listOf(1L, bytes.size.toLong() + 1)) {
            val f = staging()
            val c = client { r -> reply(r, body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = declared
                override fun source(): BufferedSource = Buffer().write(bytes)
            }) }
            assertTrue(fails { c.downloadTo("$base/v1/media/${sha(bytes)}", sha(bytes), f, bytes.size.toLong() + 10) } is IOException)
            assertFalse(f.exists())
        }
    }

    @Test fun foreignOrAmbiguousUrlsNeverReachHttpOrDeleteExistingFiles() {
        var calls = 0; val c = client { calls++; reply(it) }
        val hash = sha(bytes)
        for (url in listOf("https://evil.example/v1/media/$hash", "$base/v1/media/$hash?device=primary", "$base/v1/media/$hash#x", "$base/v1/media/${"0".repeat(64)}")) {
            val f = staging()
            fails { c.downloadTo(url, hash, f, bytes.size.toLong()) }
            assertFalse(f.exists())
        }
        val existing = staging(); existing.writeText("keep")
        try { fails { c.downloadTo("$base/v1/media/$hash", hash, existing, bytes.size.toLong()) }; assertEquals("keep", existing.readText()) }
        finally { existing.delete() }
        assertEquals(0, calls)
    }

    @Test fun redirectsAreRejectedForEveryOperationAndNeverFollowed() {
        var calls = 0
        val c = client { r -> calls++; reply(r, 302).newBuilder().header("Location", "https://evil.example/stolen").build() }
        val hash = sha(bytes); val url = "$base/v1/media/$hash"
        assertTrue(fails { c.upload(MailboxMedia.RawSource(bytes.size.toLong()) { bytes.inputStream() }, hash, listOf(recipient)) } is MailboxMedia.HttpFailure)
        val f = staging(); assertTrue(fails { c.downloadTo(url, hash, f, bytes.size.toLong()) } is MailboxMedia.HttpFailure)
        assertFalse(f.exists())
        val result = c.confirm(url) as MailboxMedia.Confirmation.Failed
        assertEquals(302, result.status); assertEquals(3, calls)
    }

    @Test fun realHttpsRedirectNeverContactsOtherOriginForUploadDownloadOrConfirm() {
        val dir = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), "wp09-tls-")
        val store = dir.resolve("test.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-dname", "CN=localhost", "-storetype", "PKCS12", "-keystore", store.toString(),
            "-storepass", "test-only", "-keypass", "test-only", "-noprompt").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        val ks = java.security.KeyStore.getInstance("PKCS12").also { s -> Files.newInputStream(store).use { s.load(it, "test-only".toCharArray()) } }
        val km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).also { it.init(ks, "test-only".toCharArray()) }
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        }
        val ssl = javax.net.ssl.SSLContext.getInstance("TLS").also { it.init(km.keyManagers, arrayOf(trust), java.security.SecureRandom()) }
        fun server() = com.sun.net.httpserver.HttpsServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).also {
            it.httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(ssl)
        }
        val foreign = server(); val origin = server(); val reached = java.util.concurrent.atomic.AtomicInteger()
        foreign.createContext("/") { exchange -> reached.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        origin.createContext("/") { exchange ->
            exchange.requestBody.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
            exchange.responseHeaders.add("Location", "https://localhost:${foreign.address.port}/stolen")
            exchange.sendResponseHeaders(307, -1); exchange.close()
        }
        foreign.start(); origin.start()
        val raw = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
        try {
            val c = MailboxMedia.Client(MailboxMedia.RequestContext(Signer(), device), "https://localhost:${origin.address.port}", raw)
            val hash = sha(bytes); val url = c.urlFor(hash)
            assertEquals(307, (fails { c.upload(MailboxMedia.RawSource(bytes.size.toLong()) { bytes.inputStream() }, hash, listOf(recipient)) } as MailboxMedia.HttpFailure).status)
            val f = staging()
            assertEquals(307, (fails { c.downloadTo(url, hash, f, bytes.size.toLong()) } as MailboxMedia.HttpFailure).status)
            assertFalse(f.exists())
            assertEquals(307, (c.confirm(url) as MailboxMedia.Confirmation.Failed).status)
            assertEquals(0, reached.get())
        } finally {
            origin.stop(0); foreign.stop(0)
            raw.connectionPool.evictAll(); raw.dispatcher.executorService.shutdown()
            Files.deleteIfExists(store); Files.deleteIfExists(dir)
        }
    }

    @Test fun confirmationReturnsFailureAndRevocationExplicitly() {
        for ((status, code) in listOf(500 to "storage_error", 401 to "auth_revoked", 403 to "unknown_device")) {
            val c = client { reply(it, status, "{\"v\":1,\"error\":{\"code\":\"$code\",\"retryable\":false}}".toResponseBody()) }
            val result = c.confirm("$base/v1/media/${sha(bytes)}") as MailboxMedia.Confirmation.Failed
            assertEquals(status, result.status); assertEquals(code, result.code)
            if (status == 401) {
                val f = staging(); val e = fails { c.downloadTo("$base/v1/media/${sha(bytes)}", sha(bytes), f, 100) } as MailboxMedia.HttpFailure
                assertEquals("auth_revoked", e.code); assertFalse(f.exists())
            }
        }
        assertEquals(MailboxMedia.Confirmation.Confirmed, client { reply(it, 204) }.confirm("$base/v1/media/${sha(bytes)}"))
        val failed = client { throw IOException("offline") }.confirm("$base/v1/media/${sha(bytes)}") as MailboxMedia.Confirmation.Failed
        assertNull(failed.status)
    }
}
