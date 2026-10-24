package com.relay.app.sync

import com.relay.app.linking.SyncBody
import com.relay.app.linking.SyncCodec
import com.relay.app.linking.SyncResult
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

private const val CONTACT = "aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11aa11"
private const val OWNER = "bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22bb22"
private const val GROUP_ID = "44444444-4444-4444-8444-444444444444"
private const val GROUP_TARGET = "group:$OWNER:$GROUP_ID"
private const val PAYLOAD_ID = "11111111-1111-4111-8111-111111111111"
private const val DESKTOP = "0123456789abcdef0123456789abcdef"

private class FakeHint(var value: Boolean = true) : LinkedHint {
    override fun get() = value
    override fun set(value: Boolean) { this.value = value }
}

private class FakeOutbox : SyncOutbox {
    val rows = LinkedHashMap<String, SyncOutboxItem>()
    override fun enqueue(item: SyncOutboxItem): Boolean = rows.putIfAbsent(item.payloadId, item) == null
    override fun confirm(payloadId: String) { rows[payloadId]?.let { rows[payloadId] = it.copy(confirmed = true) } }
    override fun discard(payloadId: String) { rows.remove(payloadId) }
    override fun due(now: Long, limit: Int) = rows.values.filter { it.confirmed && it.nextAttemptAt <= now }.take(limit)
    override fun recordAttempt(payloadId: String, attempts: Int, nextAttemptAt: Long) {
        rows[payloadId]?.let { rows[payloadId] = it.copy(attempts = attempts, nextAttemptAt = nextAttemptAt) }
    }
    override fun remove(payloadId: String) { rows.remove(payloadId) }
    override fun purgeStale(olderThan: Long): Int {
        val stale = rows.values.filter { !it.confirmed && it.createdAt < olderThan }.map { it.payloadId }
        stale.forEach { rows.remove(it) }
        return stale.size
    }
    override fun clear() = rows.clear()
}

/** A server stand-in: posts become events with rising cursors, exactly one event per posted envelope. */
private class FakeNet : SyncNetwork {
    var devices: DevicesResult = DevicesResult.Ok(1)
    val postResults = ArrayDeque<PostResult>()
    val posted = mutableListOf<String>()
    val events = mutableListOf<SyncEvent>()
    var calls = 0
    var listCalls = 0
    val acks = mutableListOf<Long>()
    val log = mutableListOf<String>()
    var listFails = false

    override suspend fun listDevices(): DevicesResult { calls++; log += "devices"; return devices }
    override suspend fun post(envelopeJson: String): PostResult {
        calls++
        val result = postResults.removeFirstOrNull() ?: PostResult.Ok
        if (result == PostResult.Ok) { posted += envelopeJson; events += toEvent(envelopeJson, events.size + 1L) }
        return result
    }
    override suspend fun list(after: Long, limit: Int): ListResult {
        calls++; listCalls++
        if (listFails) return ListResult.Failed
        val page = events.filter { it.cursor > after }.take(limit)
        return ListResult.Ok(page, events.count { it.cursor > after } > page.size)
    }
    override suspend fun ack(through: Long): Boolean { calls++; acks += through; log += "ack:$through"; return true }

    fun addRaw(bytes: ByteArray, id: String = "e${events.size}"): Long {
        val cursor = events.size + 1L
        events += SyncEvent(cursor, id, 1, bytes.size, SentSyncWire.sha256Hex(bytes), bytes)
        return cursor
    }

    companion object {
        fun toEvent(json: String, cursor: Long): SyncEvent {
            val o = JSONObject(json)
            val data = Base64.getDecoder().decode(o.getString("data"))
            return SyncEvent(cursor, o.getString("id"), o.getLong("rev"), o.getInt("total"), o.getString("hash"), data)
        }
    }
}

private class FakeCursor(var value: Long = 0, val log: MutableList<String>? = null) : SyncCursorStore {
    override fun get() = value
    override fun set(value: Long) { this.value = value; log?.add("cursor:$value") }
}

private class Row(val thread: ThreadRef, val payloadId: String, val plain: String, val ts: Long, var status: Int = 0)

private class FakeStore(val log: MutableList<String>? = null) : SentCopyStore {
    val contacts = mutableMapOf(CONTACT to ThreadRef(1, false))
    val groups = mutableMapOf((OWNER to GROUP_ID) to ThreadRef(7, true))
    var rows = mutableListOf<Row>()
    var failInsert = false
    var unreadable = false

