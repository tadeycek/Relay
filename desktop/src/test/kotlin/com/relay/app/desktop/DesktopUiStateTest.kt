package com.relay.app.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure presentation rules of the three-pane window. These functions decide what the rail and the
 * thread actually say, so they are tested without a display: title precedence, sender grouping, honest
 * fallbacks for unknown bodies, and the quick-switcher filter.
 */
class DesktopUiStateTest {

    private val own = "a".repeat(64)
    private val peerA = "b".repeat(64)
    private val peerB = "c".repeat(64)
    private val now = 1_700_000_000_000L

    private fun message(id: String, conv: String, sender: String, body: String, ts: Long, outgoing: Boolean = false) =
        DesktopMessage(id, conv, sender, body, ts, outgoing)

    @Test
    fun `display text keeps chat text and hides controls behind honest labels`() {
        assertEquals("hello there", DesktopUi.displayText("hello there"))
        assertEquals("Unsupported message — update Relay to read this", DesktopUi.displayText("TYPE:HOLOGRAM|secret"))
        assertEquals("File attachment", DesktopUi.displayText("TYPE:FILE|whatever"))
        assertEquals("", DesktopUi.displayText("TYPE:PROFILE_PHOTO|KIND:NAME|REV:0|NAME:QQ=="))
        assertEquals("", DesktopUi.displayText("TYPE:PROFILE_PHOTO|KIND:GROUP|{}"))
        assertEquals("", DesktopUi.displayText("TYPE:PROFILE_PHOTO|KIND:GROUP_AVATAR|{}"))
        assertEquals("", DesktopUi.displayText("TYPE:CALL|J:e30="))
    }

    @Test
    fun `sender grouping collapses consecutive messages from one sender within five minutes`() {
        val conv = "peer"
        val rows = DesktopUi.buildThread(
            messages = listOf(
                message("1", conv, peerA, "one", now - 60_000),
                message("2", conv, peerA, "two", now - 50_000),
                message("3", conv, peerB, "three", now - 40_000),
                message("4", conv, peerA, "four", now - 10_000),
                message("5", conv, peerA, "five", now + 6 * 60_000),
            ),
            ownPubkey = own,
            nameOf = { key -> if (key == peerA) "Bea" else "Cy" },
            avatarOf = { null },
            now = now,
        )
        assertEquals(listOf(true, false, true, true, true), rows.map { it.showHeader })
        assertEquals(listOf("Bea", "Bea", "Cy", "Bea", "Bea"), rows.map { it.senderName })
        assertEquals(listOf("one", "two", "three", "four", "five"), rows.map { it.text })
        // Timestamps are shown on the first row of a group and on every outgoing row.
        assertTrue(rows[0].time.isNotEmpty())
        assertEquals(rows[1].time, "")
    }

    @Test
    fun `an outgoing run is grouped too and never labelled as a peer`() {
        val rows = DesktopUi.buildThread(
            messages = listOf(
                message("1", "peer", own, "mine", now - 2_000, outgoing = true),
                message("2", "peer", own, "also mine", now - 1_000, outgoing = true),
            ),
            ownPubkey = own, nameOf = { "Bea" }, avatarOf = { null }, now = now,
        )
        assertTrue(rows.all { it.outgoing })
        assertEquals(listOf("You", "You"), rows.map { it.senderName })
    }

    @Test
    fun `an unsupported body renders as a labelled row instead of raw wire text`() {
        val rows = DesktopUi.buildThread(
            messages = listOf(message("1", "peer", peerA, "TYPE:HOLOGRAM|secret", now)),
            ownPubkey = own, nameOf = { "Bea" }, avatarOf = { null }, now = now,
        )
        assertTrue(rows.single().unsupported)
        assertFalse(rows.single().text.contains("TYPE:HOLOGRAM"))
    }

    @Test
    fun `initials are stable and honest for odd names`() {
        assertEquals("AL", DesktopUi.initials("Alex"))
        assertEquals("AL", DesktopUi.initials("alex lind"))
        assertEquals("?", DesktopUi.initials(""))
        assertEquals("?", DesktopUi.initials("   "))
        assertEquals("AB", DesktopUi.initials("Ábel"))
    }

    @Test
    fun `clock and day labels separate today yesterday and older days`() {
        val today = now
        assertEquals("Today", DesktopUi.dayLabel(today, now))
        assertEquals("Yesterday", DesktopUi.dayLabel(today - 24 * 60 * 60 * 1000, now))
        assertEquals(
            java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(
                java.time.Instant.ofEpochMilli(today).atZone(java.time.ZoneId.systemDefault()),
            ),
            DesktopUi.clockLabel(today, now),
        )
        assertTrue(DesktopUi.dayLabel(today - 5L * 24 * 60 * 60 * 1000, now).matches(Regex("\\d{1,2} \\w{3}")))
    }

    @Test
    fun `quick switcher filters by title and handle, ignoring case`() {
        val rail = listOf(
            railItem("peer-a", "Bea", "bbbbbbbb…"),
            railItem("peer-b", "Cy", "cccccccc…"),
            railItem("group:x:1", "Design", "3 members", isGroup = true),
        )
        assertEquals(listOf("Bea"), DesktopUi.filterRail(rail, "bea").map { it.title })
        assertEquals(listOf("Design"), DesktopUi.filterRail(rail, "DESIGN").map { it.title })
        assertEquals(listOf("Cy"), DesktopUi.filterRail(rail, "cccc").map { it.title })
        assertEquals(3, DesktopUi.filterRail(rail, "  ").size)
        assertEquals(0, DesktopUi.filterRail(rail, "zzz").size)
    }

