package com.relay.app.desktop

import com.relay.app.call.group.GroupCallCodec
import com.relay.app.desktop.GroupCallHarness.Companion.A
import com.relay.app.desktop.GroupCallHarness.Companion.B
import com.relay.app.desktop.GroupCallHarness.Companion.C
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP16 (2): starting a call from a group chat — roster-bound create, honest refusals, announce only after 200, end. */
class DesktopGroupCallStartTest {
    private val h = GroupCallHarness()
    @After fun close() = h.close()

    @Test fun createBodyIsRosterBoundWithSortedUniqueMembersAndTheStarter() {
        h.group = h.stored(keys = listOf(C, A, B)) // stored unsorted: the body must still be strictly ascending and unique
        val svc = h.service()
        assertNull(svc.start(h.conv))
        val req = h.api.creates.single()
        assertTrue(Regex("[0-9a-f]{32}").matches(req.room))
        assertEquals(listOf(A, B, C), req.members)
        assertEquals(GroupCallHarness.GROUP, req.group)
        assertEquals(3L, req.revision)
        assertEquals(GroupCallCodec.rosterHash(h.roster()), req.rosterHash)
        val announced = h.announced.map { it.second }.first()
        val secret = GroupCallWire.decodeForTest(announced).joinSecret!!
        assertTrue(Regex("[0-9a-f]{64}").matches(secret))
        assertEquals("secretHash is the hash of the 64-hex TEXT", GroupCallCodec.secretHash(secret), req.secretHash)
    }

    @Test fun roomAndSecretComeFromASecureRandomByDefault() {
        // Two default services must mint different values: a constant or seeded source would collide.
        fun mint(): Pair<String, String> {
            val hh = GroupCallHarness()
            val s = DesktopGroupCalls(
                selfPubkey = A, deviceId = "d", groupByConversation = { hh.group }, groupBySharedId = { _, _ -> hh.group },
                states = hh.store, api = hh.api, announce = { _, _ -> com.relay.app.transport.SendResult.Sent(1) }, online = { true },
                makeMedia = { hh.FakeMedia() }, nameOf = { it }, timers = hh.timers,
            )
            try { s.start(hh.conv); return hh.api.creates.single().room to hh.api.creates.single().secretHash } finally { s.close() }
        }
        assertNotEquals(mint(), mint())
    }

    @Test fun groupStartIsSentToEveryOtherMemberOnlyAfterTheServerAnswered200() {
        val svc = h.service()
        assertNull(svc.start(h.conv))
        val events = h.log.toList()
        assertEquals("create:${h.api.creates.single().room}", events.first())
        assertEquals(listOf("announce:b", "announce:c"), events.filter { it.startsWith("announce") })
        assertTrue(events.indexOf("announce:b") > events.indexOf(events.first()))
        assertEquals(setOf(B, C), h.announced.map { it.first }.toSet())
        // every body is a decodable, roster-bound start from this identity
        h.announced.forEach { (_, body) -> val a = GroupCallWire.decodeForTest(body); assertEquals("group_start", a.op); assertEquals(A, a.caller); assertEquals(h.api.creates.single().room, a.room) }
        assertEquals("then it joins the room it created", 1, h.api.sockets.size)
        assertEquals(h.api.creates.single().room, h.api.last.room)
    }

    @Test fun nothingIsAnnouncedWhenTheServerRefusesAndThereIsNeverALegacyRetry() {
        val outcomes = listOf(
            CreateRoomOutcome.StaleRevision to "changed",
            CreateRoomOutcome.NotInRoster to "not on this call's roster",
            CreateRoomOutcome.Refused(400, "roster_required", false) to "refused",
            CreateRoomOutcome.Unreachable to "reach",
        )
        for ((outcome, hint) in outcomes) {
            val hh = GroupCallHarness(); val svc = hh.service(); hh.api.createOutcome = outcome
            val reply = svc.start(hh.conv)
            assertNotNull("$outcome must be refused honestly", reply)
            assertTrue("$outcome: '$reply' should mention '$hint'", reply!!.contains(hint, ignoreCase = true))
            assertEquals("one create only: never a legacy fallback", 1, hh.api.creates.size)
            assertTrue(hh.announced.isEmpty()); assertTrue(hh.api.sockets.isEmpty())
            assertTrue(svc.state.value.stage == GroupCallStage.IDLE)
            hh.close()
        }
    }

