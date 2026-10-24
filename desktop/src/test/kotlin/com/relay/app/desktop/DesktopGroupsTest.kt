package com.relay.app.desktop

import com.relay.app.groups.GroupProtocol
import com.relay.app.transport.SendResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows

/**
 * Group behaviour on the desktop without a network: the real [GroupProtocol] codec, the real encrypted
 * store and a fake [GroupSender] that records every direct delivery. Every branch that changes what the
 * user sees (roster, membership, posts, held events, avatars) is asserted against stored state.
 */
class DesktopGroupsTest {

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().use { out ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, color)
        ImageIO.write(image, "jpeg", out)
        out.toByteArray()
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val own = "a".repeat(64)
    private val peerA = "b".repeat(64)
    private val peerB = "c".repeat(64)
    private var clock = 10_000L
    private var uuid = 0

    private class FakeSender : GroupSender {
        data class Delivery(val peer: String, val inner: String?, val relays: List<String>, val body: String)

        val deliveries = mutableListOf<Delivery>()
        var failFor = mutableSetOf<String>()
        var result: SendResult = SendResult.Sent(1)

        override suspend fun send(peer: String, inner: String?, relays: List<String>, body: String): SendResult {
            deliveries += Delivery(peer, inner, relays, body)
            return if (peer in failFor) SendResult.Failed("unreachable", retryable = true) else result
        }
    }

    private fun newStore(): DesktopMessageStore {
        val config = tmp.newFolder("config-${uuid}").toPath()
        val data = tmp.newFolder("data-${uuid}").toPath()
        return DesktopMessageStore(DesktopPaths(config, data).databaseFile, FileSecretStore(DesktopPaths(config, data).secretFile))
    }

    private fun fixture(store: DesktopMessageStore, sender: FakeSender = FakeSender()) = DesktopGroups(
        store = store,
        ownPubkey = own,
        ownName = { "Alex" },
        ownInner = { "OWNINNERKEYBASE64AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" },
        sender = sender,
        clock = { clock },
        newId = { "00000000-0000-4000-8000-%012d".format(++uuid) },
    )

    private fun members() = listOf(
        GroupMemberInput(peerA, "Bea", "AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLL", listOf("wss://relay.example")),
        GroupMemberInput(peerB, "Cy", "BBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMM", emptyList()),
    )

    private fun mirrored(store: DesktopMessageStore, owner: String = peerA): StoredGroup {
        val state = GroupProtocol.State("99999999-9999-4999-8999-999999999999", owner, "Design", 1, listOf(
            GroupProtocol.Member(owner, if (owner == own) "Alex" else "Bea"), GroupProtocol.Member(if (owner == own) peerA else own, if (owner == own) "Bea" else "Alex"), GroupProtocol.Member(peerB, "Cy")))
        store.saveGroupState(state)
        return store.storedGroup(owner, state.id)!!
    }

    @Test
    fun `unknown roster requires verified owner`() {
        newStore().use { store ->
            val groups = fixture(store)
            val state = GroupProtocol.State("99999999-9999-4999-8999-999999999999", peerA, "Invite", 1,
                listOf(GroupProtocol.Member(peerA, "Bea"), GroupProtocol.Member(own, "Alex")))
            assertTrue(groups.handle(peerA, GroupProtocol.format(GroupProtocol.Sync(state))))
            assertNull(store.storedGroup(peerA, state.id))
            seedContact(store, peerA, "Bea")
            assertTrue(groups.handle(peerA, GroupProtocol.format(GroupProtocol.Sync(state))))
            assertNotNull(store.storedGroup(peerA, state.id))
            seedContact(store, peerB, "Cy")
            val hijack = state.copy(owner = peerB, name = "Hijacked", revision = 9,
                members = listOf(GroupProtocol.Member(peerB, "Cy"), GroupProtocol.Member(own, "Alex")))
            assertTrue(groups.handle(peerB, GroupProtocol.format(GroupProtocol.Sync(hijack))))
            assertNull(store.storedGroup(peerB, state.id))
            assertEquals("Invite", store.storedGroup(peerA, state.id)!!.name)
        }
    }

    @Test
    fun `owner removal revision tombstones membership and blocks send`() = runBlocking<Unit> {
        newStore().use { store ->
            val groups = fixture(store)
            val initial = mirrored(store)
            val removed = GroupProtocol.State(initial.sharedId, peerA, "Design", 2,
                listOf(GroupProtocol.Member(peerA, "Bea"), GroupProtocol.Member(peerB, "Cy")))
            assertTrue(groups.handle(peerA, GroupProtocol.format(GroupProtocol.Sync(removed))))
            assertEquals(2L, store.storedGroup(initial.conversationId)!!.revision)
            assertFalse(store.storedGroup(initial.conversationId)!!.members.any { it.key == own })
            assertTrue(runCatching { groups.send(initial.conversationId, "not allowed") }.isFailure)
            assertEquals(0, store.messages(initial.conversationId).size)
        }
    }

