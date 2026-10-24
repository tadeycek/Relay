package com.relay.app.linking

import com.relay.app.crypto.KeyMaterial
import com.relay.app.groups.GroupProtocol
import com.relay.app.messaging.InnerEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * WP05-AC3/AC4: the versioned, bounded `GROUP` roster schema (`GroupRosterSync`, "GroupRoster/1").
 * `golden/group-roster-sync.fixture` is the wire fixture the :app producer test and the :desktop
 * applier test also consume; this class owns recording it (`RELAY_GOLDEN_RECORD=1`).
 */
class GroupRosterSyncTest {

    /** Deterministic stand-in for HPKE so the fixture is byte-stable (the real sealing is Tink/Keystore). */
    private class ReverseKeys : KeyMaterial {
        override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray) = plaintext.reversedArray()
        override fun decryptMine(ciphertext: ByteArray) = ciphertext.reversedArray()
    }

    private val keys = ReverseKeys()
    private val seal: (String) -> String? = { InnerEnvelope.seal(keys, "OWNKEY", it) }
    private val open: (String) -> String? = { InnerEnvelope.open(keys, it) }

    private val owner = "a1".repeat(32)
    private val friend = "b2".repeat(32)
    private val other = "c3".repeat(32)
    private val gid = "11111111-2222-4333-8444-555555555555"
    private val inner = "A".repeat(44)
    private val jpeg = Base64.getDecoder().decode(JPEG_B64)

    private fun state(revision: Long = 3, members: List<GroupProtocol.Member>? = null) = GroupProtocol.State(
        gid, owner, "Team é", revision,
        members ?: listOf(
            GroupProtocol.Member(owner, "Owner"),
            GroupProtocol.Member(friend, "Friend", inner),
            GroupProtocol.Member(other, "Other", inner),
        ),
    )

    private fun roster(
        revision: Long = 3, avatarRevision: Long = 2, avatar: ByteArray? = jpeg, tombstone: Boolean = false,
    ) = GroupRosterSync.Roster(state(revision), avatarRevision, avatar, tombstone)

    // ---- schema -----------------------------------------------------------------------------

    @Test fun roundTripKeepsEveryField() {
        val r = roster()
        val back = (GroupRosterSync.decode(GroupRosterSync.encode(r)) as GroupRosterSync.Result.Ok).roster
        assertEquals(r.state, back.state)
        assertEquals(2L, back.avatarRevision)
        assertArrayEquals(jpeg, back.avatar)
        assertFalse(back.tombstone)
    }

    @Test fun tombstoneAndAvatarlessRostersRoundTrip() {
        val t = roster(avatarRevision = -1, avatar = null, tombstone = true)
        val back = (GroupRosterSync.decode(GroupRosterSync.encode(t)) as GroupRosterSync.Result.Ok).roster
        assertTrue(back.tombstone)
        assertEquals(-1L, back.avatarRevision)
        assertEquals(null, back.avatar)
        // "avatar removed" is revision >= 0 with no bytes
        val removed = roster(avatarRevision = 5, avatar = null)
        assertEquals(5L, (GroupRosterSync.decode(GroupRosterSync.encode(removed)) as GroupRosterSync.Result.Ok).roster.avatarRevision)
    }

    @Test fun goldenPlaintextIsPinned() {
        assertEquals(fixture()["roster_plain"], GroupRosterSync.encode(roster()))
        assertEquals(fixture()["roster_tombstone_plain"], GroupRosterSync.encode(roster(revision = 4, avatarRevision = -1, avatar = null, tombstone = true)))
    }

    @Test fun goldenSyncWireIsPinnedAndOpensToTheSameRoster() {
        val body = GroupRosterSync.seal(roster(), GroupRosterSync.ORIGIN_PRIMARY, seal)
        val wire = SyncCodec.encode(body)
        assertEquals(fixture()["sync_wire"], wire)
        val decoded = (SyncCodec.decode(fixture()["sync_wire"]!!) as SyncResult.Ok).body as SyncBody.Group
        assertEquals("primary", decoded.origin)
        assertEquals(GroupRosterSync.syncId(roster()), decoded.syncId)
        assertEquals(GroupRosterSync.syncRevision(roster()), decoded.revision)
        assertEquals(roster().state, (GroupRosterSync.open(decoded, open) as GroupRosterSync.Result.Ok).roster.state)
    }

    @Test fun syncIdIsStablePerSnapshotAndRevisionIsMonotonic() {
        assertEquals(GroupRosterSync.syncId(roster()), GroupRosterSync.syncId(roster()))
        assertTrue(GroupRosterSync.syncId(roster()).matches(Regex("[0-9a-f]{32}")))
        val ids = setOf(
            GroupRosterSync.syncId(roster()), GroupRosterSync.syncId(roster(revision = 4)),
            GroupRosterSync.syncId(roster(avatarRevision = 3)), GroupRosterSync.syncId(roster(tombstone = true)),
        )
        assertEquals(4, ids.size)
        val a = GroupRosterSync.syncRevision(roster())
        assertTrue(GroupRosterSync.syncRevision(roster(revision = 4)) > a)
        assertTrue(GroupRosterSync.syncRevision(roster(avatarRevision = 3)) > a)
        assertTrue(GroupRosterSync.syncRevision(roster(tombstone = true)) > a)
    }

    @Test fun sealedBodyNeverShowsNamesKeysOrAvatar() {
        val wire = SyncCodec.encode(GroupRosterSync.seal(roster(), "primary", seal))
        val j = (SyncCodec.decode(wire) as SyncResult.Ok).body as SyncBody.Group
        val sealed = String(Base64.getDecoder().decode(j.rosterJsonBase64))
        assertTrue(sealed.startsWith("TYPE:ENC|CT:"))
        assertFalse(sealed.contains("Team"))
        assertFalse(sealed.contains(owner))
    }

    @Test fun maxRosterWithMaxAvatarFitsTheCodecThroughChunking() {
        val big = ByteArray(GroupRosterSync.MAX_AVATAR_BYTES).also { it[0] = 0xff.toByte(); it[1] = 0xd8.toByte(); it[2] = 0xff.toByte() }
        val members = listOf(GroupProtocol.Member(owner, "o".repeat(30))) + (1 until GroupProtocol.MAX_MEMBERS).map {
            GroupProtocol.Member("%064x".format(it), "n".repeat(30), "B".repeat(200), listOf("wss://relay.damus.io", "wss://nos.lol"))
        }
        val s = GroupProtocol.fitted(GroupProtocol.State(gid, owner, "x".repeat(30), 9, members))
        val body = GroupRosterSync.seal(GroupRosterSync.Roster(s, 1, big, false), "primary", seal)
        val wires = SyncCodec.chunk(body)
        assertTrue(wires.size >= 2)
        assertTrue(wires.all { it.toByteArray().size <= SyncCodec.MAX_RAW_BYTES })
        val r = SyncReassembler()
        var whole: String? = null
        for (w in wires) {
            val p = (SyncCodec.decode(w) as SyncResult.Ok).body as SyncBody.Part
            val res = r.offer(p, 0L)
            if (res is ReassemblyResult.Complete) whole = res.body
        }
        val g = (SyncCodec.decode(whole!!) as SyncResult.Ok).body as SyncBody.Group
        assertArrayEquals(big, (GroupRosterSync.open(g, open) as GroupRosterSync.Result.Ok).roster.avatar)
    }

    // ---- bounds / hostile input ---------------------------------------------------------------

    @Test fun encodeRefusesOversizeAvatarNonJpegAndInconsistentFields() {
        fun bad(r: GroupRosterSync.Roster) = try { GroupRosterSync.encode(r); fail("accepted") } catch (e: IllegalArgumentException) { }
        val over = ByteArray(GroupRosterSync.MAX_AVATAR_BYTES + 1).also { it[0] = 0xff.toByte(); it[1] = 0xd8.toByte(); it[2] = 0xff.toByte() }
        bad(roster(avatar = over))
        bad(roster(avatar = byteArrayOf(1, 2, 3, 4)))
        bad(roster(avatarRevision = -1, avatar = jpeg)) // bytes without a revision
        bad(roster(avatarRevision = -2, avatar = null))
    }

    @Test fun decodeRejectsEveryMalformedShapeWithoutThrowing() {
        val good = GroupRosterSync.encode(roster())
        val cases = listOf(
            "", "not json", "[]", "{}", good.replace("\"v\":1", "\"v\":2"), good.replace("\"v\":1", "\"v\":\"1\""),
            good.replace("\"tombstone\":false", "\"tombstone\":\"no\""),
            good.dropLast(1) + ",\"extra\":1}",
            good.replace("\"avatarRev\":2", "\"avatarRev\":-9"),
            good.replace("\"avatar\":\"", "\"avatar\":\"!!"),
            "x".repeat(GroupRosterSync.MAX_PLAINTEXT_BYTES + 1),
        )
        for (c in cases) assertTrue(c.take(40), GroupRosterSync.decode(c) is GroupRosterSync.Result.Err)
        assertEquals(GroupRosterSync.RosterError.UNSUPPORTED_VERSION, (GroupRosterSync.decode(good.replace("\"v\":1", "\"v\":2")) as GroupRosterSync.Result.Err).error)
        assertEquals(GroupRosterSync.RosterError.TOO_LARGE, (GroupRosterSync.decode("x".repeat(GroupRosterSync.MAX_PLAINTEXT_BYTES + 1)) as GroupRosterSync.Result.Err).error)
    }

    @Test fun decodeRejectsAnInvalidInnerStateAndAnAvatarThatIsNotAJpeg() {
        val good = GroupRosterSync.encode(roster())
        assertTrue(GroupRosterSync.decode(good.replace("revision", "revisioX")) is GroupRosterSync.Result.Err)
        val notJpeg = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(GroupRosterSync.decode(good.replace(JPEG_B64, notJpeg)) is GroupRosterSync.Result.Err)
    }

    @Test fun openFailsWhenTheBodyCannotBeOpened() {
        val body = GroupRosterSync.seal(roster(), "primary", seal)
        assertEquals(GroupRosterSync.RosterError.UNREADABLE, (GroupRosterSync.open(body, { null }) as GroupRosterSync.Result.Err).error)
        assertNotEquals(null, body)
    }

    // ---- fixture -------------------------------------------------------------------------------

    private fun fixture(): Map<String, String> {
        val file = fixtureFile()
        if (System.getenv("RELAY_GOLDEN_RECORD") == "1") {
            file.parentFile.mkdirs()
            val tomb = roster(revision = 4, avatarRevision = -1, avatar = null, tombstone = true)
            file.writeText(
                listOf(
                    "# GroupRoster/1 golden wire fixture. Sealer: reverse-v1 (deterministic test stand-in for HPKE).",
                    "# Consumed by :core, :app (producer) and :desktop (applier) tests. Re-record: RELAY_GOLDEN_RECORD=1 :core:test",
                    "own_pubkey=$owner",
                    "group_id=$gid",
                    "roster_plain=" + GroupRosterSync.encode(roster()),
                    "roster_tombstone_plain=" + GroupRosterSync.encode(tomb),
                    "sync_wire=" + SyncCodec.encode(GroupRosterSync.seal(roster(), "primary", seal)),
                    "sync_wire_tombstone=" + SyncCodec.encode(GroupRosterSync.seal(tomb, "primary", seal)),
                    "",
                ).joinToString("\n"),
            )
        }
        return file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private fun fixtureFile() = File("src/test/resources/golden/group-roster-sync.fixture")

    companion object {
        const val JPEG_B64 = "/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCjoXgb7v7r9KKKK4MVmOI9o9TbIs2xX1OPvH//2Q=="
    }
}
