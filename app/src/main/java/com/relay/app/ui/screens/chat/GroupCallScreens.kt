package com.relay.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.app.calls.group.GroupCallBanner
import com.relay.app.calls.group.GroupCallUiState
import com.relay.app.calls.group.PersonRow
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Danger
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.theme.Verified

/**
 * The group-chat banner for a live call. "Join" is the only action: a call announcement never opens a
 * microphone or a room socket by itself. The encryption label is part of the banner and the in-call screen
 * and has no dismissal control.
 */
@Composable
fun GroupCallBannerBar(banner: GroupCallBanner, onJoin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RelaySpacing.lg, vertical = RelaySpacing.xs)
            .background(Surface2, RoundedCornerShape(12.dp))
            .padding(RelaySpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Call, contentDescription = null, tint = Verified, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(RelaySpacing.sm))
            Column(Modifier.weight(1f)) {
                Text("Group call in progress", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                Text("Started by ${banner.caller.take(8)}…", color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onJoin, colors = ButtonDefaults.buttonColors(containerColor = Verified, contentColor = Color.Black)) {
                Text("Join")
            }
        }
        Spacer(Modifier.height(RelaySpacing.xs))
        Text("Group calls are encrypted in transit, not end to end.", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The in-call screen. Participant rows are per person, with their devices nested underneath (a person can
 * be on this phone and a linked computer). A person the local roster does not know is shown with the same
 * "Not in this group" warning the model computes; they are never subscribed to.
 */
@Composable
fun GroupCallScreen(
    state: GroupCallUiState,
    onMute: (Boolean) -> Unit,
    onDeafen: (Boolean) -> Unit,
    onVolume: (String, Float) -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val model = state.model
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = RelaySpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(RelaySpacing.md))
        Text(state.groupName, color = TextPrimary, style = MaterialTheme.typography.headlineSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                state.finished != null -> state.message.ifBlank { "The call ended." }
                state.phase == com.relay.app.calls.group.SessionPhase.JOINING -> "Joining…"
                state.phase == com.relay.app.calls.group.SessionPhase.RECONNECTING -> "Reconnecting…"
                else -> "${model.people.size} ${if (model.people.size == 1) "person" else "people"} in this call"
            },
            color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
        )
        // The label is computed by GroupCallSecurityLabel: it only says end to end once every participant
        // device has proven the current epoch key.
        Text(model.securityLabel, color = TextSecondary,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = RelaySpacing.xs, bottom = RelaySpacing.sm))
        state.error?.let {
            Text(it, color = Danger, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = RelaySpacing.sm))
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(model.people, key = { it.pubkey.ifEmpty { "self" } }) { person ->
                PersonCard(person, onVolume)
            }
        }

        if (state.finished != null) {
            TextButton(onClick = onDismiss) { Text("Close", color = TextPrimary) }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                CallControl(
                    label = if (model.muted) "Unmute" else "Mute",
                    active = model.muted,
                    onClick = { onMute(!model.muted) },
                    icon = if (model.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                )
                CallControl(
                    label = if (model.deafened) "Undeafen" else "Deafen",
                    active = model.deafened,
                    onClick = { onDeafen(!model.deafened) },
                    icon = if (model.deafened) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                )
                if (model.canEnd) {
                    CallControl("End", true, onEnd, Icons.Filled.CallEnd)
                } else {
                    CallControl("Leave", true, onLeave, Icons.Filled.CallEnd)
                }
            }
        }
        Spacer(Modifier.height(RelaySpacing.lg))
    }
}

@Composable
private fun PersonCard(person: PersonRow, onVolume: (String, Float) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = RelaySpacing.xs)
            .background(Surface2, RoundedCornerShape(12.dp)).padding(RelaySpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                person.name + if (person.isSelf) " (you)" else "",
                color = if (person.inGroup) TextPrimary else Danger,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (person.speaking) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (person.speaking) Text("speaking", color = Verified, style = MaterialTheme.typography.labelSmall)
        }
        person.warning?.let {
            Text(it, color = Danger, style = MaterialTheme.typography.labelSmall)
        }
        person.devices.forEach { device ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    buildString {
                        append(device.device.ifBlank { "device" })
                        if (device.pid.isNotEmpty()) append(" · ").append(device.pid.take(6))
                        if (device.muted) append(" · muted")
                        if (device.deafened) append(" · deafened")
                        if (!device.hasAudio) append(" · connecting")
                    },
                    color = TextSecondary, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!person.isSelf) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volume", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = person.volume,
                    onValueChange = { onVolume(person.pubkey, it) },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f).padding(start = RelaySpacing.sm),
                )
            }
        }
    }
}

@Composable
private fun CallControl(label: String, active: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) Danger else Surface2,
                contentColor = if (active) Color.White else TextPrimary,
            ),
            shape = RoundedCornerShape(24.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
        Text(label, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

/** Used by the chat screen's start action so the label/strings stay in one place. */
@Composable
fun GroupCallStartButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.Call, contentDescription = "Start group call", tint = if (enabled) Accent else TextSecondary)
    }
}
