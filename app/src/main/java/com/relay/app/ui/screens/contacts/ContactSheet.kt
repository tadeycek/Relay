package com.relay.app.ui.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.ui.components.ChipKind
import com.relay.app.ui.components.ConfirmDialog
import com.relay.app.ui.components.ContactGlyph
import com.relay.app.ui.components.DangerButton
import com.relay.app.ui.components.PrimaryButton
import com.relay.app.ui.components.RelayBottomSheet
import com.relay.app.ui.components.SecondaryButton
import com.relay.app.ui.components.SegmentedControl
import com.relay.app.ui.components.StatusChip
import com.relay.app.ui.screens.chat.trustLineFor
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary

/** Short, eyeballable digest of a key, so a changed key is easy to compare in person. */
internal fun keyFingerprint(base64Key: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(base64Key.toByteArray())
    return digest.take(8).joinToString(" ") { "%02X".format(it) }
}

internal fun trustExplanation(level: ContactTrustLevel): String = when (level) {
    ContactTrustLevel.TRUSTED -> "Their messages arrive and their photos download by themselves."
    ContactTrustLevel.ASK -> "You decide about each photo before it downloads."
    ContactTrustLevel.BLOCKED -> "Their messages are dropped and never reach you."
}

/** Everything you can do with one contact, in one place, instead of cycling a hidden badge. */
@Composable
fun ContactSheet(
    contact: Contact,
    onMessage: () -> Unit,
    onRename: (String) -> Unit,
    onTrustLevel: (ContactTrustLevel) -> Unit,
    onAcceptKey: () -> Unit,
    onRejectKey: () -> Unit,
    onVerify: () -> Unit,
    onDeleteKeepChat: () -> Unit,
    onDeleteWithChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteChatToo by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val trust = trustLineFor(hasKey = contact.nostrPubkey != null, verifiedInPerson = contact.qrVerified)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = IbmPlexMono)

    RelayBottomSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RelaySpacing.md)) {
            ContactGlyph(contact, size = 56.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RelaySpacing.sm)) {
                    Text(contact.name, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    IconButton(onClick = { renaming = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "Change their name",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                StatusChip(
                    text = if (trust.verified) "Verified in person" else "Not verified",
                    kind = if (trust.verified) ChipKind.VERIFIED else ChipKind.NEUTRAL,
                    modifier = Modifier.padding(top = RelaySpacing.xs),
                )
            }
        }
        Text(trust.text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(top = RelaySpacing.md))

        val pending = contact.pendingPublicKey
        if (pending != null) {
            Column(modifier = Modifier.padding(top = RelaySpacing.lg), verticalArrangement = Arrangement.spacedBy(RelaySpacing.sm)) {
                Text("Their key changed", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(
                    "This happens when they reinstall Relay. It could also mean someone is pretending to be them. Compare these with them in person before you accept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                contact.publicKey?.let { Text("Current  ${keyFingerprint(it)}", style = mono, color = TextSecondary) }
                Text("New       ${keyFingerprint(pending)}", style = mono, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(RelaySpacing.sm)) {
                    PrimaryButton("Accept new key", onAcceptKey, Modifier.weight(1f))
                    SecondaryButton("Reject", onRejectKey, Modifier.weight(1f))
                }
            }
        }

        Text("How much you trust them", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.padding(top = RelaySpacing.xl))
        SegmentedControl(
            options = ContactTrustLevel.entries.map { it to it.label },
            selected = contact.trustLevel,
            onSelect = onTrustLevel,
            modifier = Modifier.fillMaxWidth().padding(top = RelaySpacing.sm),
        )
        Text(trustExplanation(contact.trustLevel), style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(top = RelaySpacing.sm))

        Column(modifier = Modifier.padding(top = RelaySpacing.xl), verticalArrangement = Arrangement.spacedBy(RelaySpacing.sm)) {
            if (contact.nostrPubkey != null) PrimaryButton("Message", onMessage, Modifier.fillMaxWidth())
            if (!contact.qrVerified) {
                SecondaryButton(if (contact.nostrPubkey == null) "Scan their code" else "Verify in person", onVerify, Modifier.fillMaxWidth())
            }
            DangerButton("Delete contact", { confirmDelete = true }, Modifier.fillMaxWidth())
        }
    }

    if (renaming) {
        var draft by remember(contact.id) { mutableStateOf(contact.name) }
        RelayBottomSheet(onDismiss = { renaming = false }) {
            Text("Change their name", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text(
                "This only changes what you see; it does not tell them or rename them anywhere else.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = RelaySpacing.xs),
            )
            com.relay.app.ui.components.RelayTextField(
                value = draft,
                onValueChange = { draft = it },
                label = "Name",
                modifier = Modifier.fillMaxWidth().padding(top = RelaySpacing.lg),
            )
            PrimaryButton(
                "Save name",
                { onRename(draft); renaming = false },
                Modifier.fillMaxWidth().padding(top = RelaySpacing.lg),
                enabled = draft.isNotBlank(),
            )
        }
    }

    if (confirmDelete) {
        RelayBottomSheet(onDismiss = { confirmDelete = false }) {
            Text("Delete ${contact.name}?", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text(
                "You will no longer be able to message them. What should happen to your chat with them?",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = RelaySpacing.xs),
            )
            Column(modifier = Modifier.padding(top = RelaySpacing.lg), verticalArrangement = Arrangement.spacedBy(RelaySpacing.sm)) {
                SecondaryButton(
                    "Keep the chat",
                    { confirmDelete = false; onDeleteKeepChat() },
                    Modifier.fillMaxWidth(),
                )
                Text(
                    "The conversation stays in Messages, marked as a deleted contact.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                DangerButton(
                    "Delete the chat too",
                    { confirmDelete = false; confirmDeleteChatToo = true },
                    Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (confirmDeleteChatToo) {
        ConfirmDialog(
            title = "Delete the chat too?",
            message = "Every message in this conversation is removed from this phone. This cannot be undone.",
            confirmLabel = "Delete everything",
            destructive = true,
            onConfirm = { confirmDeleteChatToo = false; onDeleteWithChat() },
            onDismiss = { confirmDeleteChatToo = false },
        )
    }
}
