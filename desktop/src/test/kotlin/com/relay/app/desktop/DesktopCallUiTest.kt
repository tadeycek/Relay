package com.relay.app.desktop

import androidx.compose.ui.test.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Call button, ring card, in-call bar and call-log rows in the real ChatScreen. */
@OptIn(ExperimentalTestApi::class)
class DesktopCallUiTest {
    private fun rail(id: String, title: String, group: Boolean = false) = RailItem(
        id = id, title = title, subtitle = "bbbb…", isGroup = group, unread = 0, lastMessage = "hi", lastSender = title,
        lastTs = 1_000L, avatar = null, memberCount = if (group) 3 else null,
    )
    private fun state(canCall: Boolean, group: Boolean = false, thread: List<ThreadRow> = emptyList()) = ChatUiState(
        rail = listOf(rail("peer-a", "Bea")), selected = if (group) rail("group:x:1", "Design", true) else rail("peer-a", "Bea"),
        thread = thread, status = "Online", canSend = true, canCall = canCall,
    )
    private fun ui(stage: DesktopCallStage, status: String, connectedAt: Long? = null, muted: Boolean = false, notice: String? = null) =
        DesktopCallUi(stage, "0".repeat(32), "b".repeat(64), "Bea", connectedAt, muted, status, notice)

    @Test fun callButtonShowsOnlyForAnEligibleDirectContactAndStartsTheCall() = runComposeUiTest {
        var started = 0
        setContent { ChatScreen(state(true), ChatActions(startCall = { started++ })) }
        onNodeWithTag("call-button").assertExists().performClick()
        waitForIdle()
        assertEquals(1, started)
    }

    @Test fun noCallButtonWhenNotEligible() = runComposeUiTest {
        setContent { ChatScreen(state(false), ChatActions()) }
        onNodeWithTag("call-button").assertDoesNotExist()
    }

    @Test fun noCallButtonInAGroup() = runComposeUiTest {
        setContent { ChatScreen(state(true, group = true), ChatActions()) }
        onNodeWithTag("call-button").assertDoesNotExist()
    }

    @Test fun callButtonIsDisabledWhileACallIsLive() = runComposeUiTest {
        setContent { ChatScreen(state(true), ChatActions(), call = ui(DesktopCallStage.ACTIVE, "Connected", 1L), callTicks = false) }
        onNodeWithTag("call-button").assertIsNotEnabled()
    }

    @Test fun incomingCallShowsRingCardWithAcceptAndDecline() = runComposeUiTest {
        var accepted = 0; var declined = 0
        setContent { ChatScreen(state(true), ChatActions(acceptCall = { accepted++ }, declineCall = { declined++ }), call = ui(DesktopCallStage.RINGING_IN, "Incoming call")) }
        onNodeWithTag("call-ring").assertExists()
        onNode(hasAnyAncestor(hasTestTag("call-ring")) and hasText("Bea")).assertExists()
        onNodeWithTag("call-accept").performClick()
        onNodeWithTag("call-decline").performClick()
        waitForIdle()
        assertEquals(1, accepted); assertEquals(1, declined)
    }

    @Test fun ringCardCanBeLeftToTheSeparateRingWindow() = runComposeUiTest {
        setContent { ChatScreen(state(true), ChatActions(), call = ui(DesktopCallStage.RINGING_IN, "Incoming call"), showRing = false) }
        onNodeWithTag("call-ring").assertDoesNotExist()
    }

    @Test fun activeCallShowsBarWithTimerStateMuteAndHangUp() = runComposeUiTest {
        val muted = mutableListOf<Boolean>(); var hung = 0
        setContent {
            ChatScreen(state(true), ChatActions(toggleMute = { muted += it }, hangUp = { hung++ }),
                call = ui(DesktopCallStage.ACTIVE, "Connected", connectedAt = 1_000L), callClock = { 66_000L }, callTicks = false)
        }
        onNodeWithTag("call-bar").assertExists()
        onNodeWithTag("call-status").assertTextEquals("Connected")
        onNodeWithTag("call-timer").assertTextEquals("1:05")
        onNodeWithTag("call-mute").performClick()
        onNodeWithTag("call-hangup").performClick()
        waitForIdle()
        assertEquals(listOf(true), muted); assertEquals(1, hung)
    }

