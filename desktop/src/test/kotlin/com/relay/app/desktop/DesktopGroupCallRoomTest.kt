package com.relay.app.desktop

import com.relay.app.desktop.GroupCallHarness.Companion.A
import com.relay.app.desktop.GroupCallHarness.Companion.B
import com.relay.app.desktop.GroupCallHarness.Companion.C
import com.relay.app.desktop.GroupCallHarness.Companion.D
import com.relay.app.desktop.GroupCallHarness.Companion.OUT
import com.relay.app.desktop.GroupCallHarness.Companion.pid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP16 (2)+(3 controller side): the room socket, subscription diffing, reconnect, 4009, mute/deafen, leave and removal. */
class DesktopGroupCallRoomTest {
    private val h = GroupCallHarness()
    @After fun close() = h.close()

    @Test fun welcomeConfirmsTheRoomThenOpensMediaPublishesAndSubscribes() {
        val svc = h.service()
        val start = h.joinedCall(svc)
        assertEquals(start.room, h.api.last.room); assertEquals(start.joinSecret, h.api.last.secret); assertNull("first join has no pid", h.api.last.pid)
        assertEquals(listOf("open", "publish", "sub:${pid(1)}:sess-b"), h.media.calls.take(3))
        assertEquals(GroupCallStage.ACTIVE, svc.state.value.stage)
        assertEquals("live-state readback of exactly this room", setOf(start.room), h.api.infos.toSet())
        h.api.last.sent.clear()
        h.timers.advance(15_000); svc.sync()
        assertEquals(1, h.api.last.sent.count { it == """{"type":"heartbeat"}""" })
    }

    @Test fun theMicrophoneIsNotStartedBeforeTheRoomIsConfirmed() {
        val svc = h.service()
        val start = h.startBy(B)
        h.api.infoFor = { h.liveMatching(start) }
        svc.ingress(B, h.frame(start)); svc.join(h.conv)
        assertTrue("nothing native before the welcome + readback", h.medias.isEmpty())
        h.api.last.listener.onOpen()
        assertTrue(h.medias.isEmpty())
    }

    @Test fun aReadbackThatDoesNotMatchTheLocalRosterNeverConfirmsOrPublishes() {
        val svc = h.service()
        val start = h.startBy(B)
        h.api.infoFor = { h.liveMatching(start) }
        svc.ingress(B, h.frame(start)); svc.sync(); svc.join(h.conv)
        h.api.infoFor = { RoomInfoOutcome.Live(true, start.revision, "0".repeat(64), 1) } // the server answers differently at join time
        h.api.last.listener.onText(h.welcome(9, listOf(Triple(1, B, "sess-b"))))
        svc.sync()
        assertTrue(h.medias.isEmpty() || h.media.calls.none { it == "publish" || it.startsWith("sub:") })
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        assertEquals(1000, h.api.last.closed?.first)
        assertNotNull(svc.state.value.notice)
    }

    @Test fun joinedLeftAndStateFramesDriveTheSubscriptionDiff() {
        val svc = h.service()
        h.joinedCall(svc, others = listOf(Triple(1, B, "sess-b")))
        h.api.last.listener.onText(h.joined(2, C, "sess-c")); svc.sync()
        assertTrue(h.media.calls.contains("sub:${pid(2)}:sess-c"))
        h.api.last.listener.onText(h.left(1, B)); svc.sync()
        assertTrue(h.media.calls.contains("unsub:${pid(1)}:sess-b"))
        // a republish with a new session replaces the old subscription
        h.api.last.listener.onText(h.joined(2, C, "sess-c2")); svc.sync()
        assertTrue(h.media.calls.contains("unsub:${pid(2)}:sess-c") && h.media.calls.contains("sub:${pid(2)}:sess-c2"))
        h.api.last.listener.onText("""{"type":"state","pid":"${pid(2)}","muted":true,"deafened":false}"""); svc.sync()
        assertTrue(svc.state.value.people.flatMap { it.devices }.single { it.pid == pid(2) }.muted)
    }

