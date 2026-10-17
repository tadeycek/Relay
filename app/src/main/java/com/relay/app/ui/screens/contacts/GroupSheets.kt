package com.relay.app.ui.screens.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relay.app.data.model.Contact
import com.relay.app.data.model.Group
import com.relay.app.ui.components.ConfirmDialog
import com.relay.app.ui.components.ContactGlyph
import com.relay.app.ui.components.DangerButton
import com.relay.app.ui.components.ListRow
import com.relay.app.ui.components.PrimaryButton
import com.relay.app.ui.components.RelayBottomSheet
import com.relay.app.ui.components.RelayTextField
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Danger
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary

@Composable
fun NewGroupSheet(contacts: List<Contact>, onCreate: (String, List<Long>) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Long>() }
    val canCreate = name.isNotBlank() && selected.isNotEmpty()

    RelayBottomSheet(onDismiss) {
        Text("New group", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        RelayTextField(
            value = name,
            onValueChange = { name = it },
            label = "Group name",
            modifier = Modifier.fillMaxWidth().padding(top = RelaySpacing.lg),
        )
        if (contacts.isEmpty()) {
            Text(
                "Add a person first, then you can put them in a group.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(top = RelaySpacing.lg),
            )
        } else {
            Text("Who is in it", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.padding(top = RelaySpacing.lg))
            Column(modifier = Modifier.padding(top = RelaySpacing.xs)) {
                contacts.forEach { contact ->
                    val checked = contact.id in selected
                    ListRow(
                        headline = contact.name,
                        onClick = { if (checked) selected.remove(contact.id) else selected.add(contact.id) },
                        leading = { ContactGlyph(contact, size = 40.dp) },
                        trailing = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = TextSecondary),
                            )
                        },
                    )
                }
            }
        }
        PrimaryButton(
            "Create group",
            { onCreate(name.trim(), selected.toList()) },
            Modifier.fillMaxWidth().padding(top = RelaySpacing.lg),
            enabled = canCreate,
        )
    }
}

@Composable
fun ManageGroupSheet(
    group: Group,
    allContacts: List<Contact>,
    onRemove: (Long) -> Unit,
    onAdd: (Long) -> Unit,
    onDeleteGroup: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val memberIds = group.members.map { it.id }.toSet()
    val others = allContacts.filter { it.id !in memberIds }

    RelayBottomSheet(onDismiss) {
        Text(group.name, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Text("In this group", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.padding(top = RelaySpacing.lg))
        if (group.members.isEmpty()) {
            Text("No one yet.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        group.members.forEach { member ->
            ListRow(
                headline = member.name,
                leading = { ContactGlyph(member, size = 40.dp) },
                trailing = {
                    TextButton(onClick = { onRemove(member.id) }) {
                        Text("Remove", color = Danger, style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        }
        if (others.isNotEmpty()) {
            Text("Add someone", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.padding(top = RelaySpacing.lg))
            others.forEach { contact ->
                ListRow(
                    headline = contact.name,
                    onClick = { onAdd(contact.id) },
                    leading = { ContactGlyph(contact, size = 40.dp) },
                    trailing = { Text("Add", color = Accent, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
        DangerButton("Delete group", { confirmDelete = true }, Modifier.fillMaxWidth().padding(top = RelaySpacing.xl))
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${group.name}?",
            message = "The group disappears from this phone. The people in it are not affected.",
            confirmLabel = "Delete group",
            destructive = true,
            onConfirm = { confirmDelete = false; onDeleteGroup() },
            onDismiss = { confirmDelete = false },
        )
    }
}
