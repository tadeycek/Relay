package com.relay.app.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Evidence only: renders the REAL ChatScreen (Call button, in-call bar, call-log row) into a PNG. Skipped unless
 * RELAY_CALL_SHOT_DIR names an absolute output directory. This is an offscreen render, not a packaged-app screenshot.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopCallScreenshotTest {
    @Test fun rendersCallButtonInCallBarAndCallLogRow() = runComposeUiTest {
        val dir = System.getenv("RELAY_CALL_SHOT_DIR")
        assumeTrue("Set RELAY_CALL_SHOT_DIR to render the call UI", dir != null && File(dir).isAbsolute)
        val peer = "5e".repeat(32)
        val rail = RailItem("peer-a", "Bea (demo)", "5e5e…", false, 0, "hi", "Bea", 1_000L, null, null)
        val log = ThreadRow("call-log-x", "Missed call", peer, "Bea (demo)", false, "12:00", false, null, false, callLog = true)
        val state = ChatUiState(rail = listOf(rail), selected = rail, thread = listOf(log), status = "Online", canSend = true, canCall = true)
        val call = DesktopCallUi(DesktopCallStage.ACTIVE, "0".repeat(32), peer, "Bea (demo)", 1_000L, false, "Connected", null)
        setContent { ChatScreen(state, ChatActions(), call = call, callClock = { 66_000L }, callTicks = false) }
        waitForIdle()
        val img = onRoot().captureToImage().toAwtImage()
        ImageIO.write(img, "png", File(dir, "wp14-call-ui.png"))
    }
}
