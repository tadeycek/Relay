package com.relay.app.call.group

import com.relay.app.groups.GroupProtocol
import org.junit.Assert.*
import org.junit.Test

class GroupCallFoundationTest {
    private val owner = "a".repeat(64)
    private val member = "b".repeat(64)
    private val outsider = "c".repeat(64)
    private val roster = GroupProtocol.State("123e4567-e89b-12d3-a456-426614174000", owner, "Voice", 3,
        listOf(GroupProtocol.Member(owner, "Owner"), GroupProtocol.Member(member, "Member")))
    private val room = "1".repeat(32)
    private val secret = "2".repeat(64)
    private var now = 100_000L
    private fun start(by: String = owner, id: String = "3".repeat(32), created: Long = now) = GroupAnnouncement(
        "group_start", id, roster.id, roster.owner, roster.revision, GroupCallCodec.rosterHash(roster), room, secret,
        by, "primary", created, created + 60_000)
    private fun sender(identity: String) = VerifiedGroupSender.fromAuthenticatedSeal(identity)
    private fun receive(wire: String, identity: String = owner, current: GroupProtocol.State = roster,
        replay: GroupEventReplay = GroupEventReplay()): GroupDecodeResult = GroupCallCodec.decode(wire, sender(identity), current, now, replay)

    @Test fun startGoldenRoundTripAndSealedOnlyContract() {
        val encoded = GroupCallCodec.encode(start())
        assertEquals(javaClass.getResource("/golden/group-call-start.json")!!.readText().trim(), encoded)
        assertEquals(start(), (receive(encoded) as GroupDecodeResult.Accepted).announcement)
        assertTrue(encoded.contains("\"joinSecret\"")) // plaintext is only supplied to authenticated E2E sealers
    }

    @Test fun endGoldenRoundTrip() {
        val end = start().copy(op = "group_end", joinSecret = null)
        val encoded = GroupCallCodec.encode(end)
        assertEquals(javaClass.getResource("/golden/group-call-end.json")!!.readText().trim(), encoded)
        assertEquals(end, (receive(encoded) as GroupDecodeResult.Accepted).announcement)
    }

    @Test fun malformedOversizeForeignStaleAndReplayRejected() {
        val wire = GroupCallCodec.encode(start())
        val replay = GroupEventReplay()
        assertTrue(receive(wire, replay = replay) is GroupDecodeResult.Accepted)
        assertEquals(GroupReject.DUPLICATE, (receive(wire, replay = replay) as GroupDecodeResult.Rejected).reason)
        assertEquals(GroupReject.FOREIGN_ROSTER, (receive(wire, current = roster.copy(id = "123e4567-e89b-12d3-a456-426614174001")) as GroupDecodeResult.Rejected).reason)
        assertEquals(GroupReject.STALE_ROSTER, (receive(wire, current = roster.copy(revision = 4)) as GroupDecodeResult.Rejected).reason)
        assertEquals(GroupReject.UNAUTHENTICATED, (receive(wire, identity = outsider) as GroupDecodeResult.Rejected).reason)
        assertEquals(GroupReject.EXPIRED, (receive(GroupCallCodec.encode(start(created = 1))) as GroupDecodeResult.Rejected).reason)
        listOf(wire.replace("\"v\":1", "\"v\":2"), wire.replace("\"room\":\"${room}\"", "\"room\":\"ABC\""),
            wire.replace("\"event\":", "\"event\":\"x\",\"event\":"), wire + " ".repeat(8192),
            wire.replace("\"joinSecret\":\"${secret}\"", "\"joinSecret\":\"short\""),
            wire.replace("\"device\":\"primary\"", "\"device\":\"${"x".repeat(65)}\"")).forEach {
            assertTrue(it, receive(it) is GroupDecodeResult.Rejected)
        }
    }

