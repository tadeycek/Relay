package com.relay.app.desktop

import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallState
import com.relay.app.desktop.GroupCallHarness.Companion.A
import com.relay.app.desktop.GroupCallHarness.Companion.B
import com.relay.app.desktop.GroupCallHarness.Companion.C
import com.relay.app.desktop.GroupCallHarness.Companion.OUT
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP16 (1): group_start / group_end ingress through the core codec/engine, authenticated sender, persistence, no stale banners. */
class DesktopGroupCallIngressTest {
    private val h = GroupCallHarness()
    @After fun close() = h.close()

    @Test fun validStartFromAMemberShowsABannerAndCommitsTheSnapshot() {
        val svc = h.service()
        val start = h.startBy(B)
        assertTrue(svc.ingress(B, h.frame(start)))
        val banner = svc.state.value.banners[h.conv]
        assertNotNull(banner); assertEquals(start.room, banner!!.room); assertEquals(B, banner.starter)
        val snap = h.store.data[h.conv]!!
        assertTrue("replay id persisted with the accepted event", start.eventId in snap.replayed)
        assertEquals(1, h.store.commits)
    }

    @Test fun senderComesFromTheSealNotFromTheBody() {
        val svc = h.service()
        // The body claims caller=B, but the authenticated seal sender is C: forged, must not be accepted.
        assertTrue(svc.ingress(C, h.frame(h.startBy(B))))
        assertTrue(svc.state.value.banners.isEmpty())
        assertEquals(0, h.store.commits)
    }

    @Test fun aNonMemberSenderIsRejectedEvenWhenTheBodyNamesItself() {
        val svc = h.service()
        val forged = h.startBy(OUT) // roster check inside the codec: OUT is not a member
        assertTrue(svc.ingress(OUT, h.frame(forged)))
        assertTrue(svc.state.value.banners.isEmpty())
    }

    @Test fun anAuthenticatedMemberCannotEndSomeoneElsesRoom() {
        val svc = h.service()
        val start = h.startBy(B)
        svc.ingress(B, h.frame(start))
        val end = GroupCallCodec.newEnd(start.copy(caller = C), h.now, h.deterministicRandom)
        svc.ingress(C, h.frame(end))
        assertNotNull("only the starter may end the room", svc.state.value.banners[h.conv])
    }

    @Test fun nonGroupCallBodiesAreNotConsumedAndMalformedGroupBodiesAreDroppedQuietly() {
        val svc = h.service()
        assertFalse(svc.ingress(B, "TYPE:CALL|J:" + java.util.Base64.getEncoder().encodeToString("""{"v":1,"op":"invite"}""".toByteArray())))
        assertFalse(svc.ingress(B, "hello"))
        val junk = "TYPE:CALL|J:" + java.util.Base64.getEncoder().encodeToString("""{"v":1,"op":"group_start","x":1}""".toByteArray())
        assertTrue("a group op is consumed even when malformed", svc.ingress(B, junk))
        assertTrue(svc.state.value.banners.isEmpty()); assertEquals(0, h.store.commits)
    }

    @Test fun anExpiredAnnouncementIsNeverShownAsLive() {
        val svc = h.service()
        val old = h.startBy(B, at = h.now - 11 * 60_000L)
        assertTrue(svc.ingress(B, h.frame(old)))
        assertTrue(svc.state.value.banners.isEmpty())
    }

    @Test fun anUnconfirmedBannerClearsWhenItsInviteExpires() {
        val svc = h.service()
        svc.ingress(B, h.frame(h.startBy(B)))
        assertEquals(1, svc.state.value.banners.size)
        h.now += 10 * 60_000L + 1
        svc.tick()
        assertTrue("stale invite must not stay live", svc.state.value.banners.isEmpty())
    }

    @Test fun groupEndClearsTheBannerAndALaterReplayOfTheStartStaysDead() {
        val svc = h.service()
        val start = h.startBy(B)
        svc.ingress(B, h.frame(start))
        svc.ingress(B, h.frame(GroupCallCodec.newEnd(start, h.now + 1, h.deterministicRandom)))
        assertTrue(svc.state.value.banners.isEmpty())
        svc.ingress(B, h.frame(start)) // redelivered stored copy
        assertTrue(svc.state.value.banners.isEmpty())
    }