    @Test fun participantsOutsideTheLocalRosterAreFlaggedAndNeverSubscribed() {
        val svc = h.service()
        h.joinedCall(svc, others = listOf(Triple(1, B, "sess-b"), Triple(2, OUT, "sess-x")))
        assertFalse(h.media.calls.contains("sub:${pid(2)}:sess-x"))
        val outsider = svc.state.value.people.single { it.pubkey == OUT }
        assertFalse(outsider.inRoster)
        assertTrue(svc.state.value.people.single { it.pubkey == B }.inRoster)
    }

    @Test fun deviceEntriesAreGroupedPerPerson() {
        val svc = h.service()
        h.joinedCall(svc, others = listOf(Triple(1, B, "sess-b1"), Triple(2, B, "sess-b2"), Triple(3, C, "sess-c")))
        val people = svc.state.value.people
        assertEquals(setOf(B, C), people.filter { !it.self }.map { it.pubkey }.toSet())
        assertEquals(2, people.single { it.pubkey == B }.devices.size)
    }

    @Test fun speakingIndicatorsComeFromTheReceiverLevelsAndSurviveOnlyWhileAudible() {
        val svc = h.service()
        h.joinedCall(svc, others = listOf(Triple(1, B, "sess-b"), Triple(2, C, "sess-c")))
        h.media.levelMap = mapOf(pid(1) to 0.4f, pid(2) to 0.0f)
        svc.tick()
        assertTrue(svc.state.value.people.single { it.pubkey == B }.speaking)
        assertFalse(svc.state.value.people.single { it.pubkey == C }.speaking)
    }

    @Test fun muteDeafenAndVolumeAreAppliedToMediaAndTheRoomHint() {
        val svc = h.service()
        h.joinedCall(svc)
        svc.setMuted(true); svc.setDeafened(true); svc.setVolume(pid(1), 0.5f)
        assertTrue(h.media.calls.containsAll(listOf("mic:true", "deaf:true", "vol:${pid(1)}:0.5")))
        assertTrue(h.api.last.sent.any { it.contains("\"state\"") && it.contains("\"muted\":true") && it.contains("\"deafened\":true") })
        assertTrue(svc.state.value.muted && svc.state.value.deafened)
        svc.setMuted(false)
        assertTrue(h.media.calls.contains("mic:false"))
    }


    @Test fun leaveSendsLeaveClosesTheSocketAndDisposesMedia() {
        val svc = h.service(); h.joinedCall(svc)
        svc.leave()
        assertTrue(h.api.last.sent.any { it.contains("\"leave\"") })
        assertEquals(1000, h.api.last.closed?.first)
        assertTrue(h.media.closed)
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        assertTrue("the call is still live for others: Join becomes Rejoin", svc.state.value.banners[h.conv]!!.rejoin)
    }

    @Test fun leavingAndRejoiningTheSameRoomWorksWhileItIsStillLive() {
        val svc = h.service()
        val start = h.joinedCall(svc)
        svc.leave()
        assertNull(svc.join(h.conv))
        assertEquals(start.room, h.api.last.room); assertEquals(start.joinSecret, h.api.last.secret)
        h.api.last.listener.onText(h.welcome(9, listOf(Triple(1, B, "sess-b")))); svc.sync()
        assertEquals(GroupCallStage.ACTIVE, svc.state.value.stage)
        assertEquals(2, h.medias.size); assertTrue(h.media.calls.contains("publish"))
    }

    @Test fun aGroupEndAfterLeavingRemovesTheRejoinBanner() {
        val svc = h.service()
        val start = h.joinedCall(svc)
        svc.leave()
        svc.ingress(B, h.frame(com.relay.app.call.group.GroupCallCodec.newEnd(start, h.now + 1, h.deterministicRandom)))
        assertTrue(svc.state.value.banners.isEmpty())
        assertNotNull(svc.join(h.conv))
    }