    override fun findDirect(contactPubkey: String) = contacts[contactPubkey]
    override fun findGroup(ownerPubkey: String, sharedId: String) = groups[ownerPubkey to sharedId]
    override fun hasOutgoing(thread: ThreadRef, payloadId: String) = rows.any { it.thread == thread && it.payloadId == payloadId }
    override fun open(body: String): String? = if (unreadable) null else body.removePrefix("SEALED:")
    override fun insertOutgoing(thread: ThreadRef, payload: RelayPayload, plainBody: String): Boolean {
        if (failInsert) throw IllegalStateException("disk full")
        rows += Row(thread, payload.id, plainBody, payload.ts)
        log?.add("insert")
        return true
    }
    override fun mergeOutgoing(thread: ThreadRef, payloadId: String) {
        rows.filter { it.thread == thread && it.payloadId == payloadId && it.status == 0 }.forEach { it.status = 2 }
    }
    override fun <T> inTransaction(block: () -> T): T {
        val snapshot = rows.map { Row(it.thread, it.payloadId, it.plain, it.ts, it.status) }.toMutableList()
        return try { block() } catch (e: Throwable) { rows = snapshot; throw e }
    }
}

class SentSyncTest {

    private var now = 1_000_000L
    private val clock: () -> Long = { now }

    private fun payloadJson(id: String = PAYLOAD_ID, body: String = "SEALED:hello", ts: Long = 1234L) =
        PayloadCodec.encode(RelayPayload(id = id, ts = ts, body = body))

    private fun sentWire(origin: String, target: String = CONTACT, json: String = payloadJson(), sid: String = SentSyncWire.syncIdFor(PAYLOAD_ID)): String =
        SyncCodec.encode(SyncBody.Sent(origin, sid, 1, target, Base64.getEncoder().encodeToString(json.toByteArray())))

    private fun publisher(net: FakeNet, outbox: FakeOutbox, hint: FakeHint = FakeHint(), ttl: Long = 60_000L) =
        SentSyncPublisher(net, outbox, hint, clock, deviceTtlMs = ttl)

    private fun applier(net: FakeNet, store: SentCopyStore, cursor: FakeCursor, hint: FakeHint = FakeHint()) =
        SentSyncApplier(net, store, cursor, hint, clock)

    // ---- publisher ------------------------------------------------------------------------------

    @Test fun publishHappyPathCarriesTheSamePayloadIdTargetAndPayloadJson() = runBlocking {
        val net = FakeNet(); val outbox = FakeOutbox(); val pub = publisher(net, outbox)
        val json = payloadJson()
        assertTrue(pub.stage(PAYLOAD_ID, CONTACT, json))
        assertEquals("unconfirmed rows are not published", 0, pub.flush().posted)
        pub.confirm(PAYLOAD_ID)
        val report = pub.flush()
        assertEquals(1, report.posted)
        assertTrue(outbox.rows.isEmpty())

        val env = JSONObject(net.posted.single())
        assertEquals(1, env.getInt("v")); assertEquals(0, env.getInt("part")); assertEquals(1, env.getInt("parts"))
        assertTrue(env.getString("id").matches(Regex("[0-9a-f]{8,64}")))
        val wire = String(Base64.getDecoder().decode(env.getString("data")))
        assertEquals(wire.toByteArray().size, env.getInt("total"))
        val sent = (SyncCodec.decode(wire) as SyncResult.Ok).body as SyncBody.Sent
        assertEquals(CONTACT, sent.target)
        assertEquals("primary", sent.origin)
        assertEquals(json, String(Base64.getDecoder().decode(sent.payloadBase64)))
        assertEquals(PAYLOAD_ID, PayloadCodec.decode(json)!!.id)
    }