    @Test
    fun `future event survives intermediate roster and replays at its revision`() {
        newStore().use { store ->
            seedContact(store, peerA, "Bea")
            val groups = fixture(store)
            val id = "99999999-9999-4999-8999-999999999999"
            val newcomer = "d".repeat(64)
            val event = GroupProtocol.Event("post", GroupProtocol.Ref(id, peerA, 3),
                "11111111-1111-4111-8111-111111111111", newcomer, "future", 40000L)
            groups.handle(newcomer, GroupProtocol.format(event))
            assertEquals(1, store.pendingGroupEventCount())
            val originalRow = store.pendingGroupEvents(peerA, id).single().row
            val base = GroupProtocol.State(id, peerA, "Design", 2, listOf(
                GroupProtocol.Member(peerA, "Bea"), GroupProtocol.Member(own, "Alex"), GroupProtocol.Member(peerB, "Cy")))
            groups.handle(peerA, GroupProtocol.format(GroupProtocol.Sync(base)))
            assertEquals(1, store.pendingGroupEventCount())
            assertEquals("later events must remain in their original bounded queue slot", originalRow,
                store.pendingGroupEvents(peerA, id).single().row)
            groups.handle(peerA, GroupProtocol.format(GroupProtocol.Sync(base.copy(revision = 3, members = base.members + GroupProtocol.Member(newcomer, "New")))))
            assertEquals(0, store.pendingGroupEventCount())
            assertEquals("future", store.messages(groupConversationId(peerA, id)).single().body)
        }
    }

    @Test
    fun `desktop roster write entry points require phone`() = runBlocking<Unit> {
        newStore().use { store ->
            val sender = FakeSender()
            val groups = fixture(store, sender)
            val group = mirrored(store)
            val edits: List<suspend () -> Unit> = listOf(
                { groups.create("Design", members()); Unit },
                { groups.addMember(group.conversationId, members().first()); Unit },
                { groups.removeMember(group.conversationId, peerB); Unit },
                { groups.rename(group.conversationId, "Changed"); Unit },
                { groups.setAvatar(group.conversationId, null); Unit },
            )
            edits.forEach { edit ->
                val error = runCatching { edit() }.exceptionOrNull()
                assertTrue(error?.message?.contains("Edit groups on your phone") == true)
            }
            assertEquals(1L, store.storedGroup(group.conversationId)!!.revision)
            assertEquals("Design", store.storedGroup(group.conversationId)!!.name)
            assertTrue(sender.deliveries.isEmpty())
        }
    }

    @Test
    fun `desktop creation refuses without storing or sending`() = runBlocking<Unit> {
        newStore().use { store ->
            val sender = FakeSender()
            val groups = fixture(store, sender)
            val error = runCatching { groups.create("Design", members()) }.exceptionOrNull()
            assertTrue(error?.message?.contains("Edit groups on your phone") == true)
            assertTrue(store.storedGroups().isEmpty())
            assertTrue(sender.deliveries.isEmpty())
        }
    }

    @Test
    fun `sending a post stores it once and reaches every other member directly`() = runBlocking<Unit> {
        val store = newStore()
        val sender = FakeSender()
        val groups = fixture(store, sender)
        val group = mirrored(store, own)
        sender.deliveries.clear()

        clock = 20_000L
        val report = groups.send(group.conversationId, "hello team")
        assertEquals(2, report.sent)
        assertEquals(0, report.failed)
        val event = GroupProtocol.parse(sender.deliveries.first().body) as GroupProtocol.Event
        assertEquals("post", event.kind)
        assertEquals("hello team", event.text)
        assertEquals(own, event.author)
        assertEquals(group.sharedId, event.group.id)
        assertEquals(setOf(peerA, peerB), sender.deliveries.map { it.peer }.toSet())
        // The event id in the wire body is the id the local row uses, so a replay cannot duplicate it.
        val stored = store.messages(group.conversationId).single()
        assertEquals("$own:${event.event}", stored.id)
        assertEquals("hello team", stored.body)
        assertTrue(stored.outgoing)
        // Replay of the same event from our own device is ignored.
        assertTrue(groups.handle(own, sender.deliveries.first().body))
        assertEquals(1, store.messages(group.conversationId).size)
        store.close()
    }