    @Test fun aFailedCommitOfAnUnrelatedEventDoesNotKillTheLiveCall() {
        val svc = h.service(); val start = h.joinedCall(svc)
        h.store.failNext = true
        val stray = com.relay.app.call.group.GroupCallCodec.newEnd(start.copy(room = "9".repeat(32)), h.now + 1, h.deterministicRandom)
        try { svc.ingress(B, h.frame(stray)) } catch (_: IllegalStateException) {}
        h.store.failNext = false
        assertEquals(GroupCallStage.ACTIVE, svc.state.value.stage)
        h.api.last.listener.onText(h.joined(2, C, "sess-c")); svc.sync()
        assertTrue("subscriptions still follow the engine", h.media.calls.contains("sub:${pid(2)}:sess-c"))
    }

    @Test fun aDroppedSocketReconnectsWithTheSamePidInsideTheGraceAndKeepsMedia() {
        val svc = h.service(); h.joinedCall(svc, you = 9)
        h.api.last.listener.onClosed(1006, "gone"); svc.sync()
        assertEquals(GroupCallStage.RECONNECTING, svc.state.value.stage)
        h.timers.advance(2_000); svc.sync()
        assertEquals(2, h.api.sockets.size)
        assertEquals(pid(9), h.api.last.pid)
        assertFalse(h.media.closed)
        h.api.last.listener.onOpen(); h.api.last.listener.onText(h.welcome(9, listOf(Triple(1, B, "sess-b")))); svc.sync()
        assertEquals(GroupCallStage.ACTIVE, svc.state.value.stage)
        assertEquals("not re-published on a reconnect", 1, h.media.calls.count { it == "publish" })
    }

    @Test fun reconnectGivesUpOnceTheGraceHasPassed() {
        val svc = h.service(graceMs = 30_000); h.joinedCall(svc)
        h.api.last.listener.onClosed(1006, "gone"); svc.sync()
        repeat(12) { h.now += 5_000; h.timers.advance(5_000); svc.sync() }
        h.timers.advance(5_000); svc.sync()
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        assertTrue(h.media.closed)
    }

    @Test fun closeCode4009MeansNoAutomaticReconnect() {
        val svc = h.service(); h.joinedCall(svc)
        h.api.last.listener.onClosed(4009, "removed"); svc.sync()
        h.timers.advance(120_000); svc.sync()
        assertEquals(1, h.api.sockets.size)
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        assertTrue(h.media.closed)
        val n = svc.state.value.notice
        assertTrue("notice was: $n", n != null && (n.contains("removed", ignoreCase = true) || n.contains("access", ignoreCase = true)))
    }

    @Test fun anEndFrameLeavesWithoutReconnectAndASupersedeSaysSo() {
        val svc = h.service(); h.joinedCall(svc)
        h.api.last.listener.onText("""{"type":"end","by":"$B","reason":"superseded"}"""); svc.sync()
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        h.timers.advance(60_000); svc.sync()
        assertEquals(1, h.api.sockets.size)
        assertTrue(h.media.closed)
    }

    @Test fun aRefusedReconnectUpgradeTearsTheCallDown() {
        val svc = h.service(); h.joinedCall(svc)
        h.api.last.listener.onClosed(1006, "x"); svc.sync(); h.timers.advance(2_000); svc.sync()
        h.api.last.listener.onFailure(404, "not_found"); svc.sync()
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        assertTrue(h.media.closed)
    }

    @Test fun aMediaFailureLeavesTheCall() {
        val svc = h.service(); h.joinedCall(svc)
        h.media.onEvent(GroupMediaEvent.Failed("ice")); svc.sync()
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        assertTrue(h.media.closed); assertTrue(h.api.last.sent.any { it.contains("\"leave\"") })
        assertNotNull(svc.state.value.notice)
    }

