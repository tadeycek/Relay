package com.relay.app.desktop

import com.relay.app.linking.SyncBody
import com.relay.app.linking.SyncCodec
import com.relay.app.linking.SyncResult
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * Sent-copy sync (WP05-AC2), desktop side, against a fake server that enforces the same bounds the
 * Worker does (`POST /v1/sync` part rules, dedupe by id, cursor-ordered list).
 */
class SentSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val me = "a".repeat(64)
    private val peer = "b".repeat(64)
    private val deviceId = "1".repeat(32)

    // ---- fakes --------------------------------------------------------------------------------

    /** A stand-in for the Worker's sync routes. Bytes are stored exactly as posted. */
    private class FakeServer : SyncHttp {
        val events = mutableListOf<SyncEventDto>()
        var failPosts = false
        var failList = false
        var postCalls = 0
        val acks = mutableListOf<Long>()
        private var seq = 0L

        override suspend fun post(envelopeJson: String): SyncHttpResult<Boolean> {
            postCalls++
            if (failPosts) return SyncHttpResult.Retry("unreachable")
            val j = JSONObject(envelopeJson)
            // The Worker's parseSyncPart rules.
            require(j.getInt("v") == 1)
            require(Regex("[0-9a-f]{8,64}").matches(j.getString("id")))
            require(j.getLong("rev") >= 1)
            require(j.getInt("parts") == 1 && j.getInt("part") == 0)
            require(j.getLong("total") <= 12 * 1024)
            require(j.getString("hash").matches(Regex("[0-9a-f]{64}")))
            require(j.getLong("expires") > 0)
            val data = j.getString("data")
            require(data.length <= 16 * 1024)
            require(Base64.getDecoder().decode(data).size.toLong() == j.getLong("total"))
            events.firstOrNull { it.id == j.getString("id") }?.let { return SyncHttpResult.Ok(true) }
            events += SyncEventDto(++seq, j.getString("id"), j.getLong("rev"), j.getLong("total"), j.getString("hash"), data)
            return SyncHttpResult.Ok(false)
        }

        override suspend fun list(after: Long, limit: Int): SyncHttpResult<SyncPage> {
            if (failList) return SyncHttpResult.Retry("unreachable")
            val from = after
            val rows = events.filter { it.cursor > from }
            val page = rows.take(limit)
            return SyncHttpResult.Ok(SyncPage(page, rows.size > limit, page.lastOrNull()?.cursor ?: from))
        }

        override suspend fun ack(through: Long): SyncHttpResult<Long> {
            acks += through
            return SyncHttpResult.Ok(through)
        }

        /** Adds a hand-made event as the server would hold it. */
        fun inject(bytes: ByteArray, hash: String? = null, total: Long? = null, dataBase64: String? = null): Long {
            val h = hash ?: SyncWire.sha256Hex(bytes)
            events += SyncEventDto(++seq, "ff".repeat(8) + seq, 1, total ?: bytes.size.toLong(), h, dataBase64 ?: Base64.getEncoder().encodeToString(bytes))
            return seq
        }

        fun injectWire(wire: String) = inject(wire.toByteArray(Charsets.UTF_8))
    }

    private class FakeTransport(var result: SendResult = SendResult.Sent(1)) : Transport {
        private val _status = MutableStateFlow(TransportStatus.ONLINE)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()
        override val incoming: Flow<IncomingEnvelope> = emptyFlow()
        var sends = 0
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun reconnect() = Unit
        override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean): SendResult {
            sends++
            return result
        }
    }

    private fun paths(name: String) = DesktopPaths(tmp.newFolder("$name-config").toPath(), tmp.newFolder("$name-data").toPath())

    private fun open(paths: DesktopPaths) = DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile))

    private fun sentWire(
        origin: String = "primary",
        target: String = peer,
        id: String = PayloadCodec.newId(),
        body: String = "hi",
        ts: Long = 1_700_000_000_000,
        sid: String = SyncCodec.newSyncId(),
    ): String = SyncCodec.encode(
        SyncBody.Sent(origin, sid, 1, target, b64(PayloadCodec.encode(RelayPayload(id, ts, body)))),
    )

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun applier(store: SyncStore, http: SyncHttp, known: (String) -> Boolean = { true }, open: (String) -> String? = { null }) =
        SentSyncApplier(deviceId, me, http, store, openBody = open, knownConversation = known)

    private fun decodeSent(event: SyncEventDto): SyncBody.Sent {
        val wire = String(Base64.getDecoder().decode(event.dataBase64), Charsets.UTF_8)
        return (SyncCodec.decode(wire) as SyncResult.Ok).body as SyncBody.Sent
    }

    // ---- publish ------------------------------------------------------------------------------

    @Test
    fun `publish happy path posts one SENT with the same payload id and the exact payload`() = runBlocking {
        val store = open(paths("pub"))
        val server = FakeServer()
        val publisher = SentSyncPublisher(deviceId, store, server)
        val payload = RelayPayload("11111111-2222-3333-4444-555555555555", 1_700_000_000_123, "hello | world é")
        val row = DesktopMessage(payload.id, peer, me, payload.body, payload.ts, outgoing = true)

        assertTrue(publisher.recordSent(row, peer, payload))
        assertEquals(1, store.messages(peer).size) // the local row is committed before any network
        assertEquals(1, store.outbox(10).size)

        val report = publisher.flush()
        assertEquals(1, report.posted)
        assertEquals(0, report.remaining)
        assertNull(report.error)
        assertEquals(1, server.events.size)
        val sent = decodeSent(server.events.single())
        assertEquals(deviceId, sent.origin)
        assertEquals(peer, sent.target)
        val decoded = PayloadCodec.decode(String(Base64.getDecoder().decode(sent.payloadBase64), Charsets.UTF_8))
        assertEquals(payload, decoded)
        store.close()
    }

    @Test
    fun `a failed publish is retried after restart and the send was never affected`() = runBlocking {
        val p = paths("retry")
        val server = FakeServer().apply { failPosts = true }
        val payload = RelayPayload(PayloadCodec.newId(), 5, "retry me")
        open(p).use { store ->
            val publisher = SentSyncPublisher(deviceId, store, server)
            publisher.recordSent(DesktopMessage(payload.id, peer, me, "retry me", 5, true), peer, payload)
            val report = publisher.flush()
            assertEquals(0, report.posted)
            assertEquals(1, report.remaining)
            assertEquals("unreachable", report.error)
            assertEquals(1, store.outbox(10).single().attempts)
            assertEquals(1, store.messages(peer).size)
        }
        // "Restart": a fresh store object over the same files, a fresh publisher, a healthy server.
        server.failPosts = false
        open(p).use { store ->
            assertEquals(1, store.outbox(10).size)
            val report = SentSyncPublisher(deviceId, store, server).flush()
            assertEquals(1, report.posted)
            assertEquals(0, store.outbox(10).size)
            assertEquals(1, server.events.size)
            assertEquals(payload.id, PayloadCodec.decode(String(Base64.getDecoder().decode(decodeSent(server.events.single()).payloadBase64)))!!.id)
        }
    }

    @Test
    fun `a retry after a lost reply is a server duplicate, not a second event`() = runBlocking {
        val store = open(paths("dup"))
        val server = FakeServer()
        val publisher = SentSyncPublisher(deviceId, store, server)
        val payload = RelayPayload(PayloadCodec.newId(), 5, "once")
        publisher.recordSent(DesktopMessage(payload.id, peer, me, "once", 5, true), peer, payload)
        val wire = store.outbox(1).single().wire
        server.post(SyncWire.envelope(wire, 1, System.currentTimeMillis())) // reply "lost": outbox untouched
        val report = publisher.flush()
        assertEquals(1, report.duplicates)
        assertEquals(1, server.events.size)
        assertEquals(0, store.outbox(10).size)
        store.close()
    }

    @Test
    fun `contact send failure publishes nothing and stores nothing`() = runBlocking {
        val p = paths("fail")
        val secrets = FileSecretStore(p.secretFile)
        val store = DesktopMessageStore(p.databaseFile, secrets)
        val server = FakeServer()
        val transport = FakeTransport(SendResult.Failed("recipient not on mailbox", retryable = false))
        val engine = DesktopEngine(
            DesktopIdentity(p, secrets), DesktopKeyMaterial(p, secrets), store, transport,
            syncPublisher = SentSyncPublisher(deviceId, store, server),
            syncApplier = applier(store, server),
        )
        val result = engine.sendText(peer, "never delivered")
        assertTrue(result is SendResult.Failed)
        assertEquals(1, transport.sends)
        assertEquals(0, store.outbox(10).size)
        assertEquals(0, server.postCalls)
        assertEquals(0, store.messages(peer).size)
        engine.close()
        store.close()
    }

    @Test
    fun `engine send succeeds and queues a copy even while the sync server is down`() = runBlocking {
        val p = paths("down")
        val secrets = FileSecretStore(p.secretFile)
        val store = DesktopMessageStore(p.databaseFile, secrets)
        val server = FakeServer().apply { failPosts = true; failList = true }
        val engine = DesktopEngine(
            DesktopIdentity(p, secrets), DesktopKeyMaterial(p, secrets), store, FakeTransport(),
            syncPublisher = SentSyncPublisher(deviceId, store, server),
            syncApplier = applier(store, server),
        )
        val result = engine.sendText(peer, "still sent")
        assertTrue(result is SendResult.Sent)
        assertEquals(1, store.messages(peer).size)
        assertEquals(1, store.outbox(10).size) // waiting for the retry
        val payloadOnWire = decodeQueued(store.outbox(1).single().wire)
        assertEquals("still sent", payloadOnWire.body) // the copy carries the plain body the phone can show
        assertEquals(store.messages(peer).single().id, payloadOnWire.id)
        engine.close()
        store.close()
    }

    private fun decodeQueued(wire: String): RelayPayload {
        val sent = (SyncCodec.decode(wire) as SyncResult.Ok).body as SyncBody.Sent
        return PayloadCodec.decode(String(Base64.getDecoder().decode(sent.payloadBase64), Charsets.UTF_8))!!
    }

    @Test
    fun `with a sealer the server copy never holds the plaintext and the receiver opens it`() = runBlocking {
        val sender = open(paths("s1"))
        val receiver = open(paths("s2"))
        val server = FakeServer()
        val seal: (String) -> String? = { "TYPE:ENC|CT:" + Base64.getEncoder().encodeToString(it.reversed().toByteArray()) }
        val unseal: (String) -> String? = { String(Base64.getDecoder().decode(it.removePrefix("TYPE:ENC|CT:"))).reversed() }
        val publisher = SentSyncPublisher(deviceId, sender, server, sealToSelf = seal)
        val payload = RelayPayload(PayloadCodec.newId(), 3, "very secret words")
        publisher.recordSent(DesktopMessage(payload.id, peer, me, payload.body, 3, true), peer, payload)
        publisher.flush()
        val raw = String(Base64.getDecoder().decode(server.events.single().dataBase64))
        val inner = decodeSent(server.events.single()).payloadBase64
        assertFalse(raw.contains("secret"))
        assertFalse(String(Base64.getDecoder().decode(inner)).contains("secret"))
        SentSyncApplier("primary", me, server, receiver, openBody = unseal).runOnce()
        assertEquals("very secret words", receiver.messages(peer).single().body)
        assertEquals(payload.id, receiver.messages(peer).single().id)
        sender.close(); receiver.close()
    }

    @Test
    fun `a sealer that fails publishes no copy and never falls back to plaintext`() = runBlocking {
        val store = open(paths("s3"))
        val server = FakeServer()
        val publisher = SentSyncPublisher(deviceId, store, server, sealToSelf = { null })
        val payload = RelayPayload(PayloadCodec.newId(), 3, "plain")
        assertTrue(publisher.recordSent(DesktopMessage(payload.id, peer, me, "plain", 3, true), peer, payload))
        assertEquals(1, publisher.encodeFailures)
        assertEquals(0, store.outbox(10).size)
        assertEquals(1, store.messages(peer).size) // the user's own row is unaffected
        assertEquals(0, server.postCalls)
        store.close()
    }

    @Test
    fun `the sync id is deterministic per payload so a re-stage names the same event`() {
        val a = SentSyncPublisher(deviceId, open(paths("d1")), FakeServer())
        val payload = RelayPayload("fixed-id", 1, "x")
        assertEquals(a.buildWires(peer, payload), a.buildWires(peer, payload))
    }

    @Test
    fun `a duplicate payload id queues no second copy`() {
        val store = open(paths("dupq"))
        val m = DesktopMessage("same-id", peer, me, "x", 1, true)
        assertTrue(store.enqueueSent(m, listOf("w1"), 1))
        assertFalse(store.enqueueSent(m, listOf("w2"), 2))
        assertEquals(1, store.outbox(10).size)
        store.close()
    }

    @Test
    fun `an unencodable copy is counted and the row is still stored`() {
        val store = open(paths("big"))
        val publisher = SentSyncPublisher(deviceId, store, FakeServer())
        val payload = RelayPayload(PayloadCodec.newId(), 1, "x")
        // A target that is not a pubkey or group ref is outside the codec schema.
        assertTrue(publisher.recordSent(DesktopMessage(payload.id, "not-a-key", me, "x", 1, true), "not-a-key", payload))
        assertEquals(1, publisher.encodeFailures)
        assertEquals(1, store.messages("not-a-key").size)
        assertEquals(0, store.outbox(10).size)
        store.close()
    }

    @Test
    fun `group and media or file references travel untouched`() = runBlocking {
        val sender = open(paths("g1"))
        val receiver = open(paths("g2"))
        val server = FakeServer()
        val group = "group:${"c".repeat(64)}:${java.util.UUID.randomUUID()}"
        val media = "TYPE:MEDIA|ID:abc123|K:a2V5LWJ5dGVz|N:photo | one.jpg|S:2048|M:image/jpeg"
        val file = "TYPE:FILE|ID:def456|K:a2V5LWJ5dGVz|N:report.pdf|S:99999"
        val publisher = SentSyncPublisher(deviceId, sender, server)
        val items = listOf(group to media, peer to file)
        for ((target, body) in items) {
            val payload = RelayPayload(PayloadCodec.newId(), 10, body)
            publisher.recordSent(DesktopMessage(payload.id, target, me, body, 10, true), target, payload)
        }
        assertEquals(2, publisher.flush().posted)

        val report = SentSyncApplier("primary", me, server, receiver).runOnce()
        assertEquals(2, report.inserted)
        assertEquals(media, receiver.messages(group).single().body)
        assertTrue(receiver.messages(group).single().outgoing)
        assertEquals(file, receiver.messages(peer).single().body)
        sender.close(); receiver.close()
    }

    @Test
    fun `an oversize body is chunked within the bound, and parts apply out of order with duplicates`() = runBlocking {
        val sender = open(paths("c1"))
        val receiver = open(paths("c2"))
        val body = (0 until 15_000).joinToString("") { ('a' + it % 26).toString() }
        val publisher = SentSyncPublisher(deviceId, sender, FakeServer())
        val payload = RelayPayload(PayloadCodec.newId(), 77, body)
        val wires = publisher.buildWires(peer, payload)
        assertTrue("expected several parts, got ${wires.size}", wires.size >= 2)
        for (w in wires) assertTrue(w.toByteArray().size <= SyncCodec.MAX_RAW_BYTES)

        val server = FakeServer()
        // Part order 3,1,1(dup),2 and an unrelated event in between.
        val order = listOf(wires.size - 1) + listOf(0, 0) + (1 until wires.size - 1).toList()
        for ((n, i) in order.withIndex()) {
            server.injectWire(wires[i])
            if (n == 1) server.injectWire(sentWire(origin = "2".repeat(32), id = "other")) // another device's, inserted too
        }
        val applier = SentSyncApplier("primary", me, server, receiver)
        val first = applier.runOnce()
        assertEquals("first=$first", 2, first.inserted)
        val rows = receiver.messages(peer)
        assertEquals(body, rows.single { it.id == payload.id }.body)
        sender.close(); receiver.close()
    }

    // ---- apply --------------------------------------------------------------------------------

    @Test
    fun `apply inserts an outgoing row with the same id, commits the cursor and then acks`() = runBlocking {
        val store = open(paths("a1"))
        val server = FakeServer()
        server.injectWire(sentWire(id = "phone-msg-1", body = "from the phone", ts = 42))
        val report = applier(store, server).runOnce()
        assertEquals(1, report.inserted)
        val row = store.messages(peer).single()
        assertEquals("phone-msg-1", row.id)
        assertTrue(row.outgoing)
        assertEquals(me, row.sender)
        assertEquals("from the phone", row.body)
        assertEquals(42L, row.ts)
        assertEquals(1L, store.syncCursor())
        assertEquals(listOf(1L), server.acks)
        assertTrue(report.acked)
        store.close()
    }

    @Test
    fun `applying twice yields one row`() = runBlocking {
        val store = open(paths("a2"))
        val server = FakeServer()
        server.injectWire(sentWire(id = "dup-id"))
        applier(store, server).runOnce()
        // A crash lost the cursor after the row committed: the same event is handed out again.
        val amnesic = object : SyncStore by store {
            override fun syncCursor(): Long = 0L
        }
        val second = applier(amnesic, server).runOnce()
        assertEquals(1, store.messages(peer).size)
        assertEquals(0, second.inserted)
        assertEquals(1, second.merged)
        store.close()
    }

    @Test
    fun `a SENT whose id already exists as a received row is merged, never duplicated`() = runBlocking {
        val store = open(paths("a3"))
        store.insertMessage(DesktopMessage("shared-id", peer, peer, "original", 1, outgoing = false))
        val server = FakeServer()
        server.injectWire(sentWire(id = "shared-id", body = "copy"))
        val report = applier(store, server).runOnce()
        assertEquals(1, report.merged)
        assertEquals(0, report.inserted)
        val rows = store.messages(peer)
        assertEquals(1, rows.size)
        assertEquals("original", rows.single().body)
        assertEquals(1L, store.syncCursor())
        store.close()
    }

    @Test
    fun `own-origin events are ignored but the cursor passes them`() = runBlocking {
        val store = open(paths("a4"))
        val server = FakeServer()
        server.injectWire(sentWire(origin = deviceId, id = "mine"))
        val report = applier(store, server).runOnce()
        assertEquals(1, report.ownOrigin)
        assertEquals(0, store.countMessages())
        assertEquals(1L, store.syncCursor())
        store.close()
    }

    @Test
    fun `an unknown contact gets a placeholder conversation, no contact row`() = runBlocking {
        val store = open(paths("a5"))
        val server = FakeServer()
        server.injectWire(sentWire(id = "stranger-msg"))
        val report = applier(store, server, known = { store.conversation(it) != null }).runOnce()
        assertEquals(1, report.unknownContact)
        assertEquals(1, report.inserted)
        assertNotNull(store.conversation(peer))
        assertEquals(0L, store.contactCount())
        store.close()
    }

    @Test
    fun `malformed, unknown-version and oversize events are skipped and later good events still apply`() = runBlocking {
        val store = open(paths("a6"))
        val server = FakeServer()
        server.inject("not a sync body".toByteArray())                                              // bad prefix
        server.injectWire(sentWire().replace("|V:1|", "|V:2|"))                                      // unknown version
        server.inject(ByteArray(0), dataBase64 = "A".repeat(20_000), total = 15_000, hash = "0".repeat(64)) // oversize
        server.inject("TYPE:SYNC|SENT|V:1|".toByteArray(), hash = "f".repeat(64))                     // hash mismatch
        server.inject(byteArrayOf(1, 2, 3), dataBase64 = "%%%not-base64")                            // undecodable
        server.injectWire(sentWire(id = "good-after-bad", body = "ok"))
        val report = applier(store, server).runOnce()
        assertEquals(5, report.malformed)
        assertEquals(1, report.inserted)
        assertEquals("good-after-bad", store.messages(peer).single().id)
        assertEquals(6L, store.syncCursor())
        store.close()
    }

    @Test
    fun `a malformed event alone lets the cursor pass, a good event before it is applied first`() = runBlocking {
        val store = open(paths("a7"))
        val server = FakeServer()
        server.injectWire(sentWire(id = "first-good"))
        server.inject("garbage".toByteArray())
        val report = applier(store, server).runOnce()
        assertEquals(1, report.inserted)
        assertEquals(1, report.malformed)
        assertEquals(2L, store.syncCursor())
        store.close()
    }

    @Test
    fun `an incomplete multi-part event holds the cursor before its first part until it completes`() = runBlocking {
        val sender = open(paths("p1"))
        val receiver = open(paths("p2"))
        val payload = RelayPayload(PayloadCodec.newId(), 9, "z".repeat(15_000))
        val wires = SentSyncPublisher("primary", sender, FakeServer()).buildWires(peer, payload)
        val server = FakeServer()
        server.injectWire(sentWire(id = "before"))             // cursor 1
        server.injectWire(wires[1])                            // cursor 2 (part 2 of n first)
        server.injectWire(sentWire(id = "after", body = "b"))  // cursor 3
        val a = applier(receiver, server)
        val first = a.runOnce()
        assertEquals("first=$first", 2, first.inserted)
        assertEquals("first=$first", 1, first.pendingParts)
        assertEquals(1L, receiver.syncCursor())                // held just before the first part
        for (i in wires.indices) if (i != 1) server.injectWire(wires[i])
        val second = a.runOnce()
        assertEquals("second=$second", 1, second.inserted)
        assertEquals(0, second.pendingParts)
        assertEquals(server.events.last().cursor, receiver.syncCursor())
        assertEquals(3, receiver.messages(peer).size)
        sender.close(); receiver.close()
    }

    @Test
    fun `a chunked own-origin event is ignored and releases the cursor`() = runBlocking {
        val sender = open(paths("o1"))
        val receiver = open(paths("o2"))
        val payload = RelayPayload(PayloadCodec.newId(), 9, "q".repeat(15_000))
        val wires = SentSyncPublisher(deviceId, sender, FakeServer()).buildWires(peer, payload)
        val server = FakeServer()
        wires.forEach { server.injectWire(it) }
        val report = applier(receiver, server).runOnce()   // applier's own device id == the publisher's
        assertEquals(0, report.inserted)
        assertEquals(1, report.ownOrigin)
        assertEquals(0, report.pendingParts)
        assertEquals(wires.size.toLong(), receiver.syncCursor())
        sender.close(); receiver.close()
    }

    @Test
    fun `a commit failure leaves the cursor and the ack unmoved and the event is applied next time`() = runBlocking {
        val real = open(paths("a8"))
        val failing = object : SyncStore by real {
            var fail = true
            override fun commitSync(message: DesktopMessage?, cursor: Long): SyncCommit {
                if (fail) throw java.sql.SQLException("disk full")
                return real.commitSync(message, cursor)
            }
        }
        val server = FakeServer()
        server.injectWire(sentWire(id = "needs-commit"))
        val a = applier(failing, server)
        val first = a.runOnce()
        assertEquals("commit failed", first.error)
        assertEquals(0L, real.syncCursor())
        assertTrue(server.acks.isEmpty())
        assertEquals(0, real.messages(peer).size)
        failing.fail = false
        val second = a.runOnce()
        assertNull(second.error)
        assertEquals(1, real.messages(peer).size)
        assertEquals(1L, real.syncCursor())
        assertEquals(listOf(1L), server.acks)
        real.close()
    }

    @Test
    fun `the cursor survives a restart in the encrypted store`() = runBlocking {
        val p = paths("a9")
        val server = FakeServer()
        server.injectWire(sentWire(id = "one"))
        open(p).use { applier(it, server).runOnce() }
        open(p).use { store ->
            assertEquals(1L, store.syncCursor())
            server.injectWire(sentWire(id = "two"))
            val report = applier(store, server).runOnce() // fetches only after 1
            assertEquals(1, report.fetched)
            assertEquals(2, store.messages(peer).size)
        }
    }

    @Test
    fun `a list failure moves nothing`() = runBlocking {
        val store = open(paths("a10"))
        val server = FakeServer().apply { failList = true }
        val report = applier(store, server).runOnce()
        assertEquals("unreachable", report.error)
        assertEquals(0L, store.syncCursor())
        assertTrue(server.acks.isEmpty())
        store.close()
    }

    @Test
    fun `an encrypted body that is not for us is counted and skipped`() = runBlocking {
        val store = open(paths("a11"))
        val server = FakeServer()
        server.injectWire(sentWire(id = "enc", body = "TYPE:ENC|CT:AAAA"))
        val report = applier(store, server).runOnce()
        assertEquals(1, report.unreadable)
        assertEquals(0, store.countMessages())
        assertEquals(1L, store.syncCursor())
        store.close()
    }

    @Test
    fun `server envelope shape matches the Worker bounds`() {
        val wire = sentWire()
        val env = JSONObject(SyncWire.envelope(wire, 1, 1_000))
        assertEquals(32, env.getString("id").length)
        assertEquals(env.getString("id"), JSONObject(SyncWire.envelope(wire, 1, 5_000)).getString("id")) // deterministic
        assertEquals(1, env.getInt("parts"))
        assertEquals(0, env.getInt("part"))
        assertTrue(env.getLong("total") <= 12 * 1024)
        assertEquals(1_000L + 24L * 60 * 60 * 1000, env.getLong("expires"))
    }
}