    @Test
    fun `a partial fan-out reports the failure and still stores the post`() = runBlocking<Unit> {
        val store = newStore()
        val sender = FakeSender()
        val groups = fixture(store, sender)
        val group = mirrored(store, own)
        sender.deliveries.clear()
        sender.failFor = mutableSetOf(peerB)
        val report = groups.send(group.conversationId, "only some")
        assertEquals(1, report.sent)
        assertEquals(1, report.failed)
        assertEquals("unreachable", report.firstError)
        assertEquals(1, store.messages(group.conversationId).size)
        store.close()
    }

    @Test
    fun `a phone member's group post lands in the same conversation`() {
        val store = newStore()
        val sender = FakeSender()
        val groups = fixture(store, sender)
        val group = runBlocking { mirrored(store, own) }

        val event = GroupProtocol.Event(
            kind = "post", group = GroupProtocol.Ref(group.sharedId, own, 1),
            event = "11111111-1111-4111-8111-111111111111", author = peerA, text = "hi from the phone", time = 30_000L,
        )
        assertTrue(groups.handle(peerA, GroupProtocol.format(event)))
        val stored = store.messages(group.conversationId).single()
        assertEquals("hi from the phone", stored.body)
        assertEquals(peerA, stored.sender)
        assertFalse(stored.outgoing)
        assertEquals(1, store.unreadCount(group.conversationId))
        store.close()
    }

    @Test
    fun `an event signed by someone other than the sender is rejected`() {
        val store = newStore()
        val groups = fixture(store)
        val group = runBlocking { mirrored(store, own) }
        val forged = GroupProtocol.Event("post", GroupProtocol.Ref(group.sharedId, own, 1), "11111111-1111-4111-8111-111111111111", peerA, "forged", 1L)
        assertTrue(groups.handle(peerB, GroupProtocol.format(forged)))
        assertEquals(0, store.messages(group.conversationId).size)
        store.close()
    }

    @Test
    fun `a post from a stranger is not stored or held`() {
        val store = newStore()
        val groups = fixture(store)
        val group = runBlocking { mirrored(store, own) }
        val stranger = "d".repeat(64)
        val event = GroupProtocol.Event("post", GroupProtocol.Ref(group.sharedId, own, 1), "11111111-1111-4111-8111-111111111111", stranger, "spam", 1L)
        assertTrue(groups.handle(stranger, GroupProtocol.format(event)))
        assertEquals(0, store.messages(group.conversationId).size)
        assertEquals(0, store.pendingGroupEventCount())
        store.close()
    }

    @Test
    fun `a malformed group control is swallowed instead of rendered as chat`() {
        val store = newStore()
        val groups = fixture(store)
        assertTrue(groups.handle(peerA, GroupProtocol.PREFIX + "{\"v\":2,\"kind\":\"sync\"}"))
        assertEquals(0, store.messages(peerA).size)
        store.close()
    }

    @Test
    fun `an event that arrives before its roster is held and replayed once the sync lands`() {
        val source = newStore()
        val group = runBlocking { mirrored(source, own) }
        source.close()
        // A second desktop that knows the owner as a contact but has not received the roster yet.
        val dirs = DesktopPaths(tmp.newFolder("held-config").toPath(), tmp.newFolder("held-data").toPath())
        val secret = FileSecretStore(dirs.secretFile)
        DesktopMessageStore(dirs.databaseFile, secret).use { fresh ->
            seedContact(fresh, own, "Owner")
            val groups2 = fixture(fresh, FakeSender())
            val event = GroupProtocol.Event("post", GroupProtocol.Ref(group.sharedId, own, 2), "22222222-2222-4222-8222-222222222222", peerA, "early bird", 40_000L)
            assertTrue(groups2.handle(peerA, GroupProtocol.format(event)))
            assertEquals(0, fresh.messages(group.conversationId).size)
            assertEquals(1, fresh.pendingGroupEventCount())

            val sync = GroupProtocol.Sync(
                GroupProtocol.State(group.sharedId, own, "Design", 2, listOf(
                    GroupProtocol.Member(own, "Alex"), GroupProtocol.Member(peerA, "Bea"), GroupProtocol.Member(peerB, "Cy"),
                )),
            )
            assertTrue(groups2.handle(own, GroupProtocol.format(sync)))
            assertEquals(0, fresh.pendingGroupEventCount())
            assertEquals(listOf("early bird"), fresh.messages(group.conversationId).map { it.body })
        }
    }

