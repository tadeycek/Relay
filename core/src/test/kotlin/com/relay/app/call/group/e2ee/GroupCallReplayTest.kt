package com.relay.app.call.group.e2ee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCallReplayTest {
    private val owner = ParticipantDevice("a".repeat(64), "phone")
    private val member = ParticipantDevice("b".repeat(64), "desktop")

    @Test
    fun duplicateAndOldCountersAreRejected() {
        val window = FrameReplayWindow(8)
        assertEquals(ReplayDecision.ACCEPTED, window.admit(100))
        assertEquals(ReplayDecision.ACCEPTED, window.admit(102))
        assertEquals(ReplayDecision.ACCEPTED, window.admit(101)) // reorder inside window
        assertEquals(ReplayDecision.DUPLICATE, window.admit(101))
        assertEquals(ReplayDecision.DUPLICATE, window.admit(100))
        assertEquals(ReplayDecision.TOO_OLD, window.admit(92))
        assertEquals(ReplayDecision.ACCEPTED, window.admit(109)) // jump clears the window
        assertEquals(ReplayDecision.TOO_OLD, window.admit(100))
        assertEquals(ReplayDecision.INVALID, window.admit(-1))
    }

    @Test
    fun sequenceWrapIsExtendedAndReplayAcrossWrapRejected() {
        val guard = GroupCallReplayGuard()
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(member, 0, 77L, 65534))
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(member, 0, 77L, 65535))
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(member, 0, 77L, 0)) // wraps to 65536
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(member, 0, 77L, 1))
        assertEquals(ReplayDecision.DUPLICATE, guard.admit(member, 0, 77L, 65535))
        assertEquals(ReplayDecision.DUPLICATE, guard.admit(member, 0, 77L, 0))
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(member, 0, 77L, 65500)) // late but unseen
        assertEquals(ReplayDecision.DUPLICATE, guard.admit(member, 0, 77L, 65500))
        assertEquals(ReplayDecision.TOO_OLD, guard.admit(member, 0, 77L, 64000))
    }

    @Test
    fun sendersEpochsAndStreamsDoNotShareWindows() {
        val guard = GroupCallReplayGuard(maxStreams = 4)
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(member, 0, 1L, 500))
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(member, 1, 1L, 500))
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(owner, 0, 1L, 500))
        assertEquals(ReplayDecision.ACCEPTED, guard.admit(member, 0, 2L, 500))
        assertEquals(ReplayDecision.STREAM_LIMIT, guard.admit(member, 0, 3L, 500))
        guard.removeEpochsBelow(1)
        assertEquals(1, guard.streamCount())
        guard.clear()
        assertEquals(0, guard.streamCount())
    }

    @Test
    fun securityLabelRequiresEveryDeviceAtTheCurrentEpoch() {
        val status = GroupCallE2eeStatus.forEpoch(0, listOf(owner, member))
        assertEquals(GroupCallSecurityLabel.IN_TRANSIT,
            status.label(allParticipantsCapable = true, keyDistributionAuthenticated = true, sendPathEncrypted = true))
        assertTrue(status.verify(member, 0))
        status.verify(owner, 1) // stale epoch ignored
        val coverage = status.coverage(true, true, true)
        assertEquals(1, coverage.verifiedEpochs.size)
        assertEquals(GroupCallSecurityLabel.IN_TRANSIT, GroupCallSecurityLabel.label(coverage))

        assertTrue(status.verify(owner, 0))
        assertEquals(GroupCallSecurityLabel.END_TO_END,
            status.label(allParticipantsCapable = true, keyDistributionAuthenticated = true, sendPathEncrypted = true))
        assertEquals(GroupCallSecurityLabel.IN_TRANSIT,
            status.label(allParticipantsCapable = false, keyDistributionAuthenticated = true, sendPathEncrypted = true))
        assertEquals(GroupCallSecurityLabel.IN_TRANSIT,
            status.label(allParticipantsCapable = true, keyDistributionAuthenticated = false, sendPathEncrypted = true))
        assertEquals(GroupCallSecurityLabel.IN_TRANSIT,
            status.label(allParticipantsCapable = true, keyDistributionAuthenticated = true, sendPathEncrypted = false))
    }
}
