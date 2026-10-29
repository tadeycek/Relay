package com.relay.app.calls.group

import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallEffect
import org.junit.Assert.*
import org.junit.Test

/** WP16 step 2 / AC: group_start/group_end ingress goes through core only after the seal sender is authenticated. */
class GroupCallIngressTest {
    private var now = G.NOW
    private fun coordinator(store: MemoryStore = MemoryStore(), self: String = G.carol, roster: com.relay.app.groups.GroupProtocol.State = G.roster()) =
        GroupCallCoordinator(roster, self, { now }, store)

    @Test fun wireRoundTripsAndRejectsNonGroupBodies() {
        val json = GroupCallCodec.encode(G.start())
        assertEquals(json, GroupCallWire.unwrap(GroupCallWire.wrap(json)))
        assertNull(GroupCallWire.unwrap("hello"))
        assertNull(GroupCallWire.unwrap("TYPE:CALL|J:" + java.util.Base64.getEncoder().encodeToString("""{"v":1,"op":"invite"}""".toByteArray())))
        assertNull(GroupCallWire.unwrap("TYPE:CALL|J:!!!notbase64"))
    }

    @Test fun startFromAuthenticatedMemberShowsBannerAndPersistsFenceAtomicallyWithIt() {
        val store = MemoryStore(); val c = coordinator(store)
        val a = G.start(G.bob)
        val r = c.onWire(G.bob, G.wire(a))
        assertTrue(r is IngressResult.Accepted)
        val accepted = r as IngressResult.Accepted
        assertTrue(accepted.effects.any { it is GroupCallEffect.OpenRoom && it.room == a.room })
        assertEquals(a.room, accepted.banner?.room)
        assertEquals(a.room, c.liveBanner()?.room)
        assertEquals(1, store.commits.size)
        assertTrue(a.eventId in store.commits.single().replayed)
    }

    @Test fun senderComesFromTheSealNeverFromTheBody() {
        val store = MemoryStore(); val c = coordinator(store)
        // Mallory is on the roster too and holds a valid seal, but the body claims Bob started the call.
        val roster = G.roster(members = listOf(G.alice, G.bob, G.carol, G.mallory))
        val c2 = coordinator(store, roster = roster)
        val r = c2.onWire(G.mallory, G.wire(G.start(G.bob, roster)))
        assertEquals(IngressResult.Rejected("UNAUTHENTICATED"), r)
        assertNull(c2.liveBanner()); assertTrue(store.commits.isEmpty())
        // A non-member with a valid seal claiming to be themselves is also refused.
        assertTrue(c.onWire(G.mallory, G.wire(G.start(G.mallory))) is IngressResult.Rejected)
        assertNull(c.liveBanner())
    }

    @Test fun staleRosterRevisionIsRefusedAndNeverShown() {
        val c = coordinator(roster = G.roster(revision = 2))
        assertEquals(IngressResult.Rejected("STALE_ROSTER"), c.onWire(G.bob, G.wire(G.start(G.bob, G.roster(revision = 1)))))
        assertNull(c.liveBanner())
    }

    @Test fun expiredAnnouncementIsNotShownAsLive() {
        val c = coordinator()
        val a = G.start(G.bob)
        now = a.expires + 1
        assertEquals(IngressResult.Rejected("EXPIRED"), c.onWire(G.bob, G.wire(a)))
        assertNull(c.liveBanner())
    }

    @Test fun unconfirmedBannerSelfClearsWhenTheInviteExpires() {
        val c = coordinator(); val a = G.start(G.bob)
        c.onWire(G.bob, G.wire(a))
        assertNotNull(c.liveBanner())
        now = a.expires + 1
        assertNull(c.liveBanner())
    }

