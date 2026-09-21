package com.relay.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.relay.app.data.model.PinExpiry
import com.relay.app.transport.TransportStatus
import com.relay.app.ui.components.ChoiceRow
import com.relay.app.ui.components.ListRow
import com.relay.app.ui.components.SectionHeader
import com.relay.app.ui.components.ToggleRow
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.util.RelayPreferences

/** The Account sub-pages. Each is a short list of related settings; the Account tab links to them. */
enum class AccountPage(val route: String, val title: String) {
    NOTIFICATIONS("notifications", "Notifications"),
    LOCATION("location", "Location sharing"),
    PRIVACY("privacy", "Privacy and Tor"),
    SECURITY("security", "Security"),
    CONNECTION("connection", "Connection"),
    APPEARANCE("appearance", "Appearance");

    companion object {
        fun fromRoute(route: String?): AccountPage = entries.firstOrNull { it.route == route } ?: NOTIFICATIONS
    }
}

@Composable
fun AccountPageContent(page: AccountPage, vm: SettingsViewModel) {
    when (page) {
        AccountPage.NOTIFICATIONS -> NotificationsPage(vm)
        AccountPage.LOCATION -> LocationPage(vm)
        AccountPage.PRIVACY -> PrivacyPage(vm)
        AccountPage.SECURITY -> SecurityPage(vm)
        AccountPage.CONNECTION -> ConnectionPage(vm)
        AccountPage.APPEARANCE -> AppearancePage(vm)
    }
}

/** One-line summary shown under each link on the Account tab. */
fun accountPageSummary(page: AccountPage, vm: SettingsViewModel): String = when (page) {
    AccountPage.NOTIFICATIONS -> if (vm.incomingMessageNotification) "Messages on" else "Messages off"
    AccountPage.LOCATION -> if (vm.locationRequestFrom == RelayPreferences.FROM_NOBODY) "No one can ask" else "Contacts can ask"
    AccountPage.PRIVACY -> if (vm.torEnabled) "Tor on" else "Tor off"
    AccountPage.SECURITY -> if (vm.appLock) "App lock on" else "App lock off"
    AccountPage.CONNECTION -> connectionText(vm.connectionStatus)
    AccountPage.APPEARANCE -> when (vm.theme) {
        RelayPreferences.THEME_LIGHT -> "Light"
        RelayPreferences.THEME_SYSTEM -> "Follows your phone"
        else -> "Dark"
    }
}

fun connectionText(status: TransportStatus): String = when (status) {
    TransportStatus.ONLINE -> "Connected"
    TransportStatus.CONNECTING -> "Connecting"
    TransportStatus.OFFLINE -> "Offline. Messages wait and send when it reconnects"
    TransportStatus.STOPPED -> "Stopped"
    TransportStatus.WAITING_FOR_TOR -> "Waiting for Tor. Start Orbot, or turn Tor off"
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.sm),
    )
}

@Composable
private fun NotificationsPage(vm: SettingsViewModel) {
    ToggleRow("New messages", vm.incomingMessageNotification, vm::updateIncomingMessageNotification)
    ToggleRow("Shared locations", vm.incomingPinNotification, vm::updateIncomingPinNotification)
    SectionHeader("Quiet hours")
    ToggleRow(
        "Silence location requests at night",
        vm.dndEnabled,
        vm::updateDndEnabled,
        supporting = "Requests wait for you instead of buzzing your phone.",
    )
    HourRow("From", vm.dndStartHour, vm::updateDndStartHour, enabled = vm.dndEnabled)
    HourRow("Until", vm.dndEndHour, vm::updateDndEndHour, enabled = vm.dndEnabled)
}

/** A time of day you step by the hour. 24 choices do not fit a segmented control or a readable list. */
@Composable
private fun HourRow(label: String, hour: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    ListRow(
        headline = label,
        enabled = enabled,
        trailing = {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { onChange((hour + 23) % 24) }, enabled = enabled) { Text("Earlier", style = MaterialTheme.typography.labelLarge) }
                Text(String.format("%02d:00", hour), style = MaterialTheme.typography.titleSmall, color = if (enabled) com.relay.app.ui.theme.TextPrimary else TextSecondary)
                TextButton(onClick = { onChange((hour + 1) % 24) }, enabled = enabled) { Text("Later", style = MaterialTheme.typography.labelLarge) }
            }
        },
    )
}

