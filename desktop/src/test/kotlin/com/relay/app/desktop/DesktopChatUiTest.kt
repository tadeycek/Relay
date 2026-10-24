package com.relay.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.relay.app.transport.IncomingEnvelope
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.SendResult
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * WP07-AC3: real Compose UI tests against the semantics tree — sending with Enter and Shift+Enter,
 * switching conversations, the Ctrl+K quick switcher, honest empty states, unknown message types and
 * a missing avatar. These run the actual composables, not a screenshot diff.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopChatUiTest {

    private fun rail(id: String, title: String, isGroup: Boolean = false, unread: Int = 0) = RailItem(
        id = id, title = title, subtitle = if (isGroup) "3 members" else "bbbbbbbb…", isGroup = isGroup,
        unread = unread, lastMessage = "hello from $title", lastSender = title, lastTs = 1_000L, avatar = null,
        memberCount = if (isGroup) 3 else null,
    )

    private fun thread(text: String, sender: String = "b".repeat(64), outgoing: Boolean = false) = ThreadRow(
        id = "m-$text", text = text, senderKey = sender, senderName = if (outgoing) "You" else "Bea",
        outgoing = outgoing, time = "12:00", showHeader = true, avatar = null, unsupported = false,
    )

    private fun baseState(selected: String, rows: List<ThreadRow>, draft: String = "") = ChatUiState(
        rail = listOf(rail("peer-a", "Bea"), rail("peer-b", "Cy"), rail("group:x:1", "Design", isGroup = true, unread = 2)),
        selected = when (selected) {
            "peer-a" -> rail("peer-a", "Bea")
            "peer-b" -> rail("peer-b", "Cy")
            else -> rail("group:x:1", "Design", isGroup = true)
        },
        thread = rows,
        members = emptyList(),
        selfName = "Alex",
        selfAvatar = null,
        draft = draft,
        status = "Online",
        offline = false,
        canSend = true,
        error = null,
    )

    @Test
    fun `clicking a conversation in the rail switches the thread`() = runComposeUiTest {
        var state by mutableStateOf(baseState("peer-a", listOf(thread("hello from Bea"))))
        setContent {
            ChatScreen(state, ChatActions(select = { id ->
                state = when (id) {
                    "peer-b" -> baseState("peer-b", listOf(thread("hello from Cy", sender = "c".repeat(64))))
                    "group:x:1" -> baseState("group:x:1", listOf(thread("hello from the group")))
                    else -> baseState("peer-a", listOf(thread("hello from Bea")))
                }
            }))
        }
        onNodeWithTag("rail-item-peer-b").assertExists().performClick()
        waitForIdle()
        onNodeWithTag("message-m-hello from Cy").assertExists()
    }

    @Test
    fun `Enter sends the composed text and clears the composer`() = runComposeUiTest {
        val sent = mutableListOf<String>()
        setContent { ChatScreen(baseState("peer-a", listOf(thread("hello from Bea"))), ChatActions(send = { sent += it })) }
        val composer = onNodeWithTag("composer")
        composer.performClick()
        composer.performTextInput("hello there")
        composer.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("hello there"), sent)
        assertEquals("", composerText(composer))
    }

    @Test
    fun `Shift Enter inserts a newline without sending`() = runComposeUiTest {
        val sent = mutableListOf<String>()
        setContent { ChatScreen(baseState("peer-a", emptyList()), ChatActions(send = { sent += it })) }
        val composer = onNodeWithTag("composer")
        composer.performClick()
        composer.performTextInput("first")
        composer.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        composer.performTextInput("second")
        waitForIdle()
        composer.assertTextContains("first\nsecond")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `Ctrl+K opens the quick switcher and Enter jumps to the first match`() = runComposeUiTest {
        val selected = mutableListOf<String>()
        setContent { ChatScreen(baseState("peer-a", listOf(thread("hello from Bea"))), ChatActions(select = { selected += it })) }
        val composer = onNodeWithTag("composer")
        composer.performClick()
        composer.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } }
        waitForIdle()
        onNodeWithTag("quick-switcher").assertExists()
        val input = onNodeWithTag("quick-switcher-input")
        input.performTextInput("des")
        input.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("group:x:1"), selected)
        onNodeWithTag("quick-switcher").assertDoesNotExist()
    }

    @Test
    fun `an empty conversation shows an honest empty state not a blank pane`() = runComposeUiTest {
        setContent { ChatScreen(baseState("peer-b", emptyList()), ChatActions()) }
        onNodeWithTag("thread-empty").assertExists()
        onNodeWithText("No messages yet").assertExists()
    }

    @Test
    fun `an unknown message type renders the honest label and never the raw wire text`() = runComposeUiTest {
        val row = DesktopUi.buildThread(
            listOf(DesktopMessage("m1", "peer-a", "b".repeat(64), "TYPE:HOLOGRAM|secret", 1_000L, false)),
            "a".repeat(64), { "Bea" }, { null }, 1_000L,
        ).single()
        setContent { ChatScreen(baseState("peer-a", listOf(row)), ChatActions()) }
        onNodeWithText(DesktopUi.UNSUPPORTED).assertExists()
        onNodeWithText("TYPE:HOLOGRAM", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a missing avatar renders initials instead of a blank hole`() = runComposeUiTest {
        val row = DesktopUi.buildThread(
            listOf(DesktopMessage("m-avatar", "peer-a", "b".repeat(64), "hello from Bea", 1_000L, false)),
            "a".repeat(64), { "Bea" }, { null }, 1_000L,
        ).single()
        setContent { ChatScreen(baseState("peer-a", listOf(row)), ChatActions()) }
        onNodeWithTag("message-m-avatar").assertExists()
        assertTrue(onAllNodesWithText("BE").fetchSemanticsNodes().isNotEmpty())
        onNodeWithTag("message-m-avatar").assertExists()
    }

    @Test
    fun `a group conversation shows read only members and permits posting`() = runComposeUiTest {
        val state = baseState("group:x:1", listOf(thread("hello from the group"))).copy(
            members = listOf(
                MemberRow("a".repeat(64), "Alex", isOwner = true, isSelf = true, avatar = null),
                MemberRow("b".repeat(64), "Bea", isOwner = false, isSelf = false, avatar = null),
            ),
        )
        setContent { ChatScreen(state, ChatActions()) }
        onNodeWithTag("members-panel").assertExists()
        onNodeWithText("Members — 2").assertExists()
        onNodeWithText("Creator").assertExists()
        onNodeWithTag("group-settings").assertDoesNotExist()
        onNodeWithTag("new-group").assertDoesNotExist()
        onNodeWithText("Add someone").assertDoesNotExist()
        onNodeWithText("✕").assertDoesNotExist()
        onNodeWithText("Edit groups on your phone").assertExists()
        onNodeWithTag("create-group").assertDoesNotExist()
        onNodeWithTag("group-name").assertDoesNotExist()
        onNodeWithText("Rename").assertDoesNotExist()
        onNodeWithText("Change group photo…", substring = true).assertDoesNotExist()
        onNodeWithTag("composer").assertIsEnabled()
    }

    @Test
    fun `failed send restores local composer without changing selection`() = runComposeUiTest {
        var state by mutableStateOf(baseState("peer-a", emptyList()))
        setContent { ChatScreen(state, ChatActions(send = {
            state = state.copy(draft = it, error = "Delivery failed", draftVersion = state.draftVersion + 1)
        })) }
        val composer = onNodeWithTag("composer")
        composer.performClick()
        composer.performTextInput("retry me")
        onNodeWithTag("send").performClick()
        waitForIdle()
        composer.assertTextContains("retry me")
        onNodeWithText("Delivery failed").assertExists()
    }

    @Test
    fun `a failed send shows the error banner and keeps the draft`() = runComposeUiTest {
        val state = baseState("peer-a", listOf(thread("hello from Bea")), draft = "unsent").copy(error = "Offline: try again")
        setContent { ChatScreen(state, ChatActions()) }
        onNodeWithText("Offline: try again").assertExists()
        onNodeWithTag("composer").assertTextContains("unsent", substring = true)
    }

    @Test
    fun `a missing conversation shows the select prompt`() = runComposeUiTest {
        setContent { ChatScreen(ChatUiState(rail = emptyList(), status = "Offline — no mailbox configured", offline = true), ChatActions()) }
        onNodeWithText("No conversation selected").assertExists()
        onNodeWithText("Offline — no mailbox configured").assertExists()
    }
}


/** A real engine, store and controller on a disposable directory; only the network is a stub. */
private class ChatHarness(
    tmp: TemporaryFolder,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    wrap: (ChatReads) -> ChatReads = { it },
    sendOutcome: () -> SendResult,
) : AutoCloseable {
    class StubTransport(val outcome: () -> SendResult) : Transport {
        private val _status = MutableStateFlow(TransportStatus.ONLINE)
        override val status: StateFlow<TransportStatus> = _status.asStateFlow()
        override val incoming: Flow<IncomingEnvelope> = emptyFlow()
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun reconnect() = Unit
        override suspend fun send(recipientPubkeyHex: String, payload: RelayPayload, relayHints: List<String>, ephemeral: Boolean) = outcome()
    }

    val peer = "b".repeat(64)
    val paths = DesktopPaths(tmp.newFolder().toPath(), tmp.newFolder().toPath())
    private val secrets = FileSecretStore(paths.secretFile)
    private val peerPaths = DesktopPaths(tmp.newFolder().toPath(), tmp.newFolder().toPath())
    private val peerMaterial = DesktopKeyMaterial(peerPaths, FileSecretStore(peerPaths.secretFile))
    val store = DesktopMessageStore(paths.databaseFile, secrets)
    val engine = DesktopEngine(
        DesktopIdentity(paths, secrets), DesktopKeyMaterial(paths, secrets), store, StubTransport(sendOutcome),
        peerInnerKeys = mapOf(peer to peerMaterial.publicKeyBase64()),
    )
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val chat = DesktopChat(engine, scope, reads = wrap(EngineChatReads(engine)))

    init { store.ensureConversation(peer, title = "Bea") }

    fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; state=${chat.state.value}" }
            Thread.sleep(20)
        }
    }

    override fun close() { scope.cancel(); engine.close(); store.close() }
}

/** Wall-clock bounded wait: Compose's own waitUntil spins on test-clock time and can hang the JVM. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.eventually(what: String = "condition", timeoutMs: Long = 8_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        waitForIdle()
        if (runCatching(condition).getOrDefault(false)) return
        if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
        Thread.sleep(25)
    }
}

@OptIn(ExperimentalTestApi::class)
private fun composerText(node: SemanticsNodeInteraction): String =
    node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

@OptIn(ExperimentalTestApi::class)
class DesktopChatControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a failed send puts the draft straight back into the published state`() {
        ChatHarness(tmp, sendOutcome = { SendResult.Failed("Delivery failed", retryable = true) }).use { h ->
            h.chat.start()
            h.waitFor("selection") { h.chat.state.value.selected?.id == h.peer }
            h.chat.draftChanged("retry me")
            h.chat.send("retry me")
            h.waitFor("error") { h.chat.state.value.error == "Delivery failed" }
            assertEquals("retry me", h.chat.state.value.draft)
        }
    }

    @Test
    fun `a pending debounced draft write cannot resurrect text after a successful send`() {
        ChatHarness(tmp, sendOutcome = { SendResult.Sent(1) }).use { h ->
            h.chat.start()
            h.waitFor("selection") { h.chat.state.value.selected?.id == h.peer }
            h.chat.draftChanged("hello")
            h.chat.send("hello")
            h.waitFor("sent") { h.store.messages(h.peer).any { it.outgoing } }
            Thread.sleep(600) // longer than the 250 ms debounce
            assertEquals("", h.store.conversationDraft(h.peer))
            h.chat.refresh()
            h.waitFor("refresh") { h.chat.state.value.thread.isNotEmpty() }
            assertEquals("", h.chat.state.value.draft)
        }
    }

    @Test
    fun `unknown typed body goes through the controller and message row path as an honest label`() = runComposeUiTest {
        val h = ChatHarness(tmp, sendOutcome = { SendResult.Sent(1) })
        try {
            h.store.insertMessage(DesktopMessage("hologram-1", h.peer, h.peer, "TYPE:HOLOGRAM|secret", 1_000L, false))
            h.chat.start()
            h.waitFor("thread") { h.chat.state.value.thread.size == 1 }
            setContent {
                var state by androidx.compose.runtime.remember { mutableStateOf(h.chat.state.value) }
                androidx.compose.runtime.LaunchedEffect(Unit) { h.chat.state.collect { state = it } }
                ChatScreen(state, ChatActions())
            }
            eventually { onAllNodesWithText(DesktopUi.UNSUPPORTED).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("TYPE:HOLOGRAM", substring = true).assertDoesNotExist()
        } finally { h.close() }
    }

    @Test
    fun `a peer without a stored avatar renders initials from the controller row`() = runComposeUiTest {
        val h = ChatHarness(tmp, sendOutcome = { SendResult.Sent(1) })
        try {
            h.store.insertMessage(DesktopMessage("plain-1", h.peer, h.peer, "hello from Bea", 1_000L, false))
            h.chat.start()
            h.waitFor("thread") { h.chat.state.value.thread.size == 1 }
            val row = h.chat.state.value.thread.single()
            assertTrue(row.avatar == null)
            setContent {
                var state by androidx.compose.runtime.remember { mutableStateOf(h.chat.state.value) }
                androidx.compose.runtime.LaunchedEffect(Unit) { h.chat.state.collect { state = it } }
                ChatScreen(state, ChatActions())
            }
            eventually { onAllNodesWithText(DesktopUi.initials(row.senderName)).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("message-plain-1").assertExists()
        } finally { h.close() }
    }

    @Test
    fun `failed send restores the composer through the real controller every time`() = runComposeUiTest {
        val h = ChatHarness(tmp, sendOutcome = { SendResult.Failed("Delivery failed", retryable = true) })
        try {
            h.chat.start()
            h.waitFor("selection") { h.chat.state.value.selected?.id == h.peer }
            setContent {
                var state by androidx.compose.runtime.remember { mutableStateOf(h.chat.state.value) }
                androidx.compose.runtime.LaunchedEffect(Unit) { h.chat.state.collect { state = it } }
                ChatScreen(state, ChatActions(send = h.chat::send, draftChanged = h.chat::draftChanged, dismissError = h.chat::dismissError))
            }
            val composer = onNodeWithTag("composer")
            composer.performClick()
            composer.performTextInput("retry me")
            onNodeWithTag("send").performClick()
            eventually { onAllNodesWithText("Delivery failed").fetchSemanticsNodes().isNotEmpty() }
            eventually { composerText(composer) == "retry me" }
            // Second identical failure (same text, same error string) must restore again.
            onNodeWithTag("send").performClick()
            try { eventually { composerText(composer) == "retry me" } } catch (e: AssertionError) {
                throw AssertionError("second restore missing; state=${h.chat.state.value} text='${composerText(composer)}'", e)
            }
            assertEquals("retry me", composerText(composer))
        } finally { h.close() }
    }

    private class CountingReads(
        private val d: ChatReads,
        private val gate: java.util.concurrent.CountDownLatch? = null,
        val entered: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1),
    ) : ChatReads by d {
        val profileCalls = java.util.concurrent.atomic.AtomicInteger()
        val lastCalls = java.util.concurrent.atomic.AtomicInteger()
        val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)
        override fun profiles(): Map<String, DesktopProfile> { threads += Thread.currentThread().name; profileCalls.incrementAndGet(); return d.profiles() }
        override fun lastMessage(conversationId: String): DesktopMessage? { threads += Thread.currentThread().name; lastCalls.incrementAndGet(); return d.lastMessage(conversationId) }
        override fun conversations(): List<DesktopConversation> {
            threads += Thread.currentThread().name
            val result = d.conversations()
            // The first read returns its (soon stale) snapshot only after the test lets it go.
            if (gate != null && first.getAndSet(false)) { entered.countDown(); gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
            return result
        }
    }

    private fun uiDispatcher(): kotlinx.coroutines.CoroutineDispatcher =
        java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ui-test").apply { isDaemon = true } }.asCoroutineDispatcher()

    @Test
    fun `refresh loads profiles once per rebuild, off the ui thread`() {
        lateinit var counting: CountingReads
        ChatHarness(tmp, uiDispatcher(), wrap = { CountingReads(it).also { c -> counting = c } }, sendOutcome = { SendResult.Sent(1) }).use { h ->
            val keys = (1..25).map { "%064x".format(it + 1000) }
            keys.forEachIndexed { i, key ->
                h.store.ensureConversation(key, title = "Peer $i")
                h.store.saveProfileName(key, "Peer $i", 1)
                h.store.insertMessage(DesktopMessage("m$i", key, key, "hi $i", 1_000L + i, false))
            }
            h.chat.start()
            h.waitFor("rail") { h.chat.state.value.rail.size >= 25 }
            Thread.sleep(300) // let revision-triggered rebuilds settle
            counting.profileCalls.set(0); counting.lastCalls.set(0)
            h.chat.refresh()
            h.waitFor("one rebuild") { counting.profileCalls.get() >= 1 }
            Thread.sleep(300)
            assertEquals("profiles are preloaded once, not looked up per row", 1, counting.profileCalls.get())
            assertTrue("one lastMessage per rail row, not per message", counting.lastCalls.get() <= 26)
            assertFalse("store work must not run on the ui thread: ${counting.threads}", counting.threads.any { it.startsWith("ui-test") })
        }
    }

    @Test
    fun `a slow older refresh can never overwrite a newer state`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        lateinit var counting: CountingReads
        ChatHarness(tmp, Dispatchers.Default, wrap = { CountingReads(it, gate).also { c -> counting = c } }, sendOutcome = { SendResult.Sent(1) }).use { h ->
            h.chat.refresh() // generation 1 blocks inside conversations() holding the old snapshot (no start(): no revision follow-up to heal it)
            assertTrue("generation 1 never reached the gate", counting.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val other = "c".repeat(64)
            h.store.ensureConversation(other, title = "Cy")
            h.store.insertMessage(DesktopMessage("new1", other, other, "fresh", 9_000L, false))
            h.chat.refresh() // newer generation sees both conversations
            h.waitFor("fresh state") { h.chat.state.value.rail.size == 2 }
            gate.countDown() // let the stale generation finish
            Thread.sleep(500)
            assertEquals("stale snapshot must not replace the newer one", 2, h.chat.state.value.rail.size)
        }
    }

    @Test
    fun `controller offers no roster editing and a group stays postable`() {
        val names = ChatActions::class.java.declaredFields.map { it.name }.toSet()
        for (editor in listOf("createGroup", "addMember", "removeMember", "renameGroup", "setGroupAvatar")) {
            assertFalse("ChatActions must not expose $editor", editor in names)
        }
        val controller = DesktopChat::class.java.declaredMethods.map { it.name }.toSet()
        for (editor in listOf("createGroup", "addMember", "removeMember", "renameGroup", "setGroupAvatar")) {
            assertFalse("DesktopChat must not expose $editor", editor in controller)
        }
    }
}
