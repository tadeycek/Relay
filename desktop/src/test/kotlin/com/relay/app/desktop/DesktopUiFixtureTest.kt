package com.relay.app.desktop

import com.relay.app.groups.GroupProtocol
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates a disposable desktop state directory for the packaged-UI screenshot. Gated by
 * RELAY_UI_FIXTURE_DIR so a normal `:desktop:test` run skips it (no user data is ever touched).
 *
 * Every row is written through the production store/engine seams; the synthetic contacts are
 * obviously fake members, and no network call is made.
 */
class DesktopUiFixtureTest {

    @Test
    fun `seed a disposable desktop state for the screenshot`() {
        val root = System.getenv("RELAY_UI_FIXTURE_DIR") ?: "".also { assumeTrue("Set RELAY_UI_FIXTURE_DIR", false) }
        val base = Path.of(root)
        val paths = DesktopPaths(base.resolve("config"), base.resolve("data"))
        val desktop = "a".repeat(64)
        val bea = "b".repeat(64)
        val cy = "c".repeat(64)
        val inner = "PUBLICKEYBASE64AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        DesktopEngine.create(paths, "").use { engine ->
            val store = engine.store
            val profile = engine.profiles
            // This computer's profile.
            store.saveProfileName(desktop, "Alex", 0)
            // Contacts arrive through the real link-import path.
            store.importLinkBundle(
                com.relay.app.linking.LinkBundle(
                    createdAt = 1L,
                    identity = com.relay.app.linking.LinkIdentity("00".repeat(32), desktop, "primary", "fixture", "fixture"),
                    contacts = listOf(
                        com.relay.app.linking.LinkContact(name = "Bea", hasRelay = true, trustLevel = "trusted", nostrPubkey = bea, publicKey = inner, qrVerified = true),
                        com.relay.app.linking.LinkContact(name = "Cy", hasRelay = true, trustLevel = "trusted", nostrPubkey = cy, publicKey = inner, qrVerified = true),
                    ),
                    groups = emptyList(),
                    settings = com.relay.app.linking.LinkSettings(),
                ),
                appliedAt = 1L,
            )
            // A 1:1 conversation with a few messages, grouped by sender.
            val dm = store.ensureConversation(bea, title = "Bea")
            store.insertMessage(DesktopMessage("dm1", dm.id, bea, "Hey Alex - did you see the new deck?", 1_000L, false))
            store.insertMessage(DesktopMessage("dm2", dm.id, desktop, "Yes, the three-pane layout looks right.", 2_000L, true))
            store.insertMessage(DesktopMessage("dm3", dm.id, bea, "Great. Ship it?", 3_000L, false))
            store.insertMessage(DesktopMessage("dm4", dm.id, desktop, "After the group avatar lands.", 4_000L, true))
            store.markRead(dm.id, 4_000L)
            // A group with two members and a member's post, using the real protocol codec.
            val group = GroupProtocol.fitted(
                GroupProtocol.State(
                    id = "11111111-2222-4333-8444-555555555555",
                    owner = desktop,
                    name = "Design",
                    revision = 1,
                    members = listOf(
                        GroupProtocol.Member(desktop, "Alex"),
                        GroupProtocol.Member(bea, "Bea"),
                        GroupProtocol.Member(cy, "Cy"),
                    ),
                ),
            )
            store.saveGroupState(group, now = 5_000L)
            val conv = groupConversationId(desktop, group.id)
            store.insertMessage(DesktopMessage("g1", conv, desktop, "New group for the redesign.", 5_000L, true))
            store.insertMessage(DesktopMessage("g2", conv, bea, "I'll bring the icon set.", 6_000L, false))
            store.insertMessage(DesktopMessage("g3", conv, cy, "And the spacing tokens.", 7_000L, false))
            // An unread message so the rail badge is real.
            store.insertMessage(DesktopMessage("g4", conv, bea, "Pushed a draft to the branch.", 12_000L, false))
            store.markRead(conv, 5_000L)
            // A conversation with an unsupported body, to prove the honest label is what renders.
            store.insertMessage(DesktopMessage("u1", cy, cy, "TYPE:HOLOGRAM|secret", 9_000L, false))
        }
        Files.writeString(base.resolve("READY"), "ok")
        println("UI_FIXTURE_OK root=$root")
    }
}
