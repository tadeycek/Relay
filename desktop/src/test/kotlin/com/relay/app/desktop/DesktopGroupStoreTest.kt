package com.relay.app.desktop

import com.relay.app.groups.GroupProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * WP07 storage half: group rosters, group avatars, profiles and per-conversation read state, all in
 * the same encrypted database and all durable across reopen. Revision guards are the protocol's
 * defence against a replayed roster/name/avatar body, so each of them is asserted directly.
 */
class DesktopGroupStoreTest {

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().use { out ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, color)
        ImageIO.write(image, "jpeg", out)
        out.toByteArray()
    }

    @Test
    fun `store refuses invalid image bytes on direct writes`() {
        val p = paths("images")
        DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile)).use { store ->
            val group = state()
            store.saveGroupState(group)
            val invalid = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
            assertFalse(store.setGroupAvatar(groupConversationId(group.owner, group.id), invalid, 1))
            assertFalse(store.saveProfileAvatar(group.owner, invalid, 1))
            assertNull(store.storedGroup(group.owner, group.id)!!.avatar)
            assertNull(store.profile(group.owner))
        }
    }

    @Test
    fun `avatar dimensions above 256 or zero are rejected by header`() {
        fun jpegOf(w: Int, h: Int) = ByteArrayOutputStream().use { out ->
            ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpeg", out); out.toByteArray()
        }
        assertTrue(validDesktopAvatar(jpegOf(256, 256)))
        assertFalse(validDesktopAvatar(jpegOf(257, 100)))
        assertFalse(validDesktopAvatar(jpegOf(300, 300)))
        val zero = jpegOf(16, 16).also { b ->
            val i = (0 until b.size - 8).first { b[it] == 0xff.toByte() && b[it + 1] == 0xc0.toByte() }
            b[i + 7] = 0; b[i + 8] = 0
        }
        assertFalse(validDesktopAvatar(zero))
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun paths(name: String = "one"): DesktopPaths {
        val config = tmp.newFolder("$name-config").toPath()
        val data = tmp.newFolder("$name-data").toPath()
        return DesktopPaths(config, data)
    }

    private fun state(owner: String = "a".repeat(64), members: List<String> = listOf(owner)) = GroupProtocol.State(
        id = "11111111-2222-3333-4444-555555555555",
        owner = owner,
        name = "Design",
        revision = 1,
        members = members.mapIndexed { i, key -> GroupProtocol.Member(key, "Member $i") },
    )

    @Test
    fun `newest page is chronological and equal timestamps follow insertion order`() {
        val p = paths("page")
        DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile)).use { store ->
            repeat(205) { n -> store.insertMessage(DesktopMessage("m$n", "peer", "peer", "$n", n.toLong(), false)) }
            store.insertMessage(DesktopMessage("tie", "peer", "peer", "tie", 204, false))
            val page = store.messages("peer")
            assertEquals(200, page.size)
            assertEquals("m6", page.first().id)
            assertEquals(listOf("m204", "tie"), page.takeLast(2).map { it.id })
            store.markRead("peer", store.lastMessage("peer")!!.ts)
            assertEquals(0, store.unreadCount("peer"))
        }
    }

    @Test
    fun `stale and equal roster revisions cannot overwrite a phone revision`() {
        val p = paths("cas")
        DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile)).use { store ->
            val initial = state()
            store.saveGroupState(initial.copy(revision = 3, name = "Phone"))
            store.saveGroupState(initial.copy(revision = 2, name = "Desktop"))
            store.saveGroupState(initial.copy(revision = 3, name = "Equal"))
            assertEquals("Phone", store.storedGroup(initial.owner, initial.id)!!.name)
            assertEquals("Phone", store.conversation(groupConversationId(initial.owner, initial.id))!!.title)
        }
    }

    @Test
    fun `persisted draft and selected conversation have bounded lengths`() {
        val p = paths("bounded")
        DesktopMessageStore(p.databaseFile, FileSecretStore(p.secretFile)).use { store ->
            assertThrows(IllegalArgumentException::class.java) { store.setConversationDraft("peer", "x".repeat(100_000)) }
            assertThrows(IllegalArgumentException::class.java) { store.setMeta("ui.selected", "x".repeat(100_000)) }
            assertEquals("", store.conversationDraft("peer"))
            assertNull(store.meta("ui.selected"))
        }
    }

    @Test
    fun `selected conversation and its draft survive a restart`() {
        val paths = paths("ui")
        val peer = "b".repeat(64)
        var store = DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile))
        val conversation = store.ensureConversation(peer, title = "Bea")
        store.setMeta("ui.selected", conversation.id)
        store.setConversationDraft(conversation.id, "half-typed message")
        store.close()

        store = DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile))
        assertEquals(conversation.id, store.meta("ui.selected"))
        assertEquals("half-typed message", store.conversationDraft(conversation.id))
        store.close()
    }

    @Test
    fun `group roster and its rail conversation survive a reopen`() {
        val paths = paths()
        val owner = "a".repeat(64)
        val other = "b".repeat(64)
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            assertEquals("schema 3 adds groups, profiles and read state", 3, store.schemaVersion())
            store.saveGroupState(state(owner, listOf(owner, other)), now = 5_000L)
            val convId = groupConversationId(owner, "11111111-2222-3333-4444-555555555555")
            assertEquals("group:$owner:11111111-2222-3333-4444-555555555555", convId)
            assertTrue(isGroupConversation(convId))
            val rail = store.conversations().single()
            assertEquals(convId, rail.id)
            assertEquals("Design", rail.title)
        }
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { reopened ->
            val group = reopened.storedGroup(groupConversationId(owner, "11111111-2222-3333-4444-555555555555"))
            assertNotNull(group)
            assertEquals("Design", group!!.name)
            assertEquals(1L, group.revision)
            assertEquals(listOf(owner, other), group.members.map { it.key })
            assertEquals("Design", reopened.conversations().single().title)
        }
    }

    @Test
    fun `group avatar accepts only a newer revision`() {
        val paths = paths()
        val owner = "a".repeat(64)
        val id = "11111111-2222-3333-4444-555555555555"
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            store.saveGroupState(state(owner))
            val conv = groupConversationId(owner, id)
            val first = jpeg(0xff0000)
            val second = jpeg(0x00ff00)
            assertTrue(store.setGroupAvatar(conv, first, 1))
            assertFalse("same revision must not overwrite", store.setGroupAvatar(conv, second, 1))
            assertTrue(store.setGroupAvatar(conv, second, 2))
            assertEquals(2L, store.storedGroup(conv)!!.avatarRevision)
            assertTrue(store.storedGroup(conv)!!.avatar!!.contentEquals(second))
            // Clearing the photo is an update like any other and still has to move the revision.
            assertTrue(store.setGroupAvatar(conv, null, 3))
            assertNull(store.storedGroup(conv)!!.avatar)
        }
    }

    @Test
    fun `profile name and avatar are revision guarded and durable`() {
        val paths = paths()
        val peer = "c".repeat(64)
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            assertTrue(store.saveProfileName(peer, "Ada", 1, now = 1_000L))
            assertFalse("a replayed older name must be dropped", store.saveProfileName(peer, "Mallory", 1, now = 1_001L))
            assertTrue(store.saveProfileName(peer, "Ada L", 2, now = 1_002L))
            assertTrue(store.saveProfileAvatar(peer, jpeg(0xff0000), 1, now = 1_003L))
            assertEquals("Ada L", store.profile(peer)!!.name)
            assertEquals(2L, store.profile(peer)!!.nameRevision)
            assertEquals(1L, store.profile(peer)!!.avatarRevision)
            assertEquals(1, store.profiles().size)
        }
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { reopened ->
            assertEquals("Ada L", reopened.profile(peer)!!.name)
            assertNull("unknown peers have no profile row", reopened.profile("d".repeat(64)))
        }
    }

    @Test
    fun `unread counts only incoming messages after the read mark`() {
        val paths = paths()
        val peer = "e".repeat(64)
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            store.insertMessage(DesktopMessage("m1", peer, peer, "one", 1_000L, outgoing = false))
            store.insertMessage(DesktopMessage("m2", peer, peer, "two", 2_000L, outgoing = false))
            store.insertMessage(DesktopMessage("m3", peer, "me", "mine", 3_000L, outgoing = true))
            assertEquals(2, store.unreadCount(peer))
            assertEquals(0L, store.readThrough(peer))
            store.markRead(peer, 2_000L)
            assertEquals(0, store.unreadCount(peer))
            assertEquals(2_000L, store.readThrough(peer))
            store.insertMessage(DesktopMessage("m4", peer, peer, "three", 4_000L, outgoing = false))
            assertEquals(1, store.unreadCount(peer))
            // The mark never moves backwards, so a late-read conversation does not re-badge.
            store.markRead(peer, 100L)
            assertEquals(2_000L, store.readThrough(peer))
        }
    }

    @Test
    fun `held group events are bounded per group and replay oldest first`() {
        val paths = paths()
        val owner = "f".repeat(64)
        val id = "11111111-2222-3333-4444-555555555555"
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            repeat(105) { store.holdGroupEvent(owner, id, owner, "body-$it", now = it.toLong()) }
            val held = store.pendingGroupEvents(owner, id)
            assertEquals("per-group cap is 100", 100, held.size)
            assertEquals("oldest five are evicted", "body-5", held.first().body)
            assertEquals("body-104", held.last().body)
            assertEquals(100, store.pendingGroupEventCount())
            store.deletePendingGroupEvent(held.first().row)
            assertEquals(99, store.pendingGroupEvents(owner, id).size)
        }
    }

    @Test
    fun `conversation read mark and draft are cleared per conversation`() {
        val paths = paths()
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            store.insertMessage(DesktopMessage("m1", "peer-a", "peer-a", "hello", 1_000L, outgoing = false))
            store.setConversationDraft("peer-a", "half typed")
            assertEquals("half typed", store.conversationDraft("peer-a"))
            assertEquals(0, store.unreadCount("peer-b"))
            assertEquals("", store.conversationDraft("peer-b"))
        }
        val db = paths.databaseFile
        DesktopMessageStore(db, FileSecretStore(paths.secretFile)).use { reopened ->
            assertEquals("a draft survives restart", "half typed", reopened.conversationDraft("peer-a"))
            assertNull("no selected conversation before the UI picks one", reopened.meta("ui.selected"))
        }
    }

    @Test
    fun `group conversation title follows a rename and direct titles are not clobbered`() {
        val paths = paths()
        val owner = "a".repeat(64)
        val id = "11111111-2222-3333-4444-555555555555"
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            store.saveGroupState(state(owner))
            val conv = groupConversationId(owner, id)
            store.saveGroupState(state(owner).copy(name = "Design v2", revision = 2))
            assertEquals("Design v2", store.conversation(conv)!!.title)
            // A bootstrap "Jane" from the phone's contact list must survive a message insert.
            store.ensureConversation("b".repeat(64), title = "Jane")
            store.insertMessage(DesktopMessage("m1", "b".repeat(64), "b".repeat(64), "hi", 1_000L, false))
            assertEquals("Jane", store.conversation("b".repeat(64))!!.title)
        }
    }

    @Test
    fun `stored groups are listed and each has a rail conversation`() {
        val paths = paths()
        val owner = "a".repeat(64)
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            fun save(id: String, name: String, at: Long) = store.saveGroupState(
                GroupProtocol.State(id, owner, name, 1, listOf(GroupProtocol.Member(owner, "me"))), now = at,
            )
            save("11111111-2222-3333-4444-555555555551", "Older", 1_000L)
            save("11111111-2222-3333-4444-555555555552", "Newer", 2_000L)
            assertEquals(listOf("Newer", "Older"), store.storedGroups().map { it.name })
            assertEquals(2, store.conversations().size)
        }
    }
}
