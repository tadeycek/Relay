package com.relay.app.sync

import com.relay.app.crypto.KeyMaterial
import com.relay.app.groups.GroupProtocol
import com.relay.app.linking.GroupRosterSync
import com.relay.app.linking.ReassemblyResult
import com.relay.app.linking.SyncBody
import com.relay.app.linking.SyncCodec
import com.relay.app.linking.SyncReassembler
import com.relay.app.linking.SyncResult
import com.relay.app.messaging.InnerEnvelope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * WP05-AC3 (phone producer): a roster edit becomes a sealed GROUP sync event through the durable sync outbox.
 * The golden wire is the shared fixture `core/src/test/resources/golden/group-roster-sync.fixture`; the
 * desktop test feeds the same bytes to its real applier.
 */
class GroupRosterProducerTest {

    private class ReverseKeys : KeyMaterial {
        override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray) = plaintext.reversedArray()
        override fun decryptMine(ciphertext: ByteArray) = ciphertext.reversedArray()
    }

    private class Hint(var value: Boolean = true) : LinkedHint {
        override fun get() = value
        override fun set(value: Boolean) { this.value = value }
    }

    private class Outbox : SyncOutbox {
        val rows = LinkedHashMap<String, SyncOutboxItem>()
        override fun enqueue(item: SyncOutboxItem) = rows.putIfAbsent(item.payloadId, item) == null
        override fun confirm(payloadId: String) { rows[payloadId]?.let { rows[payloadId] = it.copy(confirmed = true) } }
        override fun discard(payloadId: String) { rows.remove(payloadId) }
        override fun due(now: Long, limit: Int) = rows.values.filter { it.confirmed && it.nextAttemptAt <= now }.sortedBy { it.createdAt }.take(limit)
        override fun backedOffBefore(createdAt: Long, now: Long) = rows.values.any { it.confirmed && it.createdAt < createdAt && it.nextAttemptAt > now }
        override fun recordAttempt(payloadId: String, attempts: Int, nextAttemptAt: Long) {
            rows[payloadId]?.let { rows[payloadId] = it.copy(attempts = attempts, nextAttemptAt = nextAttemptAt) }
        }
        override fun remove(payloadId: String) { rows.remove(payloadId) }
        override fun purgeStale(olderThan: Long) = 0
        override fun clear() = rows.clear()
    }

    private class Net : SyncNetwork {
        val results = ArrayDeque<PostResult>()
        val attempts = mutableListOf<String>() // every POST body, accepted or not
        var calls = 0
        override suspend fun listDevices(): DevicesResult { calls++; return DevicesResult.Ok(1) }
        override suspend fun post(envelopeJson: String): PostResult { calls++; attempts += envelopeJson; return results.removeFirstOrNull() ?: PostResult.Ok }
        override suspend fun list(after: Long, limit: Int): ListResult { calls++; return ListResult.Ok(emptyList(), false) }
        override suspend fun ack(through: Long): Boolean { calls++; return true }
        fun wires() = attempts.map { String(Base64.getDecoder().decode(JSONObject(it).getString("data")), Charsets.UTF_8) }
    }

    private val keys = ReverseKeys()
    private val seal: (String) -> String? = { InnerEnvelope.seal(keys, "OWN", it) }
    private var now = 5_000_000L
    private val clock: () -> Long = { now }

    private fun fixture(): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core/src/test/resources/golden/group-roster-sync.fixture").exists()) dir = dir.parentFile
        return File(dir, "core/src/test/resources/golden/group-roster-sync.fixture").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun fixtureRoster(key: String = "roster_plain") = (GroupRosterSync.decode(fixture()[key]!!) as GroupRosterSync.Result.Ok).roster

    private fun publisher(net: Net, outbox: Outbox, hint: Hint = Hint()) = SentSyncPublisher(net, outbox, hint, clock)
    private fun stager(outbox: Outbox, hint: Hint = Hint(), sealer: (String) -> String? = seal) = GroupRosterStager(outbox, hint, sealer, clock)

    @Test fun `producer bytes equal the shared golden wire for a full roster`() = runBlocking<Unit> {
        val outbox = Outbox(); val net = Net()
        assertTrue(stager(outbox).stage(fixtureRoster()))
        val report = publisher(net, outbox).flush()
        assertEquals(1, report.posted)
        assertEquals(listOf(fixture()["sync_wire"]), net.wires())
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun `producer bytes equal the shared golden wire for a tombstone`() = runBlocking<Unit> {
        val outbox = Outbox(); val net = Net()
        stager(outbox).stage(fixtureRoster("roster_tombstone_plain"))
        publisher(net, outbox).flush()
        assertEquals(listOf(fixture()["sync_wire_tombstone"]), net.wires())
    }

    @Test fun `event is origin primary and sealed - no plaintext name key or picture on the wire`() = runBlocking<Unit> {
        val outbox = Outbox(); val net = Net()
        stager(outbox).stage(fixtureRoster())
        publisher(net, outbox).flush()
        val body = (SyncCodec.decode(net.wires().single()) as SyncResult.Ok).body as SyncBody.Group
        assertEquals("primary", body.origin)
        val envelope = net.attempts.single()
        assertFalse(envelope.contains("Team"))
        assertFalse(envelope.contains(fixtureRoster().state.owner))
    }

    @Test fun `unlinked install stages nothing and makes zero requests`() = runBlocking<Unit> {
        val outbox = Outbox(); val net = Net(); val hint = Hint(false)
        assertFalse(stager(outbox, hint).stage(fixtureRoster()))
        assertTrue(outbox.rows.isEmpty())
        val report = publisher(net, outbox, hint).flush()
        assertEquals(FlushReport(), report)
        assertEquals(0, net.calls)
    }

    @Test fun `network failure never blocks the edit and the retry after restart sends identical bytes`() = runBlocking<Unit> {
        val outbox = Outbox(); val net = Net()
        assertTrue(stager(outbox).stage(fixtureRoster())) // returns at once: no network involved
        net.results += PostResult.Retry("unreachable")
        val first = publisher(net, outbox).flush()
        assertEquals(1, first.retried)
        assertEquals(1, outbox.rows.size)
        // "Restart": a brand-new publisher over the same durable rows, after the backoff.
        now += 20 * 60_000L
        val second = publisher(net, outbox).flush()
        assertEquals(1, second.posted)
        assertEquals(2, net.attempts.size)
        assertEquals(net.wires()[0], net.wires()[1])
        assertEquals(JSONObject(net.attempts[0]).getString("id"), JSONObject(net.attempts[1]).getString("id"))
        assertEquals(JSONObject(net.attempts[0]).getString("hash"), JSONObject(net.attempts[1]).getString("hash"))
    }

    @Test fun `re-staging the same snapshot while queued adds no second row`() {
        val outbox = Outbox()
        val s = stager(outbox)
        assertTrue(s.stage(fixtureRoster()))
        assertFalse(s.stage(fixtureRoster()))
        assertEquals(1, outbox.rows.size)
    }

    @Test fun `older rosters are posted first and a backed-off one holds back the later ones`() = runBlocking<Unit> {
        val outbox = Outbox(); val net = Net()
        val base = fixtureRoster()
        val s = stager(outbox)
        s.stage(base)
        now += 10
        s.stage(base.copy(state = base.state.copy(revision = base.state.revision + 1)))
        net.results += PostResult.Retry("http 503")
        assertEquals(1, publisher(net, outbox).flush().retried)
        assertEquals(1, net.attempts.size) // the lane stopped
        // Still inside the first row's backoff: the second row must NOT overtake it.
        now += 1_000
        assertEquals(FlushReport(), publisher(net, outbox).flush())
        assertEquals(1, net.attempts.size)
        now += 20 * 60_000L
        assertEquals(2, publisher(net, outbox).flush().posted)
        val revs = net.wires().map { (SyncCodec.decode(it) as SyncResult.Ok).body.revision }
        assertEquals(revs.sorted(), revs)
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun `a permanently rejected roster is dropped and does not wedge the lane`() = runBlocking<Unit> {
        val outbox = Outbox(); val net = Net()
        val base = fixtureRoster()
        val s = stager(outbox)
        s.stage(base); now += 10
        s.stage(base.copy(state = base.state.copy(revision = base.state.revision + 1)))
        net.results += PostResult.Permanent("http 400")
        val r = publisher(net, outbox).flush()
        assertEquals(1, r.dropped)
        assertEquals(1, r.items)
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun `an unsealable roster queues nothing`() {
        val outbox = Outbox()
        assertFalse(stager(outbox, sealer = { null }).stage(fixtureRoster()))
        assertTrue(outbox.rows.isEmpty())
    }

    @Test fun `a roster larger than one wire body is chunked and reassembles to the same roster`() = runBlocking<Unit> {
        val outbox = Outbox(); val net = Net()
        val avatar = ByteArray(GroupRosterSync.MAX_AVATAR_BYTES).also { it[0] = 0xff.toByte(); it[1] = 0xd8.toByte(); it[2] = 0xff.toByte() }
        val members = listOf(GroupProtocol.Member("a1".repeat(32), "Owner")) + (1 until 17).map {
            GroupProtocol.Member("%064x".format(it), "member $it", "B".repeat(120))
        }
        val big = GroupRosterSync.Roster(GroupProtocol.State("11111111-2222-4333-8444-555555555555", "a1".repeat(32), "Big", 9, members), 4, avatar, false)
        assertTrue(stager(outbox).stage(big))
        val report = publisher(net, outbox).flush()
        assertTrue(report.posted >= 2)
        val ids = net.attempts.map { JSONObject(it).getString("id") }
        assertEquals(ids.size, ids.toSet().size)
        val reassembler = SyncReassembler()
        var whole: String? = null
        for (w in net.wires()) {
            assertTrue(w.toByteArray().size <= SyncLimits.MAX_EVENT_BYTES)
            val res = reassembler.offer((SyncCodec.decode(w) as SyncResult.Ok).body as SyncBody.Part, now)
            if (res is ReassemblyResult.Complete) whole = res.body
        }
        val g = (SyncCodec.decode(whole!!) as SyncResult.Ok).body as SyncBody.Group
        val back = (GroupRosterSync.open(g) { InnerEnvelope.open(keys, it) } as GroupRosterSync.Result.Ok).roster
        assertEquals(big, back)
    }
}
