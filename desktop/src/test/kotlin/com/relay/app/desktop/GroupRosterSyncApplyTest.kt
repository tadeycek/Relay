package com.relay.app.desktop

import com.relay.app.crypto.KeyMaterial
import com.relay.app.groups.GroupProtocol
import com.relay.app.linking.GroupRosterSync
import com.relay.app.linking.SyncBody
import com.relay.app.linking.SyncCodec
import com.relay.app.messaging.InnerEnvelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * WP05-AC4 + WP07-AC2 (desktop receiver): the real [SentSyncApplier] consumes the GROUP wire bytes recorded in
 * the shared fixture `core/src/test/resources/golden/group-roster-sync.fixture` (the same bytes the :app
 * producer test emits) and mirrors them into the real encrypted [DesktopMessageStore].
 */
class GroupRosterSyncApplyTest {

    @get:Rule val tmp = TemporaryFolder()

    private class ReverseKeys : KeyMaterial {
        override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray) = plaintext.reversedArray()
        override fun decryptMine(ciphertext: ByteArray) = ciphertext.reversedArray()
    }

    private class Server : SyncHttp {
        val events = mutableListOf<SyncEventDto>()
        val acks = mutableListOf<Long>()
        override suspend fun post(envelopeJson: String): SyncHttpResult<Boolean> = SyncHttpResult.Ok(false)
        override suspend fun list(after: Long, limit: Int): SyncHttpResult<SyncPage> {
            val rows = events.filter { it.cursor > after }
            val page = rows.take(limit)
            return SyncHttpResult.Ok(SyncPage(page, rows.size > limit, page.lastOrNull()?.cursor ?: after))
        }
        override suspend fun ack(through: Long): SyncHttpResult<Long> { acks += through; return SyncHttpResult.Ok(through) }
        fun wire(wire: String) {
            val bytes = wire.toByteArray(Charsets.UTF_8)
            events += SyncEventDto(events.size + 1L, "e${events.size}", 1, bytes.size.toLong(), SyncWire.sha256Hex(bytes), Base64.getEncoder().encodeToString(bytes))
        }
    }

    private val keys = ReverseKeys()
    private val seal: (String) -> String? = { InnerEnvelope.seal(keys, "OWN", it) }
    private val open: (String) -> String? = { InnerEnvelope.open(keys, it) }
    private val deviceId = "1".repeat(32)

    private fun fixture(): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core/src/test/resources/golden/group-roster-sync.fixture").exists()) dir = dir.parentFile
        return File(dir, "core/src/test/resources/golden/group-roster-sync.fixture").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private val own get() = fixture()["own_pubkey"]!!
    private val gid get() = fixture()["group_id"]!!
    private val conv get() = groupConversationId(own, gid)
    private fun base() = (GroupRosterSync.decode(fixture()["roster_plain"]!!) as GroupRosterSync.Result.Ok).roster

    private fun store(name: String = "s"): DesktopMessageStore {
        val p = DesktopPaths(tmp.newFolder("$name-c").toPath(), tmp.newFolder("$name-d").toPath())
        return DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile))
    }

    private fun applier(store: SyncStore, http: SyncHttp, ownKey: String = own, opener: (String) -> String? = open) =
        SentSyncApplier(deviceId, ownKey, http, store, openBody = opener)

    private fun wireOf(r: GroupRosterSync.Roster, origin: String = "primary") = SyncCodec.encode(GroupRosterSync.seal(r, origin, seal))
    private fun chunksOf(r: GroupRosterSync.Roster) = SyncCodec.chunk(GroupRosterSync.seal(r, "primary", seal))
    private fun rev(r: GroupRosterSync.Roster, revision: Long) = r.copy(state = r.state.copy(revision = revision))

    @Test fun `golden phone wire creates the group with roster avatar and the cursor in one step`() = runBlocking<Unit> {
        val s = store(); val server = Server().apply { wire(fixture()["sync_wire"]!!) }
        val report = applier(s, server).runOnce()
        assertEquals(1, report.groupApplied)
        assertNull(report.error)
        val g = s.storedGroup(conv)!!
        assertEquals("Team é", g.name)
        assertEquals(3L, g.revision)
        assertEquals(listOf(own, "b2".repeat(32), "c3".repeat(32)), g.members.map { it.key })
        assertEquals(2L, g.avatarRevision)
        assertNotNull(g.avatar)
        assertFalse(g.hidden)
        assertEquals(1L, s.syncCursor())
        assertEquals(listOf(1L), server.acks)
        assertTrue(s.conversations().any { it.id == conv })
        assertEquals(0, report.otherKind)
    }

    @Test fun `tombstone hides the group from the conversation list but keeps its history`() = runBlocking<Unit> {
        val s = store(); val server = Server().apply { wire(fixture()["sync_wire"]!!); wire(fixture()["sync_wire_tombstone"]!!) }
        s.insertMessage(DesktopMessage("m1", conv, own, "old", 10, true))
        val report = applier(s, server).runOnce()
        assertEquals(2, report.groupApplied)
        assertTrue(s.storedGroup(conv)!!.hidden)
        assertEquals(4L, s.storedGroup(conv)!!.revision)
        assertFalse(s.conversations().any { it.id == conv })
        assertEquals(1, s.messages(conv).size)
    }

    @Test fun `duplicates and replays are idempotent`() = runBlocking<Unit> {
        val s = store(); val server = Server().apply { wire(fixture()["sync_wire"]!!); wire(fixture()["sync_wire"]!!) }
        val first = applier(s, server).runOnce()
        assertEquals(1, first.groupApplied); assertEquals(1, first.groupStale)
        val before = s.storedGroup(conv)!!
        server.wire(fixture()["sync_wire"]!!)
        val again = applier(s, server).runOnce()
        assertEquals(0, again.groupApplied); assertEquals(1, again.groupStale)
        assertEquals(before, s.storedGroup(conv)!!)
        assertEquals(3L, s.syncCursor())
    }

    @Test fun `an older revision arriving after a newer one changes nothing - out of order`() = runBlocking<Unit> {
        val s = store(); val server = Server()
        server.wire(wireOf(rev(base(), 5).copy(state = base().state.copy(name = "Newer", revision = 5))))
        server.wire(wireOf(rev(base(), 4)))
        val r = applier(s, server).runOnce()
        assertEquals(1, r.groupApplied); assertEquals(1, r.groupStale)
        assertEquals("Newer", s.storedGroup(conv)!!.name)
        assertEquals(5L, s.storedGroup(conv)!!.revision)
    }

    @Test fun `a tombstone that overtakes its roster still wins - the late older roster cannot resurrect the group`() = runBlocking<Unit> {
        val s = store(); val server = Server().apply { wire(fixture()["sync_wire_tombstone"]!!); wire(fixture()["sync_wire"]!!) }
        applier(s, server).runOnce()
        val g = s.storedGroup(conv)!!
        assertTrue(g.hidden); assertEquals(4L, g.revision)
        assertFalse(s.conversations().any { it.id == conv })
    }

    @Test fun `removal of self is a tombstone`() = runBlocking<Unit> {
        val s = store(); val server = Server()
        val me = "b2".repeat(32)
        server.wire(wireOf(base()))
        val without = base().state.members.filter { it.key != me }
        server.wire(wireOf(base().copy(state = base().state.copy(revision = 4, members = without))))
        val r = applier(s, server, ownKey = me).runOnce()
        assertEquals(2, r.groupApplied)
        assertTrue(s.storedGroup(conv)!!.hidden)
        assertFalse(s.conversations().any { it.id == conv })
    }

    @Test fun `a later roster that re-adds us shows the group again`() = runBlocking<Unit> {
        val s = store(); val server = Server().apply { wire(fixture()["sync_wire"]!!); wire(fixture()["sync_wire_tombstone"]!!); wire(wireOf(rev(base(), 5))) }
        applier(s, server).runOnce()
        assertFalse(s.storedGroup(conv)!!.hidden)
        assertTrue(s.conversations().any { it.id == conv })
    }

    @Test fun `a newer avatar revision alone updates only the picture`() = runBlocking<Unit> {
        val s = store(); val server = Server().apply { wire(fixture()["sync_wire"]!!) }
        applier(s, server).runOnce()
        server.wire(wireOf(base().copy(avatarRevision = 3, avatar = null)))
        val r = applier(s, server).runOnce()
        assertEquals(1, r.groupApplied)
        val g = s.storedGroup(conv)!!
        assertNull(g.avatar); assertEquals(3L, g.avatarRevision); assertEquals(3L, g.revision)
    }

    @Test fun `an invalid avatar image keeps the roster change and is not stored`() = runBlocking<Unit> {
        val s = store(); val server = Server()
        val fake = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 4, 5)
        server.wire(wireOf(base().copy(avatar = fake)))
        val r = applier(s, server).runOnce()
        assertEquals(1, r.groupApplied)
        assertNull(s.storedGroup(conv)!!.avatar)
        assertEquals(-1L, s.storedGroup(conv)!!.avatarRevision)
    }

    @Test fun `a desktop-origin GROUP event is refused - rosters come only from the phone`() = runBlocking<Unit> {
        val s = store(); val server = Server().apply { wire(wireOf(base(), origin = "f".repeat(32))) }
        val r = applier(s, server).runOnce()
        assertEquals(1, r.groupRejected); assertEquals(0, r.groupApplied)
        assertNull(s.storedGroup(conv))
        assertEquals(1L, s.syncCursor()) // still advances
    }

    @Test fun `a body that is not sealed to our key is counted and skipped`() = runBlocking<Unit> {
        val s = store(); val server = Server().apply { wire(fixture()["sync_wire"]!!) }
        val r = applier(s, server, opener = { null }).runOnce()
        assertEquals(1, r.groupRejected)
        assertNull(s.storedGroup(conv)); assertEquals(1L, s.syncCursor())
    }

    @Test fun `malformed and oversize rosters never wedge the lane`() = runBlocking<Unit> {
        val s = store(); val server = Server()
        fun junk(plain: String) = SyncCodec.chunk(SyncBody.Group("primary", "ab".repeat(8), 9, Base64.getEncoder().encodeToString(seal(plain)!!.toByteArray())))
        junk("not a roster").forEach { server.wire(it) }
        junk("x".repeat(GroupRosterSync.MAX_PLAINTEXT_BYTES + 100)).forEach { server.wire(it) }
        server.wire(fixture()["sync_wire"]!!)
        val r = applier(s, server).runOnce()
        assertEquals(2, r.groupRejected); assertEquals(1, r.groupApplied)
        assertNotNull(s.storedGroup(conv))
        assertEquals(server.events.size.toLong(), s.syncCursor())
    }

    @Test fun `a chunked roster with a full-size avatar applies`() = runBlocking<Unit> {
        val s = store(); val server = Server()
        val members = listOf(GroupProtocol.Member(own, "Owner")) + (1 until 17).map { GroupProtocol.Member("%064x".format(it), "member $it", "B".repeat(150)) }
        val big = base().copy(state = base().state.copy(members = members, name = "Big"))
        val chunks = chunksOf(big)
        assertTrue(chunks.size >= 1)
        chunks.forEach { server.wire(it) }
        val r = applier(s, server).runOnce()
        assertEquals(1, r.groupApplied)
        assertEquals(17, s.storedGroup(conv)!!.members.size)
    }

    @Test fun `same group id under a different owner is a conflict and is not applied`() = runBlocking<Unit> {
        val s = store()
        s.saveGroupState(base().state.copy(owner = "d4".repeat(32), members = listOf(GroupProtocol.Member("d4".repeat(32), "X"), GroupProtocol.Member(own, "Me"))))
        val server = Server().apply { wire(fixture()["sync_wire"]!!) }
        val r = applier(s, server).runOnce()
        assertEquals(1, r.groupRejected); assertEquals(0, r.groupApplied)
        assertNull(s.storedGroup(conv))
    }

    @Test fun `roster and cursor commit together or not at all`() = runBlocking<Unit> {
        val real = store()
        val failing = object : SyncStore by real {
            var fail = true
            override fun commitGroupSync(roster: GroupRosterSync.Roster, cursor: Long, now: Long): GroupCommit {
                if (fail) throw java.sql.SQLException("disk full")
                return real.commitGroupSync(roster, cursor, now)
            }
        }
        val server = Server().apply { wire(fixture()["sync_wire"]!!) }
        val a = applier(failing, server)
        assertEquals("commit failed", a.runOnce().error)
        assertEquals(0L, real.syncCursor()); assertNull(real.storedGroup(conv)); assertTrue(server.acks.isEmpty())
        failing.fail = false
        assertEquals(1, a.runOnce().groupApplied)
        assertEquals(1L, real.syncCursor()); assertEquals(listOf(1L), server.acks)
    }

    @Test fun `store commit is one transaction - a failure after the roster write rolls the roster back`() {
        val s = store()
        s.afterRosterWriteHook = { throw java.sql.SQLException("injected failure between the roster write and the cursor write") }
        try { s.commitGroupSync(base(), 7, 1_000); org.junit.Assert.fail("expected failure") } catch (e: Exception) { }
        assertNull(s.storedGroup(conv)); assertEquals(0L, s.syncCursor())
        s.afterRosterWriteHook = null
        assertEquals(GroupCommit.APPLIED, s.commitGroupSync(base(), 7, 1_000))
        assertEquals(7L, s.syncCursor())
    }

    @Test fun `state survives a reopen`() {
        val p = DesktopPaths(tmp.newFolder("r-c").toPath(), tmp.newFolder("r-d").toPath())
        DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile)).use { it.commitGroupSync(base(), 3, 1_000); it.commitGroupSync(rev(base(), 4).copy(tombstone = true), 4, 2_000) }
        DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile)).use {
            assertTrue(it.storedGroup(conv)!!.hidden); assertEquals(4L, it.syncCursor()); assertFalse(it.conversations().any { c -> c.id == conv })
        }
    }

    @Test fun `a direct roster from the group owner that lists us unhides a tombstoned group`() {
        val s = store()
        s.commitGroupSync(rev(base(), 4).copy(tombstone = true), 1, 1_000)
        s.saveGroupState(rev(base(), 6).state, now = 3_000, visible = true)
        assertFalse(s.storedGroup(conv)!!.hidden)
    }
}