@Composable
private fun LocationPage(vm: SettingsViewModel) {
    ChoiceRow(
        headline = "Who can ask for your location",
        options = listOf(RelayPreferences.FROM_ALL to "All contacts", RelayPreferences.FROM_NOBODY to "No one"),
        selected = vm.locationRequestFrom,
        onSelect = vm::updateLocationRequestFrom,
    )
    ToggleRow(
        "Share automatically",
        vm.autoApproveLocationRequests,
        vm::updateAutoApproveLocationRequests,
        supporting = "Answer requests without asking you first.",
    )
    ToggleRow(
        "Tell me when it shares",
        vm.notifyOnAutoShare,
        vm::updateNotifyOnAutoShare,
        enabled = vm.autoApproveLocationRequests,
    )
    SectionHeader("Shared locations")
    Note("A location you share disappears from the chat after:")
    PinExpiry.entries.forEach { option ->
        ListRow(
            headline = option.label,
            onClick = { vm.updateDefaultPinExpiry(option.smsCode) },
            trailing = {
                RadioButton(
                    selected = vm.defaultPinExpiry == option.smsCode,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextSecondary),
                )
            },
        )
    }
}

@Composable
private fun PrivacyPage(vm: SettingsViewModel) {
    ToggleRow(
        "Hide my IP address from relays",
        vm.torEnabled,
        vm::updateTorEnabled,
        supporting = "Sends everything through Tor. Needs the Orbot app.",
    )
    if (vm.torEnabled) {
        ListRow(
            headline = "Tor status",
            supporting = when (vm.connectionStatus) {
                TransportStatus.ONLINE -> "Connected through Tor"
                TransportStatus.CONNECTING -> "Connecting through Tor"
                TransportStatus.WAITING_FOR_TOR -> "Tor isn't running. Messages are waiting. Start Orbot to continue."
                else -> "Not connected"
            },
        )
        ToggleRow(
            "If Tor is unavailable, connect directly",
            vm.torFallbackToDirect,
            vm::updateTorFallbackToDirect,
            supporting = "Off keeps you private: nothing is sent until Tor is running. On sends directly, which reveals your IP address.",
        )
    }
    SectionHeader("Set up Tor")
    TorSetupGuide(orbotInstalled = vm.orbotInstalled, onOpenOrbot = vm::openOrbot)
    SectionHeader("What Tor does and doesn't do")
    TorExplanation()
}

@Composable
private fun SecurityPage(vm: SettingsViewModel) {
    ToggleRow(
        "App lock",
        vm.appLock,
        vm::updateAppLock,
        supporting = "Ask for your fingerprint, face or screen lock when you open Relay.",
    )
    SectionHeader("Encryption key")
    ListRow(
        headline = "Rotate encryption key",
        supporting = if (vm.lastKeyRotationAt == 0L) {
            "Never rotated. It happens by itself every 30 days."
        } else {
            "Last rotated ${formatRotationDate(vm.lastKeyRotationAt)}. Sends a fresh key to every encrypted contact."
        },
        enabled = !vm.rotatingKey,
        onClick = vm::rotateEncryptionKeyNow,
        trailing = { Text(if (vm.rotatingKey) "Rotating" else "Rotate", style = MaterialTheme.typography.labelLarge, color = Accent) },
    )
}

@Composable
private fun ConnectionPage(vm: SettingsViewModel) {
    ListRow(
        headline = "Relay connection",
        supporting = connectionText(vm.connectionStatus),
        onClick = vm::reconnectNow,
        trailing = { Text("Reconnect", style = MaterialTheme.typography.labelLarge, color = Accent) },
    )
    ToggleRow(
        "Stay connected in the background",
        vm.backgroundConnection,
        vm::updateBackgroundConnection,
        supporting = "Needed to receive messages while the app is closed. Uses some battery and shows a small notification. " +
            "When off, messages arrive when you open the app or within about 15 minutes.",
    )
}

@Composable
private fun AppearancePage(vm: SettingsViewModel) {
    ChoiceRow(
        headline = "Theme",
        options = listOf(
            RelayPreferences.THEME_DARK to "Dark",
            RelayPreferences.THEME_LIGHT to "Light",
            RelayPreferences.THEME_SYSTEM to "System",
        ),
        selected = vm.theme,
        onSelect = vm::updateTheme,
    )
}

private fun formatRotationDate(epochMillis: Long): String {
    val formatter = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(epochMillis))
}
