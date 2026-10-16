package com.relay.app.ui.screens.settings

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relay.app.BuildConfig
import com.relay.app.calculator.CalculatorAccessStore
import com.relay.app.calculator.OwnerSync
import com.relay.app.ui.components.SectionHeader
import com.relay.app.ui.components.ToggleRow
import kotlinx.coroutines.delay

@Composable
internal fun OwnerDiagnosticsSettings() {
    val context = LocalContext.current
    val store = remember { CalculatorAccessStore.get(context) }
    var enabled by remember { mutableStateOf(store.diagnosticsChoice().second) }
    var pending by remember { mutableStateOf(store.diagnosticsPending()) }
    var confirming by remember { mutableStateOf(false) }
    val relayRecovery = BuildConfig.OWNER_PUBLIC_KEY.isNotBlank()
    val serviceConfigured = relayRecovery || (BuildConfig.OWNER_SERVICE_URL.isNotBlank() && BuildConfig.OWNER_ENROLLMENT_TOKEN.isNotBlank())
    LaunchedEffect(store) {
        while (true) {
            enabled = store.diagnosticsChoice().second
            pending = store.diagnosticsPending()
            delay(1_000)
        }
    }
    SectionHeader("Optional owner diagnostics")
    ToggleRow(
        "Share diagnostics with the app owner",
        enabled,
        { share ->
            if (share) confirming = true else {
                store.setDiagnosticsEnabled(false)
                enabled = false
                pending = true
                OwnerSync.kick()
            }
        },
        supporting = when {
            !serviceConfigured -> "No Control Panel service is configured."
            enabled -> if (relayRecovery) "Sharing device/app versions, network type and app permission states through encrypted relays. Control Panel does not observe your IP address." else "Sharing device/app versions, network type, app permission states and the connection IP seen by Control Panel."
            pending -> "Off. Stopping sharing requests deletion of the last report; deletion waits for a connection if offline."
            else -> "Off. No optional phone diagnostics are shared."
        },
        enabled = serviceConfigured,
    )
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Share phone diagnostics?") },
        text = { Text("The app owner will receive your device manufacturer/model, Android and app versions, " +
            (if (relayRecovery) "network type and this app's camera/location/notification permission states. The encrypted relay channel does not reveal your IP address to Control Panel. " else "network type, this app's camera/location/notification permission states, and the IP address seen by their service. ") +
            "Updates continue while the app is running, including in the background. " +
            "The address may be a Tor exit or proxy; this will not bypass your Tor settings. Reports use your recovery ID.\n\n" +
            (if (relayRecovery) "Recovery uses encrypted relays. Only the owner can sign an unblock command.\n\n" else "Service: ${BuildConfig.OWNER_SERVICE_URL}\n\n") +
            "No messages, contacts, passcode, precise location or phone identifiers are included. Only the latest report is kept, " +
            "expiring after 7 days without a new report. Turn this off to stop sharing and request deletion. " +
            "The app works without optional diagnostics.",
            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            store.setDiagnosticsEnabled(true)
            enabled = true
            pending = true
            confirming = false
            OwnerSync.kick()
        }) { Text("Agree and share") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep off") } },
    )
}