    @Test fun closingTheServiceTearsEverythingDownForExit() {
        val svc = h.service(); h.joinedCall(svc)
        svc.close()
        assertTrue(h.media.closed)
        assertTrue(h.api.last.sent.any { it.contains("\"leave\"") })
        assertNotNull(h.api.last.closed)
    }

    // ---- roster changes -----------------------------------------------------------------------

    @Test fun rosterRemovingMeEndsTheCallLocally() {
        val svc = h.service(); h.joinedCall(svc)
        val next = h.stored(keys = listOf(B, C), rev = 4)
        svc.onRoster(next); svc.sync()
        assertEquals(GroupCallStage.IDLE, svc.state.value.stage)
        assertTrue(h.media.closed)
    }

    @Test fun theCreatorRemovesTheMemberOnTheServerThenMovesRemainingMembersToANewRoomWithANewSecret() {
        h.group = h.stored(keys = listOf(A, B, C, D))
        val svc = h.service(); assertNull(svc.start(h.conv))
        val first = h.api.creates.single()
        svc.sync(); h.api.last.listener.onOpen(); h.api.infoFor = { RoomInfoOutcome.Live(true, 3, first.rosterHash, 1) }
        h.api.last.listener.onText(h.welcome(9, listOf(Triple(1, B, "sess-b"), Triple(2, D, "sess-d")))); svc.sync()
        h.announced.clear(); h.log.clear()
        // the owner removes D on the phone; the roster arrives by sync as revision 4
        h.group = h.stored(keys = listOf(A, B, C), rev = 4)
        svc.onRoster(h.group!!); svc.sync()
        val removal = h.api.removals.single()
        assertEquals("the previous room is closed before the replacement opens", listOf(first.room), h.api.ends)
        assertTrue(h.log.indexOf("end:${first.room}") < h.log.indexOf("create:${h.api.creates.last().room}"))
        assertEquals(first.room, removal.first); assertEquals(listOf(D), removal.second)
        assertNotNull("the old room's capability is sealed in the same call", removal.third)
        val second = h.api.creates.last()
        assertNotEquals(first.room, second.room); assertEquals(4L, second.revision); assertEquals(listOf(A, B, C), second.members)
        assertTrue("remove happens before the new room is announced", h.log.indexOf("remove:${first.room}") < h.log.indexOf("announce:b"))
        assertEquals("new secret is re-sent only to the remaining members", setOf(B, C), h.announced.map { it.first }.toSet())
        val newStart = GroupCallWire.decodeForTest(h.announced.first().second)
        assertEquals(second.room, newStart.room); assertEquals(4L, newStart.revision)
        assertEquals(com.relay.app.call.group.GroupCallCodec.secretHash(newStart.joinSecret!!), second.secretHash)
        assertEquals("it moves itself into the new room", second.room, h.api.last.room)
    }

    @Test fun aNonCreatorWhoseRevisionChangesWaitsAndRejoinsTheCreatorsNewRoom() {
        val svc = h.service()
        h.joinedCall(svc, caller = B)
        h.group = h.stored(keys = listOf(A, B, C), rev = 4)
        svc.onRoster(h.group!!); svc.sync()
        assertEquals("the old call is closed locally for the old revision", GroupCallStage.IDLE, svc.state.value.stage)
        assertTrue(h.media.closed); assertTrue(h.api.removals.isEmpty())
        // the creator announces the continuing call at the new revision: this client was in the call, so it rejoins
        val next = h.startBy(B)
        h.api.infoFor = { h.liveMatching(next) }
        svc.ingress(B, h.frame(next))
        assertEquals(next.room, h.api.last.room)
    }

    @Test fun anOutsiderWhoWasNeverInTheCallIsNotAutoJoinedAfterARevisionChange() {
        val svc = h.service()
        h.group = h.stored(keys = listOf(A, B, C), rev = 4)
        val next = h.startBy(B)
        svc.ingress(B, h.frame(next))
        assertTrue(h.api.sockets.isEmpty())
        assertEquals(next.room, svc.state.value.banners[h.conv]?.room)
    }
}
