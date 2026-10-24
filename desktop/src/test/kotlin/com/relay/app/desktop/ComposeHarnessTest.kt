package com.relay.app.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Test

/**
 * Harness probe: proves the Compose Desktop UI-test entry point runs on this machine before the
 * WP07 behaviour tests depend on it. Kept because a future toolchain change that removes the
 * desktop test runtime would otherwise surface as a dozen confusing failures.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeHarnessTest {
    @Test
    fun `compose ui test runtime renders a composable and finds it in the semantics tree`() = runComposeUiTest {
        setContent { Text("relay-harness-ok") }
        onNodeWithText("relay-harness-ok").assertExists()
    }
}
