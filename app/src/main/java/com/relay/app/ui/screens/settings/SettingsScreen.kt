package com.relay.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.model.PinExpiry
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.util.RelayPreferences
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(navController: NavController) {
    val vm: SettingsViewModel = viewModel()

    Scaffold(
        topBar = {
            RelayTopBar(
                title = "Settings",
                onBack = { navController.popBackStack() },
            )
        },
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
            SectionHeader("Location Requests")

            ToggleRow(
                label = "Auto-approve location requests",
                checked = vm.autoApproveLocationRequests,
                onCheckedChange = vm::updateAutoApproveLocationRequests,
            )
            ToggleRow(
                label = "Notify me when I auto-share location",
                checked = vm.notifyOnAutoShare,
                onCheckedChange = vm::updateNotifyOnAutoShare,
                enabled = vm.autoApproveLocationRequests,
            )
            DropdownRow(
                label = "Who can request my location",
                selected = vm.locationRequestFrom,
                options = listOf(RelayPreferences.FROM_ALL to "All contacts", RelayPreferences.FROM_NOBODY to "Nobody"),
                onSelect = vm::updateLocationRequestFrom,
            )

            SectionHeader("General")

            DropdownRow(
                label = "Theme",
                selected = vm.theme,
                options = listOf(
                    RelayPreferences.THEME_DARK to "Dark",
                    RelayPreferences.THEME_LIGHT to "Light",
                    RelayPreferences.THEME_SYSTEM to "System default",
                ),
                onSelect = vm::updateTheme,
            )
            ToggleRow(
                label = "App lock",
                checked = vm.appLock,
                onCheckedChange = vm::updateAppLock,
            )

            SectionHeader("Notifications")

            ToggleRow(
                label = "Incoming pin notification",
                checked = vm.incomingPinNotification,
                onCheckedChange = vm::updateIncomingPinNotification,
            )
            ToggleRow(
                label = "Incoming message notification",
                checked = vm.incomingMessageNotification,
                onCheckedChange = vm::updateIncomingMessageNotification,
            )
            ToggleRow(
                label = "Do Not Disturb for location requests",
                checked = vm.dndEnabled,
                onCheckedChange = vm::updateDndEnabled,
            )
            DropdownRow(
                label = "DND starts at",
                selected = vm.dndStartHour.toString(),
                options = hourOptions(),
                onSelect = { vm.updateDndStartHour(it.toInt()) },
            )
            DropdownRow(
                label = "DND ends at",
                selected = vm.dndEndHour.toString(),
                options = hourOptions(),
                onSelect = { vm.updateDndEndHour(it.toInt()) },
            )

            SectionHeader("Security")

            ActionRow(
                label = "Rotate encryption key now",
                subtitle = if (vm.lastKeyRotationAt == 0L) {
                    "Never rotated — happens automatically every 30 days"
                } else {
                    "Last rotated ${formatRotationDate(vm.lastKeyRotationAt)} — pushes a fresh key to every encrypted contact"
                },
                actionLabel = if (vm.rotatingKey) "Rotating…" else "Rotate",
                enabled = !vm.rotatingKey,
                onClick = vm::rotateEncryptionKeyNow,
            )

            SectionHeader("Map")

            SliderRow(
                label = "Default map zoom level",
                value = vm.defaultMapZoom,
                onValueChange = vm::updateDefaultMapZoom,
                valueRange = 5f..20f,
            )
            ToggleRow(
                label = "Keep screen on while map is open",
                checked = vm.keepScreenOnMap,
                onCheckedChange = vm::updateKeepScreenOnMap,
            )
            DropdownRow(
                label = "Default pin expiry",
                selected = vm.defaultPinExpiry,
                options = PinExpiry.entries.map { it.smsCode to it.label },
                onSelect = vm::updateDefaultPinExpiry,
            )
        }
    }
}

private fun hourOptions(): List<Pair<String, String>> = (0..23).map { hour ->
    hour.toString() to String.format("%02d:00", hour)
}

private fun formatRotationDate(epochMillis: Long): String {
    val formatter = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(epochMillis))
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = TextSecondary,
        fontFamily = IbmPlexMono,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (enabled) TextPrimary else TextSecondary,
            fontFamily = IbmPlexSans,
            fontSize = 14.sp,
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OnAccent,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Surface2,
                uncheckedBorderColor = Border,
            ),
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    subtitle: String,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = label,
                color = TextPrimary,
                fontFamily = IbmPlexSans,
                fontSize = 14.sp,
            )
            Text(
                text = subtitle,
                color = TextSecondary,
                fontFamily = IbmPlexSans,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(onClick = onClick, enabled = enabled) {
            Text(actionLabel, color = if (enabled) Accent else TextSecondary, fontFamily = IbmPlexSans)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownRow(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontFamily = IbmPlexSans,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = displayLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = Surface2,
                    unfocusedContainerColor = Surface2,
                    focusedTrailingIconColor = TextSecondary,
                    unfocusedTrailingIconColor = TextSecondary,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = IbmPlexSans,
                    fontSize = 13.sp,
                ),
                singleLine = true,
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (key, display) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                display,
                                fontFamily = IbmPlexSans,
                                fontSize = 14.sp,
                                color = if (key == selected) Accent else TextPrimary,
                            )
                        },
                        onClick = {
                            onSelect(key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = TextPrimary,
                fontFamily = IbmPlexSans,
                fontSize = 14.sp,
            )
            Text(
                text = value.roundToInt().toString(),
                color = Accent,
                fontFamily = IbmPlexMono,
                fontSize = 13.sp,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Surface2,
            ),
        )
    }
}
