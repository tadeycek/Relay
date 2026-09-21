package com.relay.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.relay.app.ui.components.ChipKind
import com.relay.app.ui.components.SecondaryButton
import com.relay.app.ui.components.StatusChip
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.RelayShapeTokens
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface3
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary

/** The setup really is a sequence, so it is a numbered list: Relay does not include Tor, it uses Orbot. */
@Composable
fun TorSetupGuide(orbotInstalled: Boolean, onOpenOrbot: () -> Unit) {
    val steps = listOf(
        "Install Orbot, the free Tor app from the Guardian Project (Play Store or F-Droid).",
        "Open Orbot and tap Start. Wait until it says it is connected to Tor.",
        "Come back here and switch on \"Hide my IP from relays\". The status should change to \"Connected through Tor\".",
        "Keep Orbot running. If it stops, Relay pauses sending and shows \"Waiting for Tor\" until it is back.",
    )
    Column(
        modifier = Modifier
            .padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.md)
            .fillMaxWidth()
            .clip(RelayShapeTokens.bubble)
            .background(Surface1)
            .padding(RelaySpacing.lg),
        verticalArrangement = Arrangement.spacedBy(RelaySpacing.md),
    ) {
        Text("Needs the Orbot app", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        Text(
            "Relay does not include Tor. With Orbot missing or stopped, the switch only pauses your messages.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(RelaySpacing.md)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(Surface3),
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                }
                Text(step, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RelaySpacing.md)) {
            StatusChip(if (orbotInstalled) "Orbot installed" else "Orbot not installed", if (orbotInstalled) ChipKind.VERIFIED else ChipKind.WARNING)
            Text(
                "127.0.0.1:9050",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = IbmPlexMono),
                color = TextSecondary,
            )
        }
        SecondaryButton(if (orbotInstalled) "Open Orbot" else "Get Orbot", onOpenOrbot, Modifier.fillMaxWidth())
    }
}

/**
 * Plain-language pros and cons of the Tor option. Kept honest about what it does not cover (a maps app
 * opened from a shared location, the friend's side, timing analysis) so nobody over-trusts it.
 */
@Composable
fun TorExplanation() {
    val blocks = listOf(
        "What you gain" to listOf(
            "Relays can't see your IP address or approximate location.",
            "No single party sees both who you are and what you connect to.",
            "Someone watching your network can't easily tell which relays you use.",
        ),
        "What you give up" to listOf(
            "Messages are slower, and the first connection takes several seconds.",
            "More battery and data use.",
            "Some relays block Tor, so fewer relays may work.",
            "It needs the Orbot app to be installed and running.",
        ),
        "What it does not cover" to listOf(
            "Anything you open in another app (a link, a shared location) connects on its own, outside Tor.",
            "Your contact's IP is still visible to relays unless they use Tor too.",
            "Your mobile carrier can see that you're using Tor.",
            "It doesn't protect you if your phone is compromised, and it can't hide that a message for your key exists.",
        ),
    )
    Column(modifier = Modifier.padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.md), verticalArrangement = Arrangement.spacedBy(RelaySpacing.lg)) {
        blocks.forEach { (title, items) ->
            Column(verticalArrangement = Arrangement.spacedBy(RelaySpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
            }
        }
    }
}