    @Test fun endFromStarterClearsBannerAndStartCannotBeReplayed() {
        val store = MemoryStore(); val c = coordinator(store); val a = G.start(G.bob)
        c.onWire(G.bob, G.wire(a))
        val end = GroupCallCodec.newEnd(a, now + 1_000, G.rng(90))
        val r = c.onWire(G.bob, G.wire(end)) as IngressResult.Accepted
        assertTrue(r.effects.contains(GroupCallEffect.CloseRoom))
        assertNull(c.liveBanner())
        assertTrue(c.onWire(G.bob, G.wire(a)) is IngressResult.Rejected) // replay of the old start
    }

    @Test fun endFromAnotherMemberDoesNotClearTheBanner() {
        val c = coordinator(); val a = G.start(G.bob)
        c.onWire(G.bob, G.wire(a))
        val forged = GroupCallCodec.newEnd(a.copy(caller = G.carol), now + 1_000, G.rng(90))
        c.onWire(G.carol, G.wire(forged))
        assertNotNull(c.liveBanner())
    }

    @Test fun endArrivingBeforeStartFencesTheLateStart() {
        val c = coordinator(); val a = G.start(G.bob)
        val end = GroupCallCodec.newEnd(a, now + 1_000, G.rng(90))
        c.onWire(G.bob, G.wire(end))
        c.onWire(G.bob, G.wire(a))
        assertNull(c.liveBanner())
    }

    @Test fun fenceAndReplaySurviveRestartSoARedeliveredStartIsNotALiveCall() {
        val store = MemoryStore(); val a = G.start(G.bob)
        coordinator(store).onWire(G.bob, G.wire(a))
        val restarted = coordinator(store) // new process, same persisted snapshot
        assertNull("a stored invite must not be reopened after a restart", restarted.liveBanner())
        assertTrue(restarted.onWire(G.bob, G.wire(a)) is IngressResult.Rejected)
        assertNull(restarted.liveBanner())
    }

    @Test fun persistFailureFailsClosedThenARetryIsAccepted() {
        val store = MemoryStore(); val c = coordinator(store); val a = G.start(G.bob)
        store.failNext = true
        assertTrue(c.onWire(G.bob, G.wire(a)) is IngressResult.PersistFailed)
        assertNull("an event that could not be persisted must not show a call", c.liveBanner())
        val retry = c.onWire(G.bob, G.wire(a))
        assertTrue("redelivery after a failed commit is not a duplicate: $retry", retry is IngressResult.Accepted)
        assertNotNull(c.liveBanner())
    }

    @Test fun failedEndFencePersistenceClosesAnAlreadyOpenRoom() {
        val store = MemoryStore(); val c = coordinator(store); val a = G.start(G.bob)
        assertTrue(c.onWire(G.bob, G.wire(a)) is IngressResult.Accepted)
        store.failNext = true
        val end = GroupCallCodec.newEnd(a, now + 1_000, G.rng(90))
        val result = c.onWire(G.bob, G.wire(end)) as IngressResult.PersistFailed
        assertEquals(listOf(GroupCallEffect.CloseRoom), result.effects)
        assertNull(c.liveBanner())
    }

    @Test fun nonAnnouncementBodiesFallThroughToTheMessagePath() {
        val c = coordinator()
        assertEquals(IngressResult.NotAnnouncement, c.onWire(G.bob, "hello"))
        assertEquals(IngressResult.NotAnnouncement, c.onWire(G.bob, "TYPE:CALL|J:" + java.util.Base64.getEncoder().encodeToString("""{"v":1,"op":"invite"}""".toByteArray())))
    }

    @Test fun liveStateNotFoundClearsTheBannerAndFencesTheRoom() {
        val store = MemoryStore(); val c = coordinator(store); val a = G.start(G.bob)
        c.onWire(G.bob, G.wire(a))
        val effects = c.roomGone()
        assertTrue(effects.contains(GroupCallEffect.CloseRoom))
        assertNull(c.liveBanner())
        assertTrue(c.onWire(G.bob, G.wire(a)) is IngressResult.Rejected)
        assertTrue("the fence is persisted", store.commits.size >= 2)
    }
}
