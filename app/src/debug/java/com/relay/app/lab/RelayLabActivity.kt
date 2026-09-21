package com.relay.app.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Debug-only launcher for the Phase 0 spike. Start with:
 *   adb shell am start -n com.relay.app/.lab.RelayLabActivity
 * "Send probe" publishes a NIP-17 message to the default relays; "Check probe" later asks each
 * relay whether it still has it (run again after 1h, 24h, 72h, 7d to measure retention).
 */
class RelayLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var output by remember { mutableStateOf("Relay lab (debug build only)") }
                    var busy by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    Column(
                        modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch { output = RelayLab.sendProbe(applicationContext); busy = false }
                        }) { Text("Send probe") }
                        Button(enabled = !busy, onClick = {
                            busy = true
                            scope.launch { output = RelayLab.checkProbe(applicationContext); busy = false }
                        }) { Text("Check probe") }
                        Text(output, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
