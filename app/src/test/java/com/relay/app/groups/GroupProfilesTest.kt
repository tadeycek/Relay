package com.relay.app.groups

import com.relay.app.groups.GroupProtocol.Verdict
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
    private fun update(author: String = member, from: GroupProtocol.State = state) = GroupProtocol.Event("profile", GroupProtocol.ref(from),
        UUID.randomUUID().toString(), author, ProfileNameBody.format(ProfileNameBody.Update(7, "New name")), 123)

    @Test fun nameUpdateRoundTripsAndCannotCarryMembership() {
        val p = update()
        assertEquals(p, GroupProtocol.parse(GroupProtocol.format(p)))
        assertFalse(GroupProtocol.format(p).contains("members"))
    }
    @Test fun aMemberAnnouncesTheirOwnNameStraightToEveryone() {
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(member, state, update()))
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(owner, state, update(author = owner)))
    }
    @Test fun spoofedOrRemovedAuthorsAndWrongGroupsAreRejected() {
        // Nobody, the creator included, can rename someone else.
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(other, state, update()))
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(owner, state, update()))
        val removed = state.copy(revision = 6, members = state.members.filterNot { it.key == member })
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(member, removed, update()))
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(member, state, update(from = state.copy(id = UUID.randomUUID().toString()))))
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(member, state, update(from = state.copy(owner = other))))
    }
    @Test fun theReceiversRosterStaysAuthoritative() {
        // A name update naming a newer roster still only counts for someone already on ours.
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(member, state, update(from = state.copy(revision = 8))))
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(member, state.copy(revision = 8), update()))
    }
    @Test fun malformedProfileIsNotAGroupMessage() {
        assertThrows(IllegalArgumentException::class.java) { GroupProtocol.format(update().copy(text = "new name")) }
    }
}