    @Test fun unlinkedUserMakesNoNetworkCallAndWritesNoRow() = runBlocking {
        val net = FakeNet(); val outbox = FakeOutbox(); val hint = FakeHint(false)
        val pub = publisher(net, outbox, hint)
        assertFalse(pub.stage(PAYLOAD_ID, CONTACT, payloadJson()))
        pub.confirm(PAYLOAD_ID); pub.discard(PAYLOAD_ID)
        assertEquals(FlushReport(), pub.flush())
        assertEquals(PollReport(), applier(net, FakeStore(), FakeCursor(), hint).poll())
        assertEquals(0, net.calls)
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun staleHintWithNoLinkedDeviceStopsMirroringAndClearsRows() = runBlocking {
        val net = FakeNet().apply { devices = DevicesResult.Ok(0) }
        val outbox = FakeOutbox(); val hint = FakeHint(true); val pub = publisher(net, outbox, hint)
        pub.stage(PAYLOAD_ID, CONTACT, payloadJson()); pub.confirm(PAYLOAD_ID)
        val report = pub.flush()
        assertTrue(report.skippedNoLinkedDevice)
        assertTrue(net.posted.isEmpty())
        assertTrue(outbox.rows.isEmpty())
        assertFalse(hint.value)
        net.calls = 0
        pub.flush()
        assertEquals("after the hint is cleared there are no further requests", 0, net.calls)
    }

    @Test fun deviceListIsCachedForTheTtl() = runBlocking {
        val net = FakeNet(); val outbox = FakeOutbox(); val pub = publisher(net, outbox, ttl = 60_000)
        for (i in 1..3) {
            val id = "00000000-0000-4000-8000-00000000000$i"
            pub.stage(id, CONTACT, payloadJson(id)); pub.confirm(id); pub.flush()
        }
        assertEquals(1, net.log.count { it == "devices" })
        now += 61_000
        val id = "00000000-0000-4000-8000-000000000009"
        pub.stage(id, CONTACT, payloadJson(id)); pub.confirm(id); pub.flush()
        assertEquals(2, net.log.count { it == "devices" })
    }

    @Test fun failedPublishIsRetriedWithTheAppBackoffAndNeverDropsTheRow() = runBlocking {
        val net = FakeNet().apply { postResults += PostResult.Retry("unreachable") }
        val outbox = FakeOutbox(); val pub = publisher(net, outbox)
        pub.stage(PAYLOAD_ID, CONTACT, payloadJson()); pub.confirm(PAYLOAD_ID)
        val first = pub.flush()
        assertEquals(1, first.retried)
        val row = outbox.rows.getValue(PAYLOAD_ID)
        assertEquals(1, row.attempts)
        assertEquals(now + 5_000L, row.nextAttemptAt) // OutboxPolicy.nextDelayMs(1)
        assertEquals("not due yet: no second post", 0, pub.flush().posted)
        now += 5_000
        assertEquals(1, pub.flush().posted)
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun permanentRefusalDropsOnlyThatItem() = runBlocking {
        val net = FakeNet().apply { postResults += PostResult.Permanent("http 400") }
        val outbox = FakeOutbox(); val pub = publisher(net, outbox)
        val other = "22222222-2222-4222-8222-222222222222"
        pub.stage(PAYLOAD_ID, CONTACT, payloadJson()); pub.confirm(PAYLOAD_ID)
        now += 1
        pub.stage(other, CONTACT, payloadJson(other)); pub.confirm(other)
        val report = pub.flush()
        assertEquals(1, report.dropped); assertEquals(1, report.items)
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun failedSendPublishesNothing() = runBlocking {
        val net = FakeNet(); val outbox = FakeOutbox(); val pub = publisher(net, outbox)
        pub.stage(PAYLOAD_ID, CONTACT, payloadJson())
        pub.discard(PAYLOAD_ID) // OutboxWorker.fail()
        assertEquals(0, pub.flush().posted)
        assertTrue(net.posted.isEmpty())
        // A send that never got confirmed is never published either, and is purged after the give-up window.
        pub.stage("33333333-3333-4333-8333-333333333333", CONTACT, payloadJson("33333333-3333-4333-8333-333333333333"))
        assertEquals(0, pub.flush().posted)
        now += 8L * 24 * 3600 * 1000
        pub.flush()
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun mediaAndFileReferencesAreCarriedUntouched() = runBlocking {
        val ref = "TYPE:MEDIA|URL:https://relay.example/v1/media/abc|K:AAAA|CAP:hi there"
        assertTrue(SentSyncWire.mirrored(ref))
        assertTrue(SentSyncWire.mirrored("hello"))
        assertTrue(SentSyncWire.mirrored("TYPE:REPLY|ID:x|hello"))
        for (control in listOf("TYPE:PUBKEY|k", "TYPE:READ_RECEIPT|1", "TYPE:LOCATION|1,2", "TYPE:PROFILE_PHOTO|KIND:TYPING|x", "TYPE:PAIR_REQUEST|n")) {
            assertFalse(control, SentSyncWire.mirrored(control))
        }
        var sealedInput: String? = null
        val json = SentSyncWire.selfSealedPayload(PAYLOAD_ID, 99L, ref) { sealedInput = it; "SEALED:$it" }!!
        assertEquals(ref, sealedInput) // the reference reaches the inner layer byte for byte
        val payload = PayloadCodec.decode(json)!!
        assertEquals(PAYLOAD_ID, payload.id); assertEquals(99L, payload.ts)
        assertEquals("SEALED:$ref", payload.body)
        assertNull(SentSyncWire.selfSealedPayload(PAYLOAD_ID, 1L, ref) { null })

        // ...and the whole path: publish, then a second device applies it and recovers the reference.
        val net = FakeNet(); val outbox = FakeOutbox(); val pub = publisher(net, outbox)
        pub.stage(PAYLOAD_ID, CONTACT, json); pub.confirm(PAYLOAD_ID); pub.flush()
        val store = FakeStore()
        applier(net, store, FakeCursor(), FakeHint()).poll().also { assertEquals(1, it.ignoredOwn) }
        val desktopView = SentSyncApplier(net, store, FakeCursor(), FakeHint(), clock, origin = DESKTOP)
        assertEquals(1, desktopView.poll().applied)
        assertEquals(ref, store.rows.single().plain)
    }

    // ---- apply ----------------------------------------------------------------------------------

    private fun netWith(vararg wires: String) = FakeNet().also { n -> wires.forEach { n.addRaw(it.toByteArray()) } }

    @Test fun applyInsertsAnOutgoingMessageWithTheSamePayloadId() = runBlocking {
        val net = netWith(sentWire(DESKTOP))
        val store = FakeStore(); val cursor = FakeCursor()
        val report = applier(net, store, cursor).poll()
        assertEquals(1, report.applied)
        val row = store.rows.single()
        assertEquals(PAYLOAD_ID, row.payloadId); assertEquals(1234L, row.ts); assertEquals("hello", row.plain)
        assertEquals(ThreadRef(1, false), row.thread)
        assertEquals(1L, cursor.value); assertEquals(listOf(1L), net.acks)
    }

    @Test fun applyingTheSameEventTwiceNeverDuplicates() = runBlocking {
        val net = netWith(sentWire(DESKTOP))
        val store = FakeStore(); val cursor = FakeCursor()
        applier(net, store, cursor).poll()
        cursor.value = 0 // e.g. the ack was lost and the cursor rolled back
        val second = applier(net, store, cursor).poll()
        assertEquals(1, store.rows.size)
        assertEquals(1, second.merged); assertEquals(0, second.applied)
        assertEquals("status lines up after a merge", 2, store.rows.single().status)
    }

    @Test fun anAlreadyPresentLocalMessageIsMergedNotDuplicated() = runBlocking {
        val store = FakeStore(); store.rows += Row(ThreadRef(1, false), PAYLOAD_ID, "hello", 1234L, status = 0)
        val report = applier(netWith(sentWire(DESKTOP)), store, FakeCursor()).poll()
        assertEquals(1, report.merged)
        assertEquals(1, store.rows.size)
    }

    @Test fun ownOriginIsIgnoredButTheCursorStillMoves() = runBlocking {
        val net = netWith(sentWire("primary")); val store = FakeStore(); val cursor = FakeCursor()
        val report = applier(net, store, cursor).poll()
        assertEquals(1, report.ignoredOwn)
        assertTrue(store.rows.isEmpty()); assertEquals(1L, cursor.value)
    }

    @Test fun unknownContactIsDroppedWithoutCreatingAnythingAndTheCursorMoves() = runBlocking {
        val stranger = "cc33".repeat(16)
        val net = netWith(sentWire(DESKTOP, target = stranger)); val store = FakeStore(); val cursor = FakeCursor()
        val report = applier(net, store, cursor).poll()
        assertEquals(1, report.unknownTarget)
        assertTrue(store.rows.isEmpty()); assertTrue(store.contacts.size == 1)
        assertEquals(1L, cursor.value)
    }

    @Test fun malformedUnknownVersionAndOversizeEventsAreSkipped() = runBlocking {
        val net = FakeNet()
        net.addRaw("not a sync body".toByteArray())
        net.addRaw(sentWire(DESKTOP).replace("|V:1|", "|V:9|").toByteArray())
        net.addRaw(ByteArray(SentSyncWire.MAX_EVENT_BYTES + 1) { 'a'.code.toByte() })
        // hash does not match the bytes
        val good = sentWire(DESKTOP).toByteArray()
        net.events += SyncEvent(4, "x", 1, good.size, "00".repeat(32), good)
        net.addRaw("TYPE:SYNC|SENT|V:1|SID:abc|R:1|O:$DESKTOP|TO:zz|P:AAAA".toByteArray())
        net.addRaw(sentWire(DESKTOP).toByteArray()) // a valid one after the junk
        val store = FakeStore(); val cursor = FakeCursor()
        val report = applier(net, store, cursor).poll()
        assertEquals(5, report.malformed)
        assertEquals(1, report.applied)
        assertEquals(6L, cursor.value)
        assertEquals(1, store.rows.size)
    }

    @Test fun otherSyncKindsAreSkippedNotApplied() = runBlocking {
        val read = SyncCodec.encode(SyncBody.Read(DESKTOP, "abcdef012345", 1, CONTACT, 5L))
        val net = netWith(read); val cursor = FakeCursor()
        assertEquals(1, applier(net, FakeStore(), cursor).poll().ignoredKind)
        assertEquals(1L, cursor.value)
    }

    @Test fun commitFailureLeavesTheCursorAndTheAckUnmoved() = runBlocking {
        val net = netWith(sentWire(DESKTOP)); val store = FakeStore().apply { failInsert = true }; val cursor = FakeCursor(0)
        val report = applier(net, store, cursor).poll()
        assertTrue(report.stoppedOnFailure)
        assertEquals(0L, cursor.value); assertTrue(net.acks.isEmpty()); assertTrue(store.rows.isEmpty())
        // The next poll, once the store works, applies it.
        store.failInsert = false
        assertEquals(1, applier(net, store, cursor).poll().applied)
        assertEquals(1L, cursor.value)
    }

    @Test fun aFailureMidPageKeepsEverythingBeforeItAndNothingAfter() = runBlocking {
        val second = "22222222-2222-4222-8222-222222222222"
        val net = netWith(sentWire(DESKTOP), sentWire(DESKTOP, json = payloadJson(second), sid = SentSyncWire.syncIdFor(second)))
        val store = object : SentCopyStore by FakeStore() {
            val inner = FakeStore()
            var inserts = 0
            override fun findDirect(contactPubkey: String) = inner.findDirect(contactPubkey)
            override fun hasOutgoing(thread: ThreadRef, payloadId: String) = inner.hasOutgoing(thread, payloadId)
            override fun open(body: String) = inner.open(body)
            override fun insertOutgoing(thread: ThreadRef, payload: RelayPayload, plainBody: String): Boolean {
                if (++inserts == 2) throw IllegalStateException("boom")
                return inner.insertOutgoing(thread, payload, plainBody)
            }
            override fun <T> inTransaction(block: () -> T): T = inner.inTransaction(block)
        }
        val cursor = FakeCursor()
        val report = applier(net, store, cursor).poll()
        assertTrue(report.stoppedOnFailure)
        assertEquals(1L, cursor.value); assertEquals(listOf(1L), net.acks)
    }

    @Test fun cursorIsPersistedAndAckedOnlyAfterTheMessageIsCommitted() = runBlocking {
        val net = netWith(sentWire(DESKTOP)); val log = mutableListOf<String>()
        net.log.clear()
        val store = FakeStore(log); val cursor = FakeCursor(0, log)
        // route the ack through the same log
        val logging = object : SyncNetwork by net {
            override suspend fun ack(through: Long): Boolean { log += "ack:$through"; return net.ack(through) }
        }
        SentSyncApplier(logging, store, cursor, FakeHint(), clock).poll()
        assertEquals(listOf("insert", "cursor:1", "ack:1"), log)
    }

    @Test fun groupSentLandsInTheGroupThread() = runBlocking {
        val groupJson = payloadJson(body = "SEALED:TYPE:PROFILE_PHOTO|KIND:GROUP|{}")
        val net = netWith(sentWire(DESKTOP, target = GROUP_TARGET, json = groupJson)); val store = FakeStore()
        assertEquals(1, applier(net, store, FakeCursor()).poll().applied)
        assertEquals(ThreadRef(7, true), store.rows.single().thread)
        // an unknown group is dropped like an unknown contact
        val net2 = netWith(sentWire(DESKTOP, target = "group:$OWNER:55555555-5555-4555-8555-555555555555", json = groupJson))
        assertEquals(1, applier(net2, FakeStore(), FakeCursor()).poll().unknownTarget)
    }

    @Test fun unreadableBodyIsCountedAndSkippedWithoutInsert() = runBlocking {
        val store = FakeStore().apply { unreadable = true }
        val report = applier(netWith(sentWire(DESKTOP)), store, FakeCursor()).poll()
        assertEquals(1, report.unreadable); assertTrue(store.rows.isEmpty())
    }

    @Test fun listFailureChangesNothing() = runBlocking {
        val net = netWith(sentWire(DESKTOP)).apply { listFails = true }; val cursor = FakeCursor()
        assertTrue(applier(net, FakeStore(), cursor).poll().fetchFailed)
        assertEquals(0L, cursor.value); assertTrue(net.acks.isEmpty())
    }

    // ---- chunked --------------------------------------------------------------------------------

    private fun bigPayload(): String =
        payloadJson(body = "SEALED:" + "x".repeat(15_000))

    @Test fun aChunkedEventIsPublishedAsSeveralServerEventsAndReassembles() = runBlocking {
        val net = FakeNet(); val outbox = FakeOutbox(); val pub = publisher(net, outbox)
        pub.stage(PAYLOAD_ID, CONTACT, bigPayload()); pub.confirm(PAYLOAD_ID)
        val report = pub.flush()
        assertTrue("expected several parts, got ${report.posted}", report.posted >= 2)
        val ids = net.posted.map { JSONObject(it).getString("id") }
        assertEquals("every part has its own server event id", ids.size, ids.toSet().size)
        for (p in net.posted) {
            val o = JSONObject(p)
            assertTrue(o.getInt("total") <= SentSyncWire.MAX_EVENT_BYTES)
            assertTrue(o.getString("data").length <= 16 * 1024)
        }
        val store = FakeStore(); val cursor = FakeCursor()
        val r = SentSyncApplier(net, store, cursor, FakeHint(), clock, origin = DESKTOP).poll()
        assertEquals(1, r.applied)
        assertEquals(15_000, store.rows.single().plain.length)
        assertEquals(net.events.size.toLong(), cursor.value)
    }

    @Test fun anIncompleteChunkedEventHoldsTheCursorBeforeItsFirstPart() = runBlocking {
        val full = FakeNet(); val pub = publisher(full, FakeOutbox())
        val outbox = FakeOutbox(); val pub2 = publisher(full, outbox)
        pub2.stage(PAYLOAD_ID, CONTACT, bigPayload()); pub2.confirm(PAYLOAD_ID); pub2.flush()
        assertTrue(full.events.size >= 2)
        // an unrelated finished event first, then only the first part of the chunked one
        val net = FakeNet()
        net.addRaw(sentWire("primary", json = payloadJson("66666666-6666-4666-8666-666666666666"), sid = "aaaaaaaa").toByteArray())
        val partial = full.events.first()
        net.events += SyncEvent(2, partial.id, partial.rev, partial.total, partial.hash, partial.data)
        val store = FakeStore(); val cursor = FakeCursor()
        val a = SentSyncApplier(net, store, cursor, FakeHint(), clock, origin = DESKTOP)
        val first = a.poll()
        assertEquals("cursor stays before the pending first part", 1L, cursor.value)
        assertEquals(first.toString(), 1, store.rows.size)
        // the remaining parts arrive
        full.events.drop(1).forEachIndexed { i, e -> net.events += SyncEvent(3L + i, e.id, e.rev, e.total, e.hash, e.data) }
        val r = a.poll()
        assertEquals(1, r.applied)
        assertEquals(2, store.rows.size)
        assertEquals(net.events.last().cursor, cursor.value)
    }

    @Test fun pollPagesThroughMoreThanOnePage() = runBlocking {
        val net = FakeNet()
        repeat(11) { i ->
            val id = "00000000-0000-4000-8000-0000000000%02d".format(i)
            net.addRaw(sentWire(DESKTOP, json = payloadJson(id), sid = SentSyncWire.syncIdFor(id)).toByteArray())
        }
        val store = FakeStore(); val cursor = FakeCursor()
        assertEquals(11, applier(net, store, cursor).poll().applied)
        assertEquals(11L, cursor.value)
    }
}