    @Test fun staleRevisionAndNotInRosterAreDistinguished() {
        val a = h.service(); h.api.createOutcome = CreateRoomOutcome.StaleRevision
        val stale = a.start(h.conv)
        h.api.createOutcome = CreateRoomOutcome.NotInRoster
        val denied = a.start(h.conv)
        assertNotEquals(stale, denied)
    }

    @Test fun groupRoomExistsMeansJoinTheWinnersRoomOnceItsGroupStartArrives() {
        val svc = h.service()
        val winner = h.startBy(B)
        h.api.createOutcome = CreateRoomOutcome.GroupRoomExists(winner.room)
        h.api.infoFor = { h.liveMatching(winner) }
        val reply = svc.start(h.conv)
        assertNotNull(reply); assertTrue(reply!!.contains("already", ignoreCase = true))
        assertTrue("the loser sends no group_start", h.announced.isEmpty())
        assertTrue("and does not join its own discarded room", h.api.sockets.isEmpty())
        // the winner's announcement arrives: now the desktop joins THEIR room with THEIR secret
        svc.ingress(B, h.frame(winner))
        assertEquals(1, h.api.sockets.size)
        assertEquals(winner.room, h.api.last.room); assertEquals(winner.joinSecret, h.api.last.secret)
    }

    @Test fun aWinnerRoomForADifferentRoomIsNotJoinedAutomatically() {
        val svc = h.service()
        h.api.createOutcome = CreateRoomOutcome.GroupRoomExists("9".repeat(32))
        svc.start(h.conv)
        svc.ingress(B, h.frame(h.startBy(B))) // a different room than the server named
        assertTrue(h.api.sockets.isEmpty())
    }

    @Test fun startIsRefusedWhileUnlinkedOfflineNotAMemberOrAlreadyLive() {
        h.linked = false
        assertTrue(h.service().start(h.conv)!!.contains("Link", ignoreCase = true))
        h.linked = true; h.online = false
        assertTrue(h.service().start(h.conv)!!.contains("offline", ignoreCase = true))
        h.online = true; h.group = h.stored(keys = listOf(B, C))
        assertNotNull(h.service().start(h.conv))
        h.group = h.stored()
        val svc = h.service()
        assertNull(svc.start(h.conv))
        assertNotNull("second call while live", svc.start(h.conv))
        assertEquals(1, h.api.creates.size)
        assertNotNull("unknown group is refused, not a crash", h.service().start("group:nope:nope"))
    }

    @Test fun startIsRefusedWhenAnotherMemberCallIsAlreadyLiveInThatGroup() {
        val svc = h.service()
        svc.ingress(B, h.frame(h.startBy(B)))
        val reply = svc.start(h.conv)
        assertTrue(reply!!.contains("Join", ignoreCase = true))
        assertTrue(h.api.creates.isEmpty())
    }

    @Test fun startRefusesAGroupLargerThanTheSeventeenParticipantCap() {
        h.group = h.stored(keys = (0 until 18).map { "%064x".format(it + 1) } + A)
        assertNotNull(h.service().start(h.conv))
        assertTrue(h.api.creates.isEmpty())
    }

    @Test fun theCreatorEndingTheCallEndsTheRoomThenAnnouncesGroupEnd() {
        val svc = h.service(); svc.start(h.conv)
        val room = h.api.creates.single().room
        h.api.infoFor = { RoomInfoOutcome.Live(true, 3, h.api.creates.single().rosterHash, 1) }
        h.api.last.listener.onOpen(); h.api.last.listener.onText(h.welcome(9, emptyList())); svc.sync()
        h.log.clear(); h.announced.clear()
        svc.endCall()
        assertEquals(listOf(room), h.api.ends)
        assertTrue(h.log.indexOf("end:$room") < h.log.indexOf("announce:b"))
        assertEquals(setOf(B, C), h.announced.map { it.first }.toSet())
        h.announced.forEach { assertEquals("group_end", GroupCallWire.decodeForTest(it.second).op) }
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        assertEquals(1, h.media.calls.count { it == "close" })
    }

    @Test fun aNonCreatorLeavingNeverEndsTheRoomForEveryone() {
        val svc = h.service()
        h.joinedCall(svc)
        svc.endCall() // not the creator: only a leave
        assertTrue(h.api.ends.isEmpty()); assertTrue(h.announced.isEmpty())
        assertTrue(h.api.last.sent.any { it.contains("\"leave\"") })
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
    }
}