    @Test
    fun `rail title prefers the group name then the profile name then the contact name then the handle`() {
        val group = StoredGroup(
            conversationId = "group:$own:1", owner = own, sharedId = "1", name = "Design", revision = 1,
            members = emptyList(), avatar = null, avatarRevision = -1, updatedAt = 1,
        )
        assertEquals("Design", DesktopUi.railTitle("group:$own:1", group, null, null, "group:$own:1"))
        val profile = DesktopProfile(peerA, "Bea Profile", null, 0, -1, 0)
        assertEquals("Bea Profile", DesktopUi.railTitle(peerA, null, profile, "Bea Contact", "bbbbbbbb…"))
        assertEquals("Bea Contact", DesktopUi.railTitle(peerA, null, null, "Bea Contact", "bbbbbbbb…"))
        assertEquals("bbbbbbbb…", DesktopUi.railTitle(peerA, null, null, null, "bbbbbbbb…"))
        // A group with no roster row still falls back to its id, not a crash.
        assertEquals("group:$own:9", DesktopUi.railTitle("group:$own:9", null, null, null, "group:$own:9"))
    }

    @Test
    fun `last message preview is short and never raw wire text`() {
        val long = "x".repeat(200)
        assertEquals("hello", DesktopUi.preview("hello"))
        assertTrue(DesktopUi.preview(long).length <= 60)
        assertEquals("Unsupported message — update Relay to read this", DesktopUi.preview("TYPE:HOLOGRAM|secret"))
        assertEquals("", DesktopUi.preview("TYPE:PROFILE_PHOTO|REV:0|JPEG:"))
        assertEquals("", DesktopUi.preview(null))
    }

    @Test
    fun `rail sorting puts the most recent conversation first and keeps empty groups visible`() {
        val items = listOf(
            railItem("a", "Old", "x", lastTs = 1_000),
            railItem("b", "New", "x", lastTs = 9_000),
            railItem("c", "Empty", "x", lastTs = 0),
        )
        assertEquals(listOf("New", "Old", "Empty"), DesktopUi.sortedRail(items).map { it.title })
    }

    @Test
    fun `member rows mark the owner and this install and skip a missing avatar safely`() {
        val group = StoredGroup(
            conversationId = "group:$own:1", owner = own, sharedId = "1", name = "Design", revision = 2,
            members = listOf(
                com.relay.app.groups.GroupProtocol.Member(own, "Alex"),
                com.relay.app.groups.GroupProtocol.Member(peerA, "Bea"),
                com.relay.app.groups.GroupProtocol.Member(peerB, "Cy"),
            ),
            avatar = null, avatarRevision = -1, updatedAt = 1,
        )
        val rows = DesktopUi.memberRows(group, own) { key -> if (key == peerA) DesktopProfile(key, "Beatrice", null, 0, -1, 0) else null }
        assertEquals(listOf("Alex", "Beatrice", "Cy"), rows.map { it.name })
        assertTrue(rows.first().isOwner)
        assertTrue(rows.first().isSelf)
        assertFalse(rows[1].isOwner)
        assertNull(rows[2].avatar)
    }

    @Test
    fun `unread badge text caps at 99 plus`() {
        assertEquals("1", DesktopUi.badge(1))
        assertEquals("99", DesktopUi.badge(99))
        assertEquals("99+", DesktopUi.badge(150))
        assertEquals("", DesktopUi.badge(0))
    }

    private fun railItem(id: String, title: String, subtitle: String, isGroup: Boolean = false, lastTs: Long = 0) =
        RailItem(id = id, title = title, subtitle = subtitle, isGroup = isGroup, unread = 0, lastMessage = null, lastSender = null, lastTs = lastTs, avatar = null, memberCount = null)

    @Test
    fun `chat ui diagnostics fail closed without explicit config and data directories`() {
        for (args in listOf(
            arrayOf("--show-chat-ui"),
            arrayOf("--show-chat-ui", "--config-dir", "/tmp/c"),
            arrayOf("--show-chat-ui", "--data-dir", "/tmp/d"),
            arrayOf("--show-chat-ui", "--config-dir", "/tmp/c", "--data-dir"),
            arrayOf("--show-chat-ui", "--config-dir", "", "--data-dir", "/tmp/d"),
        )) {
            val failure = runCatching { Args.parse(args) }.exceptionOrNull()
            assertTrue("expected IllegalArgumentException for ${args.toList()}", failure is IllegalArgumentException)
        }
        val ok = Args.parse(arrayOf("--show-chat-ui", "--config-dir", "/tmp/c", "--data-dir", "/tmp/d"))
        assertTrue(ok.showChatUi)
        assertEquals("/tmp/c", ok.configDir)
        assertEquals("/tmp/d", ok.dataDir)
        // The flag is the only thing that fails closed: normal startup keeps default directories.
        assertFalse(Args.parse(arrayOf()).showChatUi)
    }
}