    @Test fun endBeforeStartFencesTheRoom() {
        val svc = h.service()
        val start = h.startBy(B)
        svc.ingress(B, h.frame(GroupCallCodec.newEnd(start, h.now + 1, h.deterministicRandom)))
        svc.ingress(B, h.frame(start))
        assertTrue(svc.state.value.banners.isEmpty())
    }

    @Test fun aRestartedClientRestoresTheJoinableBannerAndStillFencesAReplayedCopy() {
        val first = h.service()
        val start = h.startBy(B)
        first.ingress(B, h.frame(start))
        first.close()
        val second = h.service() // same durable store
        second.sync()
        assertEquals("a still-valid call is joinable again after a restart", start.room, second.state.value.banners[h.conv]?.room)
        second.ingress(B, h.frame(start))
        assertEquals("the persisted replay fence must not duplicate it", start.room, second.state.value.banners[h.conv]?.room)
        assertEquals(1, h.store.invites.size)
    }

    @Test fun aFailedCommitLeavesNoBannerAndTheEventIsNotBurnt() {
        val svc = h.service()
        val start = h.startBy(B)
        h.store.failNext = true
        try { svc.ingress(B, h.frame(start)); error("a failed commit must surface so the stored packet is retried") } catch (_: IllegalStateException) {}
        assertTrue(svc.state.value.banners.isEmpty())
        h.store.failNext = false
        assertTrue(svc.ingress(B, h.frame(start))) // the redelivery is accepted: nothing was recorded as seen
        assertEquals(start.room, svc.state.value.banners[h.conv]?.room)
    }

    @Test fun aStaleRosterRevisionOrUnknownGroupIsIgnored() {
        val svc = h.service()
        val stale = h.startBy(B, roster = h.stored(rev = 2).protocolState())
        svc.ingress(B, h.frame(stale))
        assertTrue(svc.state.value.banners.isEmpty())
        h.group = null
        svc.ingress(B, h.frame(h.startBy(B, roster = h.stored().protocolState())))
        assertTrue(svc.state.value.banners.isEmpty())
    }

    @Test fun aServerReadbackOfAGoneRoomClearsTheBanner() {
        val svc = h.service()
        val start = h.startBy(B)
        h.api.infoFor = { RoomInfoOutcome.Gone }
        svc.ingress(B, h.frame(start)); svc.sync()
        assertTrue(svc.state.value.banners.isEmpty())
    }

    @Test fun aServerReadbackWithADifferentRosterBindingClearsTheBanner() {
        val svc = h.service()
        val start = h.startBy(B)
        h.api.infoFor = { RoomInfoOutcome.Live(true, start.revision, "f".repeat(64), 1) }
        svc.ingress(B, h.frame(start)); svc.sync()
        assertTrue(svc.state.value.banners.isEmpty())
    }

    @Test fun theEncryptionLabelIsConstant() {
        assertEquals("Group calls are encrypted in transit, not end to end.", h.service().state.value.label)
        assertEquals(GroupCallState.SECURITY_LABEL, h.service().state.value.label)
    }

    @Test fun theStoreRoundTripsSnapshotsInsideTheEncryptedDatabase() {
        val dir = java.nio.file.Files.createTempDirectory("wp16-store")
        val store = DesktopMessageStore(dir.resolve("db.sqlite"), FileSecretStore(dir.resolve("secret")))
        try {
            val snap = com.relay.app.call.group.GroupCallFenceSnapshot(mapOf(("0".repeat(32) to B) to 9_999_999_999_999L), mapOf("1".repeat(32) to 9_999_999_999_999L), "2".repeat(32) to C)
            assertNull(store.snapshot("g"))
            store.commit("g", snap)
            assertEquals(snap, store.snapshot("g"))
            val start = h.startBy(B)
            store.commit("g", snap, StoredGroupCall(start, rejoin = true))
            assertEquals(start, store.invitations()["g"]?.announcement)
            assertEquals(true, store.invitations()["g"]?.rejoin)
            store.close()
            val raw = java.nio.file.Files.readAllBytes(dir.resolve("db.sqlite")).toString(Charsets.ISO_8859_1)
            assertFalse("snapshot is not stored in plaintext", raw.contains("2".repeat(32)))
            assertFalse("join secret is not stored in plaintext", raw.contains(start.joinSecret!!))
        } finally { runCatching { store.close() } }
    }
}