    @Test fun competingStartsChooseDeterministicRoomAndEndOnlyByStarter() {
        val engine = GroupCallEngine(roster, owner, { now })
        val high = start(id = "f".repeat(32)).copy(room = "f".repeat(32))
        val low = start(id = "0".repeat(32)).copy(room = "0".repeat(32))
        engine.apply(high, sender(owner))
        engine.apply(low, sender(owner))
        assertEquals(low.room, engine.state.room)
        engine.apply(low.copy(op = "group_end", joinSecret = null, caller = member, eventId = "4".repeat(32)), sender(member))
        assertEquals(low.room, engine.state.room)
        assertTrue(engine.apply(low.copy(op = "group_end", joinSecret = null, eventId = "5".repeat(32)), sender(owner)).contains(GroupCallEffect.CloseRoom))
        assertNull(engine.state.room)
    }

    @Test fun twoDevicesSamePubkeyRemainDistinctSessionsAndDiff() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(), sender(owner)); engine.confirmedRoom(room)
        val first = GroupParticipant("1".repeat(16), member, "phone", "session-one", "mic", false)
        val second = GroupParticipant("2".repeat(16), member, "desktop", "session-two", "mic", false)
        val effects = engine.updateParticipants(listOf(first, second))
        assertEquals(setOf(first.trackRef(), second.trackRef()), effects.filterIsInstance<GroupCallEffect.Subscribe>().map { it.ref }.toSet())
        assertEquals(2, engine.state.participants.size)
        assertEquals(listOf(GroupCallEffect.Unsubscribe(first.trackRef()!!)), engine.updateParticipants(listOf(second)).filterIsInstance<GroupCallEffect.Unsubscribe>())
    }

    @Test fun unknownRosterParticipantWarnsButIsNotServerAuthorization() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(), sender(owner))
        val stranger = GroupParticipant("1".repeat(16), outsider, "phone", "session", "mic", false)
        val effects = engine.updateParticipants(listOf(stranger))
        assertTrue(effects.contains(GroupCallEffect.WarnUnknown(stranger.pid)))
        assertFalse(effects.any { it is GroupCallEffect.Subscribe })
        assertEquals("Group calls are encrypted in transit, not end to end.", engine.state.securityLabel)
    }

    @Test fun removedMemberLeavesImmediatelyAndCannotRejoinFromOldRoom() {
        val engine = GroupCallEngine(roster, member, { now })
        engine.apply(start(), sender(owner))
        val effects = engine.updateRoster(roster.copy(revision = 4, members = listOf(roster.members.first())))
        assertTrue(effects.contains(GroupCallEffect.CloseRoom))
        assertFalse(engine.apply(start(), sender(owner)).any { it is GroupCallEffect.OpenRoom })
    }

    @Test fun expiredInviteNeverOpensAndExpiryClosesActiveRoom() {
        val engine = GroupCallEngine(roster, owner, { now })
        assertFalse(engine.apply(start(created = 1), sender(owner)).any { it is GroupCallEffect.OpenRoom })
        engine.apply(start(), sender(owner))
        now += 60_001
        assertTrue(engine.tick().contains(GroupCallEffect.CloseRoom))
        assertNull(engine.state.room)
    }

    @Test fun engineRejectsForgedSenderAndEndedRoomReplay() {
        val engine = GroupCallEngine(roster, owner, { now })
        assertTrue(engine.apply(start(), sender(outsider)).isEmpty())
        assertNull(engine.state.room)
        assertTrue(engine.apply(start(), sender(owner)).any { it is GroupCallEffect.OpenRoom })
        engine.apply(start().copy(op = "group_end", joinSecret = null, eventId = "4".repeat(32)), sender(owner))
        assertFalse(engine.apply(start().copy(eventId = "5".repeat(32)), sender(owner)).any { it is GroupCallEffect.OpenRoom })
    }

    @Test fun sessionReplacementUnsubscribesOldBeforeSubscribingNew() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(), sender(owner)); engine.confirmedRoom(room)
        val first = GroupParticipant("1".repeat(16), member, "phone", "session-one", "mic", false)
        engine.updateParticipants(listOf(first))
        val replacement = first.copy(sessionId = "session-two")
        assertEquals(listOf(GroupCallEffect.Unsubscribe(first.trackRef()!!), GroupCallEffect.Subscribe(replacement.trackRef()!!)),
            engine.updateParticipants(listOf(replacement)))
    }

    @Test fun mintedStartUsesInjectedCryptographicBytesAndEndBindsRoom() {
        var n = 0
        val minted = GroupCallCodec.newStart(roster, owner, "primary", now, { size ->
            n++
            ByteArray(size) { n.toByte() }
        })
        assertEquals("01".repeat(16), minted.room)
        assertEquals("02".repeat(32), minted.joinSecret)
        assertEquals("03".repeat(16), minted.eventId)
        val end = GroupCallCodec.newEnd(minted, now + 1000, { ByteArray(it) { 4 } })
        assertEquals(minted.room, end.room)
        assertNull(end.joinSecret)
        assertEquals("04".repeat(16), end.eventId)
    }

    @Test fun endBeforeStartFencesRoomEvenWithDistinctEventAndRestart() {
        val engine = GroupCallEngine(roster, owner, { now })
        val end = start().copy(op = "group_end", joinSecret = null, eventId = "4".repeat(32))
        assertTrue(engine.apply(end, sender(owner)).isEmpty())
        assertTrue(engine.apply(start(), sender(owner)).isEmpty())
        assertNull(engine.state.room)
        assertTrue(engine.apply(start().copy(eventId = "5".repeat(32)), sender(owner)).isEmpty())
    }

    @Test fun logRepresentationsNeverIncludeJoinSecret() {
        val announcement = start()
        assertFalse(announcement.toString().contains(secret))
        assertFalse(GroupCallEffect.OpenRoom(room, secret).toString().contains(secret))
        assertEquals(announcement, announcement.copy())
        assertEquals(GroupCallEffect.OpenRoom(room, secret).hashCode(), GroupCallEffect.OpenRoom(room, secret).hashCode())
    }

    @Test fun invalidSnapshotStopsGhostPlayback() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(), sender(owner)); engine.confirmedRoom(room)
        val first = GroupParticipant("1".repeat(16), member, "phone", "session-one", "mic", false)
        engine.updateParticipants(listOf(first))
        val effects = engine.updateParticipants(listOf(first.copy(pid = "bad")))
        assertTrue(effects.contains(GroupCallEffect.Unsubscribe(first.trackRef()!!)))
        assertTrue(effects.contains(GroupCallEffect.CloseRoom))
        assertNull(engine.state.room)
    }

    @Test fun fifteenMinuteRoomRequiresExplicitConfirmation() {
        val engine = GroupCallEngine(roster, owner, { now })
        val invite = start().copy(expires = now + GroupCallCodec.MAX_LIFETIME_MS)
        engine.apply(invite, sender(owner))
        // Confirmation must be signaled by the authenticated host after the room actually joins.
        engine.confirmedRoom(invite.room)
        now += 15 * 60_000L + 1
        assertTrue(engine.tick().isEmpty())
        assertEquals(invite.room, engine.state.room)
        assertTrue(GroupCallEngine(roster, member, { now }).apply(invite, sender(owner)).isEmpty())
    }

    @Test fun restoredFenceBlocksReplayAndCapacityFailsClosed() {
        val engine = GroupCallEngine(roster, owner, { now })
        val end = start().copy(op = "group_end", joinSecret = null, eventId = "4".repeat(32))
        engine.apply(end, sender(owner))
        val restored = GroupCallEngine(roster, owner, { now }, engine.snapshot())
        assertTrue(restored.apply(start(), sender(owner)).isEmpty())
        assertTrue(restored.apply(end, sender(owner)).isEmpty())
        val full = GroupEventReplay(1)
        assertTrue(full.accept("first"))
        assertFalse(full.accept("second"))
    }

    @Test fun serverSecretHashVectorHashesUtf8HexText() {
        assertEquals("60e05bd1b195af2f94112fa7197a5c88289058840ce7c6df9693756bc6250f55",
            GroupCallCodec.secretHash("00".repeat(32)))
    }

    @Test fun shortEndExpiryCannotUnfenceStillLiveStart() {
        val engine = GroupCallEngine(roster, owner, { now })
        val end = start().copy(op = "group_end", joinSecret = null, eventId = "4".repeat(32), expires = now + 1000)
        engine.apply(end, sender(owner))
        now += 1001
        val fresh = start(id = "5".repeat(32), created = now - 500)
        assertTrue(engine.apply(fresh, sender(owner)).isEmpty())
    }

    @Test fun unknownRoomStatusUnsubscribesAndClosesConfirmedRoom() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(), sender(owner))
        engine.confirmedRoom("f".repeat(32))
        now += 60_001
        assertTrue(engine.tick().contains(GroupCallEffect.CloseRoom))
        val second = start(id = "5".repeat(32), created = now).copy(room = "f".repeat(32))
        engine.apply(second, sender(owner))
        engine.confirmedRoom(second.room)
        val ref = GroupParticipant("1".repeat(16), member, "phone", "session", "mic", false)
        engine.updateParticipants(listOf(ref))
        assertEquals(listOf(GroupCallEffect.Unsubscribe(ref.trackRef()!!), GroupCallEffect.CloseRoom), engine.roomStateUnknown())
    }

    @Test fun malformedSessionSnapshotStopsGhostPlayback() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(), sender(owner)); engine.confirmedRoom(room)
        val first = GroupParticipant("1".repeat(16), member, "phone", "session-one", "mic", false)
        engine.updateParticipants(listOf(first))
        val effects = engine.updateParticipants(listOf(first.copy(sessionId = "bad session")))
        assertEquals(listOf(GroupCallEffect.Unsubscribe(first.trackRef()!!), GroupCallEffect.CloseRoom), effects)
    }

    @Test fun activeSnapshotRequiresLiveRevalidationBeforeStoredInviteReopens() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(), sender(owner))
        val restored = GroupCallEngine(roster, owner, { now }, engine.snapshot())
        assertFalse(restored.apply(start().copy(eventId = "6".repeat(32)), sender(owner)).any { it is GroupCallEffect.OpenRoom })
    }

    @Test fun localMuteDeafenLeaveAndParticipantBound() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(), sender(owner))
        assertTrue(engine.mute(true).contains(GroupCallEffect.SetMicrophoneMuted(true)))
        assertTrue(engine.deafen(true).contains(GroupCallEffect.SetPlaybackDeafened(true)))
        // 18 entries no longer close the room (see eighteenParticipantsKeepFirstSeventeenWithoutClosing)
        val participants = (0..17).map { GroupParticipant(it.toString().padStart(16, '0'), member, "device$it", "session$it", "mic", false) }
        assertFalse(engine.updateParticipants(participants).contains(GroupCallEffect.CloseRoom))
        assertEquals(17, engine.state.participants.size)
        assertTrue(engine.leave().contains(GroupCallEffect.CloseRoom))
        assertTrue(engine.leave().isEmpty())
    }

    @Test fun serverPinnedSecretHashVectorMatchesProtocolMd() {
        // server/cloudflare/PROTOCOL.md: SHA-256 of the UTF-8 bytes of the 64-hex TEXT, not decoded bytes.
        assertEquals("c386d8e8d07342f2e39e189c8e6c57bb205bb373fe4e3a6f69404a8bb767b417",
            GroupCallCodec.secretHash("0".repeat(63) + "1"))
        val decoded = java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(32).also { it[31] = 1 })
            .joinToString("") { "%02x".format(it) }
        assertNotEquals(decoded, GroupCallCodec.secretHash("0".repeat(63) + "1"))
    }

    @Test fun errorMessagesNeverContainJoinSecret() {
        val bad = start().copy(room = "ABC")
        val messages = listOf(
            runCatching { GroupCallCodec.encode(bad) }.exceptionOrNull()?.toString(),
            runCatching { GroupCallCodec.secretHash(secret + "zz") }.exceptionOrNull()?.toString())
        assertTrue(messages.all { it != null && !it.contains(secret) })
        assertFalse(GroupDecodeResult.Accepted(start()).toString().contains(secret))
    }

    @Test fun endFenceExpiresAndSurvivesSnapshotButIsBoundedAtCapacity() {
        val engine = GroupCallEngine(roster, owner, { now })
        val end = start().copy(op = "group_end", joinSecret = null, eventId = "4".repeat(32))
        engine.apply(end, sender(owner))
        val snap = engine.snapshot()
        assertEquals(1, snap.ended.size)
        now += GroupCallCodec.MAX_LIFETIME_MS + 1
        val later = GroupCallEngine(roster, owner, { now }, snap)
        val fresh = start(id = "7".repeat(32), created = now)
        assertTrue(later.apply(fresh, sender(owner)).any { it is GroupCallEffect.OpenRoom })
        // capacity: a flood of distinct ended rooms never grows the fence past its bound
        val flood = GroupCallEngine(roster, owner, { now })
        repeat(400) { i ->
            val r = i.toString(16).padStart(32, '0')
            flood.apply(start(id = i.toString(16).padStart(32, 'a')).copy(op = "group_end", joinSecret = null, room = r), sender(owner))
        }
        assertTrue(flood.snapshot().ended.size <= 256)
        assertTrue(flood.snapshot().replayed.size <= 256)
    }

    @Test fun confirmedRoomOutlivesInviteButUnconfirmedAndUnknownDoNot() {
        val engine = GroupCallEngine(roster, owner, { now })
        val invite = start()
        engine.apply(invite, sender(owner)); engine.confirmedRoom(invite.room)
        now += 20 * 60_000L
        assertTrue(engine.tick().isEmpty()); assertEquals(invite.room, engine.state.room)
        // a NEW join with the expired invite is still refused
        assertTrue(GroupCallEngine(roster, member, { now }).apply(invite, sender(owner)).isEmpty())
        // confirmation for a different room does not keep this one
        val other = GroupCallEngine(roster, owner, { now - 20 * 60_000L })
        assertTrue(other.roomStateUnknown().isEmpty())
    }

    // ---- WP16 re-review blockers (third-context fix 2) ----
    private fun end(room: String, by: String, id: String, expires: Long = now + 60_000) = start(by = by).copy(
        op = "group_end", joinSecret = null, room = room, eventId = id, expires = expires)
    private fun hexOf(i: Int, prefix: Char) = prefix + i.toString(16).padStart(31, '0')
    private fun participant(i: Int, session: String = "session$i") =
        GroupParticipant(i.toString(16).padStart(16, '0'), member, "device$i", session, "mic", false)

    @Test fun backdatedStartCannotPreemptConfirmedRoom() {
        val engine = GroupCallEngine(roster, owner, { now })
        val a = start(id = "a".repeat(32))
        assertTrue(engine.apply(a, sender(owner)).any { it is GroupCallEffect.OpenRoom })
        engine.confirmedRoom(a.room)
        val b = start(by = member, id = "b".repeat(32), created = now - 1000).copy(room = "0".repeat(32), joinSecret = "9".repeat(64))
        assertTrue(engine.apply(b, sender(member)).isEmpty())
        assertEquals(a.room, engine.state.room)
    }

    @Test fun unconfirmedCallNotPreemptedOutsideTieWindowButInsideWindowLowerWins() {
        val engine = GroupCallEngine(roster, owner, { now })
        val a = start(id = "a".repeat(32))
        engine.apply(a, sender(owner))
        val far = start(by = member, id = "b".repeat(32), created = now - 20_000).copy(room = "0".repeat(32))
        assertTrue(engine.apply(far, sender(member)).isEmpty())
        assertEquals(a.room, engine.state.room)
        val near = start(by = member, id = "c".repeat(32), created = now - 1_000).copy(room = "0".repeat(32))
        assertTrue(engine.apply(near, sender(member)).any { it is GroupCallEffect.OpenRoom })
        assertEquals(near.room, engine.state.room)
    }

    @Test fun floodOfEndsForRandomRoomsCannotBlockLaterStartOrActiveEnd() {
        val engine = GroupCallEngine(roster, owner, { now })
        repeat(300) { i -> engine.apply(end(hexOf(i, 'e'), member, hexOf(i, 'd')), sender(member)) }
        assertTrue(engine.snapshot().ended.size <= 256 && engine.snapshot().replayed.size <= 256)
        val s = start(id = "a".repeat(32))
        assertTrue("start after flood", engine.apply(s, sender(owner)).any { it is GroupCallEffect.OpenRoom })
        repeat(300) { i -> engine.apply(end(hexOf(i, '7'), member, hexOf(i, '6')), sender(member)) }
        assertTrue("end of active room after flood",
            engine.apply(end(s.room, owner, "5".repeat(32)), sender(owner)).contains(GroupCallEffect.CloseRoom))
        assertNull(engine.state.room)
    }

    @Test fun restoredFullSnapshotOfForeignEntriesNeitherBlocksStartNorActiveEnd() {
        val far = now + 60_000
        val full = GroupCallFenceSnapshot(
            (0 until 256).associate { (hexOf(it, 'e') to "c".repeat(64)) to far },
            (0 until 256).associate { hexOf(it, 'd') to far }, null)
        val engine = GroupCallEngine(roster, owner, { now }, full)
        val s = start(id = "a".repeat(32))
        assertTrue(engine.apply(s, sender(owner)).any { it is GroupCallEffect.OpenRoom })
        assertTrue(engine.apply(end(s.room, owner, "5".repeat(32)), sender(owner)).contains(GroupCallEffect.CloseRoom))
    }

    @Test fun rejectedEventDoesNotBurnReplaySlot() {
        val engine = GroupCallEngine(roster, owner, { now })
        engine.apply(start(id = "a".repeat(32)), sender(owner))
        val bogus = end(room, member, "b".repeat(32)) // non-starter end of active room: rejected
        assertTrue(engine.apply(bogus, sender(member)).isEmpty())
        assertFalse("b".repeat(32) in engine.snapshot().replayed)
    }

    @Test fun restoreOf5000EntriesNeverThrowsAndIsBounded() {
        val far = now + 1_000
        val big = GroupCallFenceSnapshot(
            (0 until 5000).associate { (hexOf(it, 'e') to (if (it % 2 == 0) member else owner)) to far },
            (0 until 5000).associate { hexOf(it, 'd') to far }, null)
        val engine = GroupCallEngine(roster, owner, { now }, big)
        assertTrue(engine.snapshot().ended.size <= 256 && engine.snapshot().replayed.size <= 256)
        assertTrue(engine.apply(start(id = "a".repeat(32)), sender(owner)).any { it is GroupCallEffect.OpenRoom })
    }

    @Test fun restoreClampsHugeExpiryDropsMalformedKeysAndBadActiveRoom() {
        val fenced = "9".repeat(32)
        val snap = GroupCallFenceSnapshot(
            mapOf((fenced to owner) to Long.MAX_VALUE, ("ABC" to owner) to now + 1000, (fenced to "short") to now + 1000,
                ("8".repeat(32) to owner) to Long.MIN_VALUE),
            mapOf("7".repeat(32) to Long.MAX_VALUE, "not-hex" to now + 1000), "bad" to "worse")
        val engine = GroupCallEngine(roster, owner, { now }, snap)
        val out = engine.snapshot()
        assertEquals(setOf(fenced to owner), out.ended.keys)
        assertTrue(out.ended.values.all { it <= now + GroupCallCodec.MAX_LIFETIME_MS })
        assertEquals(setOf("7".repeat(32)), out.replayed.keys)
        assertTrue(out.replayed.values.all { it <= now + GroupCallCodec.MAX_LIFETIME_MS })
        assertNull(out.activeRoom)
        assertTrue(engine.apply(start(id = "a".repeat(32)), sender(owner)).any { it is GroupCallEffect.OpenRoom })
        now += GroupCallCodec.MAX_LIFETIME_MS + 1
        val later = GroupCallEngine(roster, owner, { now }, out) // the clamped snapshot, re-restored after the clamp elapsed
        assertTrue(later.apply(start(id = "b".repeat(32)).copy(room = fenced), sender(owner)).any { it is GroupCallEffect.OpenRoom })
    }

    @Test fun eighteenParticipantsKeepFirstSeventeenWithoutClosing() {
        val engine = GroupCallEngine(roster, owner, { now })
        val s = start(); engine.apply(s, sender(owner)); engine.confirmedRoom(s.room)
        val effects = engine.updateParticipants((0..17).map { participant(it) })
        assertFalse(effects.contains(GroupCallEffect.CloseRoom))
        assertEquals((0..16).map { participant(it).trackRef() }.toSet(), effects.filterIsInstance<GroupCallEffect.Subscribe>().map { it.ref }.toSet())
        assertEquals(17, engine.state.participants.size)
        assertEquals(s.room, engine.state.room)
    }

    @Test fun oneMalformedSessionIsDroppedOthersStaySubscribed() {
        val engine = GroupCallEngine(roster, owner, { now })
        val s = start(); engine.apply(s, sender(owner)); engine.confirmedRoom(s.room)
        val effects = engine.updateParticipants(listOf(participant(1), participant(2, "bad session"), participant(3)))
        assertFalse(effects.contains(GroupCallEffect.CloseRoom))
        assertEquals(setOf(participant(1).trackRef(), participant(3).trackRef()),
            effects.filterIsInstance<GroupCallEffect.Subscribe>().map { it.ref }.toSet())
        assertEquals(listOf(1, 3).map { participant(it).pid }, engine.state.participants.map { it.pid })
    }

    @Test fun subscribeIsGatedOnHostConfirmation() {
        val engine = GroupCallEngine(roster, owner, { now })
        val s = start(); engine.apply(s, sender(owner))
        assertTrue(engine.updateParticipants(listOf(participant(1))).none { it is GroupCallEffect.Subscribe })
        val confirmation: Any = engine.confirmedRoom(s.room)
        assertEquals(listOf(GroupCallEffect.Subscribe(participant(1).trackRef()!!)), confirmation)
    }

    @Test fun confirmationForWrongOrExpiredRoomHasNoEffect() {
        val engine = GroupCallEngine(roster, owner, { now })
        val s = start(); engine.apply(s, sender(owner))
        engine.updateParticipants(listOf(participant(1)))
        val wrong: Any = engine.confirmedRoom("f".repeat(32))
        assertEquals(emptyList<GroupCallEffect>(), wrong)
        now += 60_001
        val late: Any = engine.confirmedRoom(s.room)
        assertEquals(emptyList<GroupCallEffect>(), late)
        assertTrue(engine.tick().contains(GroupCallEffect.CloseRoom))
    }

    @Test fun securityLabelIsConstantNotACopyableField() {
        assertTrue(GroupCallState::class.java.declaredFields.none { it.name == "securityLabel" })
        assertEquals("Group calls are encrypted in transit, not end to end.", GroupCallState(room = "x").copy(muted = true).securityLabel)
    }

    @Test fun keyEpochIsRejectedUntilWp17() {
        val wire = GroupCallCodec.encode(start()).removeSuffix("}") + ",\"keyEpoch\":1}"
        assertEquals(GroupReject.MALFORMED, (receive(wire) as? GroupDecodeResult.Rejected)?.reason)
        assertThrows(IllegalArgumentException::class.java) { GroupCallCodec.encode(start().copy(keyEpoch = 1L)) }
        assertTrue(GroupCallEngine(roster, owner, { now }).apply(start().copy(keyEpoch = 1L), sender(owner)).isEmpty())
    }
}