    @Test fun mutedCallOffersUnmute() = runComposeUiTest {
        val muted = mutableListOf<Boolean>()
        setContent { ChatScreen(state(true), ChatActions(toggleMute = { muted += it }), call = ui(DesktopCallStage.ACTIVE, "Connected", 1L, muted = true), callClock = { 2_000L }, callTicks = false) }
        onNodeWithTag("call-mute").assertTextEquals("Unmute")
        onNodeWithTag("call-mute").performClick()
        waitForIdle()
        assertEquals(listOf(false), muted)
    }

    @Test fun outgoingCallShowsStateTextAndHangUpButNoTimer() = runComposeUiTest {
        setContent { ChatScreen(state(true), ChatActions(), call = ui(DesktopCallStage.RINGING_OUT, "Calling…")) }
        onNodeWithTag("call-status").assertTextEquals("Calling…")
        onNodeWithTag("call-timer").assertDoesNotExist()
        onNodeWithTag("call-hangup").assertExists()
    }

    @Test fun noticeIsShownInTheBar() = runComposeUiTest {
        setContent { ChatScreen(state(true), ChatActions(), call = ui(DesktopCallStage.RINGING_OUT, "Calling…", notice = "Couldn't reach the mailbox.")) }
        onNodeWithTag("call-notice").assertTextContains("Couldn't reach the mailbox.")
    }

    @Test fun callLogRowRendersAsACenteredNoteNotABubble() = runComposeUiTest {
        val row = ThreadRow("call-log-x", "Missed call", "b".repeat(64), "Bea", false, "12:00", false, null, false, callLog = true)
        setContent { ChatScreen(state(true, thread = listOf(row)), ChatActions()) }
        onNodeWithTag("call-log-call-log-x").assertExists()
        onNode(hasAnyAncestor(hasTestTag("call-log-call-log-x")) and hasText("Missed call", substring = true)).assertExists()
        onNodeWithTag("message-call-log-x").assertDoesNotExist()
    }

    @Test fun threadWithAMessageIdEqualToTheOldCallRowKeyStillRenders() = runComposeUiTest {
        val peer = "b".repeat(64); val callId = "0123456789abcdef0123456789abcdef"
        val msg = DesktopMessage("call-log-$callId", peer, peer, "hello", 1_000L, false)
        val rows = DesktopUi.buildThread(listOf(msg), "a".repeat(64), { "Bea" }, { null }, 10_000L,
            calls = listOf(DesktopCallRecord(callId, peer, true, com.relay.app.call.CallOutcome.MISSED, null, 0, 2_000L)), contactName = "Bea")
        setContent { ChatScreen(state(true, thread = rows), ChatActions()) } // a duplicate LazyColumn key throws here
        onNodeWithTag("message-list").assertExists()
        onNode(hasText("Missed call", substring = true)).assertExists()
    }

    @Test fun muteIsNotOfferedBeforeTheCallIsActive() = runComposeUiTest {
        setContent { ChatScreen(state(true), ChatActions(), call = ui(DesktopCallStage.CONNECTING, "Connecting…")) }
        onNodeWithTag("call-bar").assertExists()
        onNodeWithTag("call-mute").assertDoesNotExist()
        onNodeWithTag("call-hangup").assertExists()
    }

    @Test fun ringCardBoundsARemoteControlledNameToOneLine() = runComposeUiTest {
        val hostile = "Mallory\n" + "W".repeat(400) + "\nmore\nlines"
        setContent { ChatScreen(state(true), ChatActions(), call = ui(DesktopCallStage.RINGING_IN, "Incoming call").copy(peerName = hostile)) }
        onNode(hasText(hostile, substring = true)).assertDoesNotExist()
        val node = onNode(hasAnyAncestor(hasTestTag("call-ring")) and hasText("Mallory", substring = true))
        node.assertExists()
        val text = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("name bounded: ${text.length}", text.length <= 65 && !text.contains('\n'))
    }

    @Test fun commandRejectedWhileIdleIsVisible() = runComposeUiTest {
        setContent { ChatScreen(state(true), ChatActions(), call = DesktopCallUi(notice = "That contact can't be called.")) }
        onNodeWithTag("call-notice").assertTextContains("That contact can't be called.")
    }
}
