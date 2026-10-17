package com.relay.app.groups

import com.relay.app.media.ProfileNameBody
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupProfilesTest {
    private val owner = "a".repeat(64)
    private val member = "b".repeat(64)
    private val other = "c".repeat(64)
    private val state = GroupProtocol.State(UUID.randomUUID().toString(), owner, "Group", 5,
        listOf(GroupProtocol.Member(owner, "Owner"), GroupProtocol.Member(member, "Old name"), GroupProtocol.Member(other, "Reader")))
    private fun packet(kind: String = "profile-submit") = GroupProtocol.Packet(kind, state, UUID.randomUUID().toString(), member,
        ProfileNameBody.format(ProfileNameBody.Update(7, "New name")), 123)

    @Test fun profileRoundTripDoesNotAlterMembership() {
        val p = packet()
        assertEquals(p, GroupProtocol.parse(GroupProtocol.format(p)))
        assertEquals(state, p.state)
        assertTrue(GroupProfiles.accepts(member, owner, state, p))
        assertFalse(GroupProtocol.acceptSubmit(member, state, p))
        assertFalse(GroupProtocol.acceptOwner(owner, state, p))
    }
    @Test fun spoofedOrRemovedAuthorsAndWrongGroupsAreRejected() {
        val p = packet()
        assertFalse(GroupProfiles.accepts(other, owner, state, p))
        assertFalse(GroupProfiles.accepts(member, other, state, p))
        assertFalse(GroupProfiles.accepts(member, owner, state.copy(members = state.members.filterNot { it.key == member }), p))
        assertFalse(GroupProfiles.accepts(member, owner, state.copy(id = UUID.randomUUID().toString()), p))
        assertFalse(GroupProfiles.accepts(member, owner, state.copy(owner = other), p))
    }
    @Test fun onlyOwnerCanForwardAndRosterRemainsLocallyAuthoritative() {
        val p = packet("profile")
        assertTrue(GroupProfiles.accepts(owner, other, state, p))
        assertFalse(GroupProfiles.accepts(member, other, state, p))
        // Even an older membership snapshot cannot replace the recipient's current roster.
        assertTrue(GroupProfiles.accepts(owner, other, state.copy(revision = 8), p))
        assertFalse(GroupProfiles.accepts(owner, other, state.copy(members = state.members.filterNot { it.key == member }), p))
    }
    @Test fun malformedProfileIsNotAGroupMessage() {
        assertThrows(IllegalArgumentException::class.java) { GroupProtocol.format(packet().copy(text = "new name")) }
    }
}
