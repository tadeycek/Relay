package com.relay.app.linking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.relay.app.ui.components.ConfirmDialog
import com.relay.app.ui.components.ListRow
import com.relay.app.ui.components.PrimaryButton
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.RowDivider
import com.relay.app.ui.components.SectionHeader
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Danger
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.screens.qr.RawQrScanView
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Account → Linked computers: the computers signed in to this identity, a way to link another one by
 * scanning the code it shows, and a way to cut one off.
 *
 * The screen is deliberately plain: the decisions live in [PhoneLinkFlow], the scanning is the app's
 * existing camera view ([RawQrScanView]), and removal explains that revocation stops server access
 * but does not take back keys the computer already has.
 */
@Composable
fun LinkedComputersScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val available = remember { PhoneLinkCoordinator.available(context) }

    var devices by remember { mutableStateOf<List<LinkedDevice>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var linkedDevice by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<LinkedDevice?>(null) }

    fun refresh() {
        scope.launch {
            val result = PhoneLinkCoordinator.devices(context)
            result.onSuccess { devices = it; loadFailed = false }.onFailure { loadFailed = true }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        RelayTopBar(title = "Linked computers", onBack = { navController.popBackStack() })

        if (scanning) {
            ScanPanel(
                busy = busy,
                onScanned = { raw ->
                    if (busy) return@ScanPanel
                    busy = true
                    message = null
                    scope.launch {
                        when (val result = PhoneLinkCoordinator.link(context, raw)) {
                            is PhoneLinkResult.Linked -> {
                                linkedDevice = result.deviceId
                                scanning = false
                                refresh()
                            }
                            is PhoneLinkResult.Failed -> message = result.message
                        }
                        busy = false
                    }
                },
                onCancel = { if (!busy) scanning = false },
            )
            return@Column
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "A computer you link gets this phone's identity, your contacts and your settings, so it " +
                    "can send and receive as you. Your messages stay where they are — a new computer starts " +
                    "with empty chats.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.lg),
            )

            PrimaryButton(
                text = if (busy) "Linking…" else "Link a computer",
                onClick = { message = null; linkedDevice = null; scanning = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = RelaySpacing.gutter),
                enabled = available && !busy,
            )
            if (!available) {
                Text(
                    "Linking needs Relay's own delivery server. This build connects to relays directly, so " +
                        "there is nowhere to hand a computer its setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
                )
            }

            linkedDevice?.let {
                Text(
                    "Computer linked. Leave Relay open on it for a moment while it finishes setting up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
                )
            }

            SectionHeader("This account's computers")
            when {
                loadFailed -> Text(
                    "Couldn't load the list. Pull the connection back and open this page again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
                )
                devices == null -> Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
                )
                devices!!.isEmpty() -> Text(
                    "No computers are linked yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
                )
                else -> devices!!.forEach { device ->
                    RowDivider()
                    DeviceRow(device, removing = busy && removeTarget?.id == device.id, onRemove = { removeTarget = device })
                }
            }
            RowDivider()
        }
    }

    removeTarget?.let { device ->
        ConfirmDialog(
            title = "Remove ${device.displayName}?",
            message = "Relay's server will stop answering this computer: it can't fetch new messages or " +
                "send any. It keeps the keys it already has, so it can still read what it already downloaded. " +
                "To cut it off completely you would need a new identity and to re-pair everyone.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                val target = device
                removeTarget = null
                busy = true
                scope.launch {
                    val result = PhoneLinkCoordinator.remove(context, target.id)
                    if (result.ok) {
                        refresh()
                        message = null
                    } else {
                        message = "Couldn't remove that computer (HTTP ${result.status}). Try again."
                    }
                    busy = false
                }
            },
            onDismiss = { removeTarget = null },
        )
    }
}

/** The camera fills the screen while the computer's code is being read; nothing is uploaded yet. */
@Composable
private fun ScanPanel(busy: Boolean, onScanned: (String) -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            if (busy) "Linking… keep this screen open." else "Point the camera at the code on the computer.",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
        )
        Box(modifier = Modifier.weight(1f)) {
            RawQrScanView(onText = onScanned, modifier = Modifier.fillMaxSize())
        }
        if (!busy) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(horizontal = RelaySpacing.gutter)) {
                Text("Cancel", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun DeviceRow(device: LinkedDevice, removing: Boolean, onRemove: () -> Unit) {
    ListRow(
        headline = device.displayName,
        supporting = buildString {
            append("Added ").append(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(device.addedAt)))
            if (!device.active) append(" · not connected recently")
        },
        trailing = {
            TextButton(onClick = onRemove, enabled = !removing) {
                Text("Remove", style = MaterialTheme.typography.labelLarge, color = Danger)
            }
        },
    )
}
