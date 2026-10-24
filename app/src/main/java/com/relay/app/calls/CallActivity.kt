package com.relay.app.calls

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.relay.app.ui.theme.RelayTheme

/**
 * The call-only screen (WP13 step 4). It is a separate Activity on purpose: answering a call must not
 * unlock messaging, and the calculator lock is not involved here at all. Opening a chat from a call
 * still requires the normal code in [com.relay.app.MainActivity].
 *
 * A calculator-only install never reaches this Activity: [CallController.initialize] refuses, and the
 * screen finishes immediately if it is somehow started.
 */
class CallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (com.relay.app.calculator.CalculatorOnly.enabled(this) || !CallController.initialize(this)) {
            finish()
            return
        }
        setContent {
            RelayTheme {
                CallScreen(
                    callId = intent.getStringExtra(EXTRA_CALL_ID),
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
    }
}
