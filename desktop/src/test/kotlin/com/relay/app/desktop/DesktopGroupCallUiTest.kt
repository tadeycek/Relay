package com.relay.app.desktop

import androidx.compose.ui.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DesktopGroupCallUiTest {
    private val conversation = "group:${"a".repeat(64)}:123e4567-e89b-42d3-a456-426614174000"
    private val selected = RailItem(conversation, "Design", "3 members", true, 0, "", "", 0, null, 3)
    private fun screen() = ChatUiState(rail = listOf(selected), selected = selected, status = "Online", canSend = true)

    @Test fun groupHasVoiceStart() = runComposeUiTest {
        var started = 0
        setContent { ChatScreen(screen(), ChatActions(startGroupCall = { started++ })) }
        onNodeWithTag("group-call-start").performClick()
        assertEquals(1, started)
    }

    @Test fun incomingBannerHasJoinAndHonestLabel() = runComposeUiTest {
        var joined = 0
        val ui = DesktopGroupCallUi(banners = mapOf(conversation to GroupCallBanner(conversation, "0".repeat(32), "b".repeat(64), "Bea")))
        setContent { ChatScreen(screen(), ChatActions(joinGroupCall = { joined++ }), groupCall = ui) }
        onNodeWithTag("group-call-start").assertDoesNotExist()
        onNodeWithTag("group-call-join").performClick()
        onNodeWithText(ui.label).assertExists()
        assertEquals(1, joined)
    }

    @Test fun liveGroupShowsRosterWarningDevicesAndMediaControls() = runComposeUiTest {
        var muted = false; var deafened = false; var left = 0; var volume = 0f
        val outsider = GroupPersonUi("e".repeat(64), "Eve", false, false, listOf(GroupDeviceUi("0000000000000001", "phone", false, false, true, 1f)))
        val ui = DesktopGroupCallUi(stage = GroupCallStage.ACTIVE, conversationId = conversation, groupName = "Design", people = listOf(outsider))
        setContent { ChatScreen(screen(), ChatActions(muteGroupCall = { muted = it }, deafenGroupCall = { deafened = it }, leaveGroupCall = { left++ }, groupVolume = { _, v -> volume = v }), groupCall = ui) }
        onNodeWithText("Not in this group", substring = true).assertExists()
        onNodeWithText(ui.label).assertExists()
        onNodeWithTag("group-call-mute").performClick()
        onNodeWithTag("group-call-deafen").performClick()
        onNodeWithTag("group-call-volume-0000000000000001").performClick()
        onNodeWithTag("group-call-leave").performClick()
        assertEquals(true, muted); assertEquals(true, deafened); assertEquals(0.5f, volume); assertEquals(1, left)
    }
}