    @Test
    fun `only the creator may add remove rename or set the avatar`() {
        val store = newStore()
        val groups = fixture(store)
        val group = runBlocking { mirrored(store, own) }
        // A roster owned by someone else.
        val foreign = GroupProtocol.State("99999999-9999-4999-8999-999999999999", peerA, "Foreign", 1, listOf(GroupProtocol.Member(peerA, "Bea"), GroupProtocol.Member(own, "Alex")))
        store.saveGroupState(foreign)
        val conv = groupConversationId(peerA, foreign.id)
        assertThrows(IllegalStateException::class.java) { runBlocking { groups.addMember(conv, GroupMemberInput("e".repeat(64), "Eve", null)) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { groups.removeMember(conv, own) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { groups.rename(conv, "Hijacked") } }
        assertThrows(IllegalStateException::class.java) { runBlocking { groups.setAvatar(conv, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) } }
        assertEquals("Design", store.storedGroup(group.conversationId)!!.name)
        store.close()
    }

    @Test
    fun `a roster change from a non-owner is ignored`() {
        val store = newStore()
        val groups = fixture(store)
        val group = runBlocking { mirrored(store, own) }
        val hijack = GroupProtocol.State(group.sharedId, own, "Hijacked", 2, group.members)
        // Sender is not the owner; even a valid-looking state must not be applied.
        assertTrue(groups.handle(peerA, GroupProtocol.format(GroupProtocol.Sync(hijack))))
        assertEquals("Design", store.storedGroup(group.conversationId)!!.name)
        assertEquals(3, store.storedGroup(group.conversationId)!!.members.size)
        store.close()
    }

    @Test
    fun `an older roster revision is ignored`() {
        val store = newStore()
        val groups = fixture(store)
        val group = runBlocking { mirrored(store, own) }
        store.saveGroupState(group.toTestState().copy(name = "Design v2", revision = 2))
        val older = GroupProtocol.State(group.sharedId, own, "Stale", 1, group.members)
        assertTrue(groups.handle(own, GroupProtocol.format(GroupProtocol.Sync(older))))
        assertEquals("Design v2", store.storedGroup(group.conversationId)!!.name)
        store.close()
    }

    @Test
    fun `a group avatar is stored from the owner and ignored from anyone else`() {
        val store = newStore()
        val groups = fixture(store)
        val group = runBlocking { mirrored(store, own) }
        val jpeg = jpeg(0xff0000)
        val body = com.relay.app.groups.GroupAvatarBody.format(com.relay.app.groups.GroupAvatarBody.Update(group.sharedId, 1, jpeg))
        assertTrue(groups.handleAvatar(peerA, body))
        assertNull("a member's avatar must be refused", store.storedGroup(group.conversationId)!!.avatar)
        assertTrue(groups.handleAvatar(own, body))
        assertTrue(store.storedGroup(group.conversationId)!!.avatar!!.contentEquals(jpeg))
        assertEquals(1L, store.storedGroup(group.conversationId)!!.avatarRevision)
        store.close()
    }

    @Test
    fun `profile name events update the sender's profile only`() {
        val store = newStore()
        val groups = fixture(store)
        val group = runBlocking { mirrored(store, own) }
        val body = GroupProtocol.format(
            GroupProtocol.Event(
                kind = "profile", group = GroupProtocol.Ref(group.sharedId, own, 1), event = "44444444-4444-4444-8444-444444444444",
                author = peerA, text = com.relay.app.media.ProfileNameBody.format(com.relay.app.media.ProfileNameBody.Update(1, "Beatrice")), time = 2L,
            ),
        )
        assertTrue(groups.handle(peerA, body))
        assertEquals("Beatrice", store.profile(peerA)!!.name)
        assertEquals(1L, store.profile(peerA)!!.nameRevision)
        assertNull(store.profile(peerB))
        store.close()
    }

    private fun StoredGroup.toTestState() = GroupProtocol.State(sharedId, owner, name, revision, members)

    /** Seeds one trusted contact through the production link-import path (not a test-only insert). */
    private fun seedContact(store: DesktopMessageStore, key: String, name: String) {
        store.importLinkBundle(
            com.relay.app.linking.LinkBundle(
                createdAt = 1L,
                identity = com.relay.app.linking.LinkIdentity("00".repeat(32), own, "primary", "fixture", "fixture"),
                contacts = listOf(
                    com.relay.app.linking.LinkContact(
                        name = name, hasRelay = true, trustLevel = "trusted", nostrPubkey = key,
                        publicKey = "PUBLICKEYBASE64AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", qrVerified = true,
                    ),
                ),
                groups = emptyList(),
                settings = com.relay.app.linking.LinkSettings(),
            ),
            appliedAt = 1L,
        )
        check(store.contacts().any { it.nostrPubkey == key })
    }
}
