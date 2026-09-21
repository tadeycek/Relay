package com.relay.app.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.ui.components.ChipKind
import com.relay.app.ui.components.Glyph
import com.relay.app.ui.components.LinkRow
import com.relay.app.ui.components.RelayBottomSheet
import com.relay.app.ui.components.RelayTextField
import com.relay.app.ui.components.RowDivider
import com.relay.app.ui.components.ScreenHeader
import com.relay.app.ui.components.SecondaryButton
import com.relay.app.ui.components.PrimaryButton
import com.relay.app.ui.components.StatusChip
import com.relay.app.ui.glyph.GlyphState
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.screens.settings.AccountPage
import com.relay.app.ui.screens.settings.SettingsViewModel
import com.relay.app.ui.screens.settings.accountPageSummary
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.util.RelayPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The right-hand tab: who you are, your code, and a short list that leads to the settings pages. */
@Composable
fun AccountScreen(navController: NavController) {
    val vm: SettingsViewModel = viewModel()

    // The user may install or start Orbot in another app and come back: re-check on every resume.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshOrbotInstalled()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        ScreenHeader(title = "Account")
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Profile(status = vm.connectionStatus, onShowCode = { navController.navigate(Screen.QrExchange.route) })
            AccountPage.entries.forEach { page ->
                RowDivider()
                LinkRow(
                    headline = page.title,
                    supporting = accountPageSummary(page, vm),
                    onClick = { navController.navigate(Screen.AccountPage.routeFor(page)) },
                )
            }
            RowDivider()
        }
    }
}

@Composable
private fun Profile(status: TransportStatus, onShowCode: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { RelayPreferences(context) }
    var name by remember { mutableStateOf(prefs.myName) }
    var editing by remember { mutableStateOf(false) }

    // The identity key may need the Keystore on first use; keep that off the main thread.
    val key by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { NostrIdentity.publicKeyHex(context) }.getOrNull() }
    }

    Column(modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RelaySpacing.lg)) {
            Glyph(
                seed = key ?: name,
                state = GlyphState.SELF,
                size = 72.dp,
                description = "Your glyph",
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RelaySpacing.xs)) {
                Text(
                    text = name.ifBlank { "Set your name" },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (name.isBlank()) TextSecondary else TextPrimary,
                )
                Text(
                    text = key?.let { "${it.take(8)}…${it.takeLast(4)}" } ?: "…",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = IbmPlexMono),
                    color = TextSecondary,
                )
                StatusChip(
                    text = when (status) {
                        TransportStatus.ONLINE -> "Connected"
                        TransportStatus.CONNECTING -> "Connecting"
                        TransportStatus.OFFLINE -> "Offline"
                        TransportStatus.STOPPED -> "Stopped"
                        TransportStatus.WAITING_FOR_TOR -> "Waiting for Tor"
                    },
                    kind = if (status == TransportStatus.ONLINE) ChipKind.VERIFIED else ChipKind.NEUTRAL,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = RelaySpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(RelaySpacing.sm),
        ) {
            PrimaryButton("Show my code", onShowCode, Modifier.weight(1f))
            SecondaryButton("Edit name", { editing = true }, Modifier.weight(1f))
        }
    }

    if (editing) {
        var draft by remember { mutableStateOf(name) }
        RelayBottomSheet(onDismiss = { editing = false }) {
            Text("Your name", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            RelayTextField(
                value = draft,
                onValueChange = { draft = it.take(30) },
                label = "Shown next to your code",
                modifier = Modifier.fillMaxWidth().padding(top = RelaySpacing.lg),
            )
            PrimaryButton(
                "Save name",
                {
                    val trimmed = draft.trim()
                    prefs.myName = trimmed
                    name = trimmed
                    editing = false
                },
                Modifier.fillMaxWidth().padding(top = RelaySpacing.lg),
                enabled = draft.isNotBlank(),
            )
        }
    }
}
