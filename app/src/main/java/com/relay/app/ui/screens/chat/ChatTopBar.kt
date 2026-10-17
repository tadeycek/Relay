package com.relay.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.app.data.model.Contact
import com.relay.app.ui.components.ContactGlyph
import com.relay.app.ui.components.GroupGlyph
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.theme.Verified

/** What the second line of the chat header says about who you are talking to. */
internal data class TrustLine(val text: String, val verified: Boolean)

internal fun trustLineFor(hasKey: Boolean, verifiedInPerson: Boolean): TrustLine = when {
    !hasKey -> TrustLine("Old contact: scan their code to message them", verified = false)
    verifiedInPerson -> TrustLine("Verified in person", verified = true)
    else -> TrustLine("Not verified: you haven't scanned their code", verified = false)
}

/** Chat header: back, the contact's glyph, their name, and whether you have verified them in person. */
@Composable
fun ChatTopBar(
    contact: Contact?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .statusBarsPadding()
            .padding(start = RelaySpacing.xs, end = RelaySpacing.xs, top = RelaySpacing.xs, bottom = RelaySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = TextSecondary)
        }
        if (contact != null) {
            ContactGlyph(contact, size = 40.dp)
            Spacer(Modifier.width(RelaySpacing.md))
            val trust = trustLineFor(hasKey = contact.nostrPubkey != null, verifiedInPerson = contact.qrVerified)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = trust.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (trust.verified) Verified else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** Group chat header: back, the group glyph, its name and how many people are in it. */
@Composable
fun GroupChatTopBar(
    name: String,
    memberSeeds: List<String>,
    memberCount: Int,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .statusBarsPadding()
            .padding(start = RelaySpacing.xs, end = RelaySpacing.xs, top = RelaySpacing.xs, bottom = RelaySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = TextSecondary)
        }
        GroupGlyph(memberSeeds = memberSeeds, size = 40.dp)
        Spacer(Modifier.width(RelaySpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (memberCount == 1) "1 member" else "$memberCount members",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
        actions()
    }
}
