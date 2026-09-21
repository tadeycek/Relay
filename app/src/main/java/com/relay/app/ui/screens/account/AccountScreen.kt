package com.relay.app.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.screens.settings.SettingsSections
import com.relay.app.ui.screens.settings.SettingsViewModel
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.Surface3
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.util.RelayPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The right-hand tab: who you are, your QR code, connection status, and all settings. */
@Composable
fun AccountScreen(navController: NavController) {
    val vm: SettingsViewModel = viewModel()

    Scaffold(
        topBar = { RelayTopBar(title = "Account") },
        containerColor = Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProfileCard(
                status = vm.connectionStatus,
                onShowQr = { navController.navigate(Screen.QrExchange.route) },
            )
            SettingsSections(vm)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileCard(status: TransportStatus, onShowQr: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { RelayPreferences(context) }
    var name by remember { mutableStateOf(prefs.myName) }
    var editing by remember { mutableStateOf(false) }

    // The identity key may need the Keystore on first use; keep that off the main thread.
    val relayId by produceState("…") {
        value = withContext(Dispatchers.IO) {
            runCatching { NostrIdentity.publicKeyHex(context) }
                .map { "${it.take(8)}…${it.takeLast(4)}" }
                .getOrDefault("unavailable")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(Surface1)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(64.dp).clip(RectangleShape).background(Surface3),
            ) {
                Text(
                    text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    color = Accent,
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 28.sp,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name.ifBlank { "Set your name" },
                    color = if (name.isBlank()) TextSecondary else TextPrimary,
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Relay ID  $relayId",
                    color = TextSecondary,
                    fontFamily = IbmPlexMono,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when (status) {
                        TransportStatus.ONLINE -> "Connected"
                        TransportStatus.CONNECTING -> "Connecting..."
                        TransportStatus.OFFLINE -> "Offline"
                        TransportStatus.STOPPED -> "Stopped"
                        TransportStatus.WAITING_FOR_TOR -> "Waiting for Tor"
                    },
                    color = if (status == TransportStatus.ONLINE) Accent else TextSecondary,
                    fontFamily = IbmPlexMono,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit name", tint = TextSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onShowQr,
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.QrCode, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("My QR code  /  scan a friend", color = Accent, fontFamily = IbmPlexSans)
        }
    }

    if (editing) {
        var draft by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = Surface1,
            titleContentColor = TextPrimary,
            title = { Text("Your name", fontFamily = IbmPlexSans) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(30) },
                    singleLine = true,
                    label = { Text("Shown on your QR code", fontFamily = IbmPlexSans) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Accent,
                        focusedContainerColor = Surface2,
                        unfocusedContainerColor = Surface2,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val trimmed = draft.trim()
                        prefs.myName = trimmed
                        name = trimmed
                        editing = false
                    },
                ) { Text("Save", color = Accent, fontFamily = IbmPlexSans) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) {
                    Text("Cancel", color = TextSecondary, fontFamily = IbmPlexSans)
                }
            },
        )
    }
}
