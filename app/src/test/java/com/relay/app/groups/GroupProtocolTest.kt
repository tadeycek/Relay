package com.relay.app.groups

import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.transport.PayloadCodec
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupProtocolTest {
    private val owner = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)
    private val state = GroupProtocol.State(UUID.randomUUID().toString(), owner, "Friends", 1,
        listOf(GroupProtocol.Member(owner, "Owner"), GroupProtocol.Member(alice, "Alice"), GroupProtocol.Member(bob, "Bob")))
    private fun post(kind: String = "post", author: String = alice) = GroupProtocol.Packet(kind, state, UUID.randomUUID().toString(), author, "hello", 12345)

    @Test fun invitationAndPostsRoundTripWithoutLosingUnicodeOrQuotes() {
        val invite = GroupProtocol.Packet("sync", state)
        assertEquals(invite, GroupProtocol.parse(GroupProtocol.format(invite)))
        val packet = post().copy(text = "hello 😄 | \"hello\"\nworld")
        assertEquals(packet, GroupProtocol.parse(GroupProtocol.format(packet)))
    }
    @Test fun onlyVerifiedActiveUnblockedFriendsWithAcceptedKeysCanBeAdded() {
        val friend = Contact(id = 1, name = "Alice", phone = "nostr:$alice", nostrPubkey = alice, qrVerified = true, publicKey = "key")
        assertTrue(GroupProtocol.eligible(friend))
        listOf(friend.copy(qrVerified = false), friend.copy(deletedAt = 1), friend.copy(trustLevel = ContactTrustLevel.BLOCKED),
            friend.copy(publicKey = null), friend.copy(pendingPublicKey = "changed"), friend.copy(nostrPubkey = null))
            .forEach { assertFalse(GroupProtocol.eligible(it)) }
    }
    @Test fun onlyCreatorCanAnnounceMembershipOrForwardAnotherPersonsPost() {
        assertTrue(GroupProtocol.acceptOwner(owner, null, post()))
        assertFalse(GroupProtocol.acceptOwner(alice, null, post()))
        assertFalse(GroupProtocol.acceptOwner(bob, state, GroupProtocol.Packet("sync", state.copy(revision = 2))))
    }
    @Test fun submissionsCannotImpersonateOtherMembersOrChangeTheRoster() {
        assertTrue(GroupProtocol.acceptSubmit(alice, state, post("submit")))
        assertFalse(GroupProtocol.acceptSubmit(bob, state, post("submit")))
        assertFalse(GroupProtocol.acceptSubmit(owner, state, post("submit", owner)))
        assertFalse(GroupProtocol.acceptSubmit(alice, state, post("submit").copy(state = state.copy(id = UUID.randomUUID().toString()))))
        assertFalse(GroupProtocol.acceptSubmit(alice, state, post("submit").copy(state = state.copy(revision = 2))))
    }
    @Test fun memberCanReplyWhileAnOlderRosterUpdateIsStillInFlight() {
        val updated = state.copy(revision = 2, name = "New name")
        assertTrue(GroupProtocol.acceptSubmit(alice, updated, post("submit")))
        // The creator forwards its current state, never a submitter's proposed roster.
        val forwarded = post("submit").copy(kind = "post", state = updated)
        assertTrue(GroupProtocol.acceptOwner(owner, state, forwarded))
    }
    @Test fun removedMemberCannotSubmitEvenWithAnOldValidRoster() {
        val removed = state.copy(revision = 2, members = state.members.filterNot { it.key == alice })
        assertFalse(GroupProtocol.acceptSubmit(alice, removed, post("submit")))
        assertFalse(GroupProtocol.acceptOwner(owner, removed, GroupProtocol.Packet("sync", state)))
    }
    @Test fun delayedPostsSurviveRosterChangesWithoutRollingBackMembership() {
        val newer = state.copy(revision = 2, name = "New name")
        assertTrue(GroupProtocol.acceptOwner(owner, newer, post()))
        assertFalse(GroupProtocol.acceptOwner(owner, newer, GroupProtocol.Packet("sync", state)))
        val removed = newer.copy(members = newer.members.filterNot { it.key == alice })
        assertFalse(GroupProtocol.acceptOwner(owner, removed, post()))
    }
    @Test fun conflictingSameRevisionAndCrossGroupUpdatesAreRejected() {
        assertFalse(GroupProtocol.acceptOwner(owner, state, GroupProtocol.Packet("sync", state.copy(name = "changed"))))
        assertFalse(GroupProtocol.acceptOwner(owner, state, post().copy(state = state.copy(id = UUID.randomUUID().toString()))))
    }
    @Test fun malformedOrOversizedPacketsNeverBecomeControlOperations() {
        val wire = GroupProtocol.format(post())
        assertNull(GroupProtocol.parse(wire.replace("\"v\":1", "\"v\":2")))
        assertNull(GroupProtocol.parse(wire + "x".repeat(10000)))
        assertNull(GroupProtocol.parse(GroupProtocol.PREFIX + "{}"))
        for (bad in listOf(state.copy(members = state.members + state.members.first()), state.copy(members = emptyList()),
            state.copy(members = state.members.drop(1)), state.copy(name = "bad\nname"), state.copy(revision = 0))) {
            assertTrue(runCatching { GroupProtocol.format(GroupProtocol.Packet("sync", bad)) }.isFailure)
        }
    }
    @Test fun fullGroupWithUnicodeStaysWithinEncryptedPayloadBudget() {
        val dense = state.copy(members = (0 until GroupProtocol.MAX_MEMBERS).map { GroupProtocol.Member(if (it == 0) owner else it.toString(16).padStart(64, '0'), "字".repeat(30)) })
        val p = post().copy(state = dense, author = owner, text = "字".repeat(1000))
        val wire = GroupProtocol.format(p)
        assertEquals(p, GroupProtocol.parse(wire))
        assertTrue((wire.toByteArray().size + 256) * 4 / 3 + 40 < PayloadCodec.MAX_BODY_LENGTH)
        assertTrue(runCatching { GroupProtocol.format(GroupProtocol.Packet("sync", dense.copy(members = dense.members + GroupProtocol.Member(bob, "Extra")))) }.isFailure)
    }
    @Test fun virtualMemberIdsAreStableAndCannotOverlapRealDatabaseIds() {
        assertTrue(GroupProtocol.memberId(owner) < 0)
        assertEquals(GroupProtocol.memberId(owner), GroupProtocol.memberId(owner))
        assertNotEquals(GroupProtocol.memberId(owner), GroupProtocol.memberId(alice))
    }
    @Test fun deleteRequestsAreBoundToAuthenticatedAuthorsAndCreators() {
        val target = UUID.randomUUID().toString()
        val request = post("delete-submit").copy(text = target)
        assertEquals(request, GroupProtocol.parse(GroupProtocol.format(request)))
        assertTrue(GroupProtocol.acceptSubmit(alice, state, request))
        assertFalse(GroupProtocol.acceptSubmit(bob, state, request))
        val forwarded = request.copy(kind = "delete")
        assertTrue(GroupProtocol.acceptOwner(owner, state, forwarded))
        assertFalse(GroupProtocol.acceptOwner(bob, state, forwarded))
        assertTrue(runCatching { GroupProtocol.format(request.copy(text = "../arbitrary-file")) }.isFailure)
        val removed = state.copy(revision = 2, members = state.members.filterNot { it.key == alice })
        assertFalse(GroupProtocol.acceptSubmit(alice, removed, request))
    }
    @Test fun mediaAndQuotedRepliesTravelInsideGroupPackets() {
        val reply = com.relay.app.messaging.ReplyBody.withText(com.relay.app.messaging.ReplyBody.draft(
            com.relay.app.data.model.Message(id = 1, contactId = 1, body = "original"), "Alice"), "Reply caption")
        val media = com.relay.app.media.MediaBody.format(com.relay.app.media.MediaRef("https://example.com/a", "a".repeat(64),
            java.util.Base64.getEncoder().encodeToString(ByteArray(32)), "image/jpeg", 1024, reply))
        val packet = post().copy(text = media)
        val parsed = GroupProtocol.parse(GroupProtocol.format(packet))!!
        assertEquals("Reply caption", com.relay.app.messaging.ReplyBody.preview(com.relay.app.media.MediaBody.parse(parsed.text)!!.caption))
        assertTrue(runCatching { GroupProtocol.format(post().copy(text = "TYPE:MEDIA|URL:file:///secret")) }.isFailure)
    }

    @Test fun bulkErasureUsesAnAuthenticatedAuthorAndRejectsInvalidCutoffs() {
        val request = post("purge-submit").copy(text = "12345")
        assertEquals(request, GroupProtocol.parse(GroupProtocol.format(request)))
        assertTrue(GroupProtocol.acceptSubmit(alice, state, request))
        assertFalse(GroupProtocol.acceptSubmit(bob, state, request))
        assertTrue(GroupProtocol.acceptOwner(owner, state, request.copy(kind = "purge")))
        for (bad in listOf("0", "-1", "not a timestamp")) assertTrue(runCatching { GroupProtocol.format(request.copy(text = bad)) }.isFailure)
    }

}
