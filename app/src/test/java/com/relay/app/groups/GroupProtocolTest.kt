package com.relay.app.groups

import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.groups.GroupProtocol.Verdict
import com.relay.app.transport.PayloadCodec
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupProtocolTest {
    private val owner = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)
    private val stranger = "d".repeat(64)
    private val inner = "A".repeat(160)
    private val state = GroupProtocol.State(UUID.randomUUID().toString(), owner, "Friends", 1,
        listOf(GroupProtocol.Member(owner, "Owner"), GroupProtocol.Member(alice, "Alice", inner, listOf("wss://relay.example")),
            GroupProtocol.Member(bob, "Bob", inner)))
    private fun event(kind: String = "post", author: String = alice, from: GroupProtocol.State = state, text: String = "hello") =
        GroupProtocol.Event(kind, GroupProtocol.ref(from), UUID.randomUUID().toString(), author, text, 12345)
    private fun realTinkKey(): String {
        com.google.crypto.tink.config.TinkConfig.register()
        val keys = com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"))
        val out = java.io.ByteArrayOutputStream()
        keys.publicKeysetHandle.writeNoSecret(com.google.crypto.tink.BinaryKeysetWriter.withOutputStream(out))
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test fun rostersAndEventsRoundTripWithoutLosingUnicodeOrQuotes() {
        val invite = GroupProtocol.Sync(state)
        assertEquals(invite, GroupProtocol.parse(GroupProtocol.format(invite)))
        val post = event(text = "hello 😄 | \"hello\"\nworld")
        assertEquals(post, GroupProtocol.parse(GroupProtocol.format(post)))
    }
    @Test fun eventsNameTheGroupVersionButNeverCarryTheRoster() {
        val wire = GroupProtocol.format(event())
        assertFalse(wire.contains("members")); assertFalse(wire.contains("Friends")); assertFalse(wire.contains(inner))
    }
    @Test fun rostersSavedBeforeDirectDeliveryStillLoad() {
        // Exactly what 1.9 stored in shared_groups.state.
        val v1 = GroupProtocol.PREFIX + org.json.JSONObject().put("v", 1).put("kind", "sync").put("id", state.id).put("owner", owner)
            .put("name", "Friends").put("revision", 3).put("members", org.json.JSONArray()
                .put(org.json.JSONObject().put("key", owner).put("name", "Owner")).put(org.json.JSONObject().put("key", alice).put("name", "Alice")))
            .put("event", "").put("author", "").put("text", "").put("time", 0).toString()
        val parsed = GroupProtocol.parse(v1) as GroupProtocol.Sync
        assertEquals(3, parsed.state.revision)
        assertEquals(listOf(GroupProtocol.Member(owner, "Owner"), GroupProtocol.Member(alice, "Alice")), parsed.state.members)
    }
    @Test fun creatorForwardedV1EventsAreNoLongerAccepted() {
        val v1Post = GroupProtocol.format(event()).replace("\"v\":2", "\"v\":1")
        assertNull(GroupProtocol.parse(v1Post))
        val submit = GroupProtocol.format(event()).replace("\"kind\":\"post\"", "\"kind\":\"submit\"")
        assertNull(GroupProtocol.parse(submit))
    }
    @Test fun onlyVerifiedActiveUnblockedFriendsWithAcceptedKeysCanBeAdded() {
        val friend = Contact(id = 1, name = "Alice", phone = "nostr:$alice", nostrPubkey = alice, qrVerified = true, publicKey = "key")
        assertTrue(GroupProtocol.eligible(friend))
        listOf(friend.copy(qrVerified = false), friend.copy(deletedAt = 1), friend.copy(trustLevel = ContactTrustLevel.BLOCKED),
            friend.copy(publicKey = null), friend.copy(pendingPublicKey = "changed"), friend.copy(nostrPubkey = null))
            .forEach { assertFalse(GroupProtocol.eligible(it)) }
    }
    @Test fun onlyTheCreatorCanAnnounceMembership() {
        assertTrue(GroupProtocol.acceptSync(owner, null, state))
        assertFalse(GroupProtocol.acceptSync(alice, null, state))
        assertFalse(GroupProtocol.acceptSync(bob, state, state.copy(revision = 2)))
        assertTrue(GroupProtocol.acceptSync(owner, state, state.copy(revision = 2, name = "New name")))
    }
    @Test fun rosterUpdatesNeverRollBackOrConflict() {
        val newer = state.copy(revision = 2, name = "New name")
        assertFalse(GroupProtocol.acceptSync(owner, newer, state))
        assertTrue(GroupProtocol.acceptSync(owner, state, state))
        assertFalse(GroupProtocol.acceptSync(owner, state, state.copy(name = "changed")))
        assertFalse(GroupProtocol.acceptSync(owner, state, state.copy(id = UUID.randomUUID().toString(), revision = 2)))
    }
    @Test fun anyMemberSendsStraightToEveryoneIncludingTheCreator() {
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(alice, state, event()))
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(bob, state, event(author = bob)))
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(owner, state, event(author = owner)))
    }
    @Test fun membersCannotImpersonateEachOther() {
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(bob, state, event(author = alice)))
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(owner, state, event(author = alice)))
    }
    @Test fun nonMembersAndOtherGroupsAreRejected() {
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(stranger, state, event(author = stranger)))
        val other = state.copy(id = UUID.randomUUID().toString())
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(alice, state, event(from = other)))
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(alice, state, event(from = state.copy(owner = bob))))
    }
    @Test fun removedMemberIsRejectedEvenWithTheirOldValidRoster() {
        val removed = state.copy(revision = 2, members = state.members.filterNot { it.key == alice })
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(alice, removed, event(from = state)))
    }
    @Test fun delayedPostsSurviveLaterRosterChanges() {
        val newer = state.copy(revision = 5, name = "New name")
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(alice, newer, event(from = state)))
    }
    @Test fun aJustAddedMembersPostWaitsForTheRosterThatAddsThem() {
        val added = state.copy(revision = 2, members = state.members + GroupProtocol.Member(stranger, "Dana"))
        assertEquals(Verdict.LATER, GroupProtocol.checkEvent(stranger, state, event(author = stranger, from = added)))
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(stranger, added, event(author = stranger, from = added)))
        // A post that arrives before our own invitation waits for it too.
        assertEquals(Verdict.LATER, GroupProtocol.checkEvent(alice, null, event()))
        // Waiting needs a newer roster to be possible; impersonation is still rejected outright.
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(bob, state, event(author = stranger, from = added)))
    }
    @Test fun malformedOrOversizedPacketsNeverBecomeControlOperations() {
        val wire = GroupProtocol.format(event())
        assertNull(GroupProtocol.parse(wire.replace("\"v\":2", "\"v\":3")))
        assertNull(GroupProtocol.parse(wire + "x".repeat(10000)))
        assertNull(GroupProtocol.parse(GroupProtocol.PREFIX + "{}"))
        for (bad in listOf(state.copy(members = state.members + state.members.first()), state.copy(members = emptyList()),
            state.copy(members = state.members.drop(1)), state.copy(name = "bad\nname"), state.copy(revision = 0),
            state.copy(members = state.members.map { it.copy(inner = "not base64!") }),
            state.copy(members = state.members.map { it.copy(relays = listOf("ws://clearnet.example")) }),
            state.copy(members = state.members.map { it.copy(relays = listOf("wss://a.example", "wss://b.example", "wss://c.example")) }))) {
            assertTrue(runCatching { GroupProtocol.format(GroupProtocol.Sync(bad)) }.isFailure)
        }
        for (bad in listOf(event(kind = "submit"), event(author = "short"), event().copy(event = "not-a-uuid"), event().copy(time = 0), event(text = " "))) {
            assertTrue(runCatching { GroupProtocol.format(bad) }.isFailure)
        }
    }
    @Test fun fullGroupWithRealKeysAndRelayHintsStaysWithinEncryptedPayloadBudget() {
        val key = realTinkKey()
        assertTrue(key.length < 256)
        val longRelay = "wss://" + "r".repeat(84) + ".example"
        val dense = state.copy(name = "字".repeat(30), members = (0 until GroupProtocol.MAX_MEMBERS).map {
            GroupProtocol.Member(if (it == 0) owner else it.toString(16).padStart(64, '0'), "字".repeat(30), key, listOf(longRelay, longRelay.replace("rrr", "sss")))
        })
        val fitted = GroupProtocol.fitted(dense)
        val wire = GroupProtocol.format(GroupProtocol.Sync(fitted))
        assertEquals(GroupProtocol.Sync(fitted), GroupProtocol.parse(wire))
        // Relay hints give way; members and their keys never do.
        assertEquals(dense.members.map { it.key to it.inner }, fitted.members.map { it.key to it.inner })
        assertTrue((wire.toByteArray().size + 256) * 4 / 3 + 40 < PayloadCodec.MAX_BODY_LENGTH)
        val post = GroupProtocol.format(event(text = "字".repeat(1000), from = dense))
        assertTrue((post.toByteArray().size + 256) * 4 / 3 + 40 < PayloadCodec.MAX_BODY_LENGTH)
        assertTrue(runCatching { GroupProtocol.format(GroupProtocol.Sync(dense.copy(members = dense.members + GroupProtocol.Member(bob, "Extra")))) }.isFailure)
    }
    @Test fun aRosterThatFitsKeepsAllItsRelayHints() {
        assertEquals(state, GroupProtocol.fitted(state))
    }
    @Test fun relayHintsAreBoundedAndOnlyUseAcceptableRelays() {
        assertEquals(listOf("wss://a.example", "wss://b.example"),
            GroupProtocol.relayHints(listOf("ws://clearnet.example", "wss://a.example", "wss://a.example", "wss://b.example", "wss://c.example")))
        assertEquals(emptyList<String>(), GroupProtocol.relayHints(listOf("wss://" + "x".repeat(100))))
    }
    @Test fun virtualMemberIdsAreStableAndCannotOverlapRealDatabaseIds() {
        assertTrue(GroupProtocol.memberId(owner) < 0)
        assertEquals(GroupProtocol.memberId(owner), GroupProtocol.memberId(owner))
        assertNotEquals(GroupProtocol.memberId(owner), GroupProtocol.memberId(alice))
    }
    @Test fun deleteRequestsAreBoundToTheirAuthenticatedAuthor() {
        val request = event("delete", text = UUID.randomUUID().toString())
        assertEquals(request, GroupProtocol.parse(GroupProtocol.format(request)))
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(alice, state, request))
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(bob, state, request))
        assertTrue(runCatching { GroupProtocol.format(request.copy(text = "../arbitrary-file")) }.isFailure)
        val removed = state.copy(revision = 2, members = state.members.filterNot { it.key == alice })
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(alice, removed, request))
    }
    @Test fun bulkErasureUsesAnAuthenticatedAuthorAndRejectsInvalidCutoffs() {
        val request = event("purge", text = "12345")
        assertEquals(request, GroupProtocol.parse(GroupProtocol.format(request)))
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(alice, state, request))
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(bob, state, request))
        for (bad in listOf("0", "-1", "not a timestamp")) assertTrue(runCatching { GroupProtocol.format(request.copy(text = bad)) }.isFailure)
    }
    @Test fun nameUpdatesComeFromTheMemberThemselves() {
        val update = event("profile", text = com.relay.app.media.ProfileNameBody.format(com.relay.app.media.ProfileNameBody.Update(2, "Alice B")))
        assertEquals(update, GroupProtocol.parse(GroupProtocol.format(update)))
        assertEquals(Verdict.ACCEPT, GroupProtocol.checkEvent(alice, state, update))
        assertEquals(Verdict.REJECT, GroupProtocol.checkEvent(owner, state, update))
        assertTrue(runCatching { GroupProtocol.format(update.copy(text = "Alice B")) }.isFailure)
    }
    @Test fun mediaAndQuotedRepliesTravelInsideGroupEvents() {
        val reply = com.relay.app.messaging.ReplyBody.withText(com.relay.app.messaging.ReplyBody.draft(
            com.relay.app.data.model.Message(id = 1, contactId = 1, body = "original"), "Alice"), "Reply caption")
        val media = com.relay.app.media.MediaBody.format(com.relay.app.media.MediaRef("https://example.com/a", "a".repeat(64),
            java.util.Base64.getEncoder().encodeToString(ByteArray(32)), "image/jpeg", 1024, reply))
        val parsed = GroupProtocol.parse(GroupProtocol.format(event(text = media))) as GroupProtocol.Event
        assertEquals("Reply caption", com.relay.app.messaging.ReplyBody.preview(com.relay.app.media.MediaBody.parse(parsed.text)!!.caption))
        assertTrue(runCatching { GroupProtocol.format(event(text = "TYPE:MEDIA|URL:file:///secret")) }.isFailure)
    }
}
