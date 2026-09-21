package com.relay.app.ui.screens.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.Group
import com.relay.app.ui.components.ChipKind
import com.relay.app.ui.components.ContactGlyph
import com.relay.app.ui.components.EmptyState
import com.relay.app.ui.components.GroupGlyph
import com.relay.app.ui.components.ListRow
import com.relay.app.ui.components.RowDivider
import com.relay.app.ui.components.ScreenHeader
import com.relay.app.ui.components.SectionHeader
import com.relay.app.ui.components.StatusChip
import com.relay.app.ui.glyph.GlyphGenerator
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextSecondary

private val AvatarSize = 48.dp
private val TextInset = RelaySpacing.gutter + AvatarSize + RelaySpacing.md

/** The first tab: everyone you can message, and your groups. Tap a row for what you can do with it. */
@Composable
fun ContactsScreen(navController: NavController) {
    val vm: ContactsViewModel = viewModel()
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val groups by vm.groups.collectAsState()
    var showNewGroup by remember { mutableStateOf(false) }
    var contactSheetId by remember { mutableStateOf<Long?>(null) }
    var manageGroupId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        vm.registerUpdates(context)
        onDispose { vm.unregisterUpdates(context) }
    }

    fun pair() = navController.navigate(Screen.QrExchange.route)

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        ScreenHeader(title = "People") {
            IconButton(onClick = { showNewGroup = true }) {
                Icon(Icons.Outlined.Group, contentDescription = "New group", tint = TextSecondary)
            }
            IconButton(onClick = { pair() }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add a person by scanning their code", tint = TextSecondary)
            }
        }

        if (contacts.isEmpty() && groups.isEmpty()) {
            EmptyState(
                title = "No one here yet",
                message = "Meet a friend and scan each other's codes. That is how Relay makes sure you are talking to the right person.",
                actionLabel = "Scan a friend's code",
                onAction = { pair() },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (groups.isNotEmpty()) {
                    item { SectionHeader("Groups") }
                    items(groups, key = { "group_${it.id}" }) { group ->
                        GroupRow(
                            group,
                            onOpen = { navController.navigate(Screen.GroupChat.routeFor(group.id)) },
                            onManage = { manageGroupId = group.id },
                        )
                        RowDivider(inset = TextInset)
                    }
                }
                if (contacts.isNotEmpty()) {
                    item { SectionHeader("Contacts") }
                    items(contacts, key = { it.id }) { contact ->
                        ContactRow(contact, onClick = { contactSheetId = contact.id })
                        RowDivider(inset = TextInset)
                    }
                }
            }
        }
    }

    if (showNewGroup) {
        NewGroupSheet(
            contacts = contacts,
            onCreate = { name, ids -> vm.createGroup(name, ids); showNewGroup = false },
            onDismiss = { showNewGroup = false },
        )
    }

    groups.find { it.id == manageGroupId }?.let { group ->
        ManageGroupSheet(
            group = group,
            allContacts = contacts,
            onRemove = { vm.removeMemberFromGroup(group.id, it) },
            onAdd = { vm.addMemberToGroup(group.id, it) },
            onDeleteGroup = { vm.deleteGroup(group.id); manageGroupId = null },
            onDismiss = { manageGroupId = null },
        )
    }

    contacts.find { it.id == contactSheetId }?.let { contact ->
        ContactSheet(
            contact = contact,
            onMessage = { contactSheetId = null; navController.navigate(Screen.Chat.routeFor(contact.id)) },
            onTrustLevel = { vm.updateTrustLevel(contact.id, it) },
            onAcceptKey = { vm.acceptKeyChange(contact.id) },
            onRejectKey = { vm.rejectKeyChange(contact.id) },
            onVerify = { contactSheetId = null; pair() },
            onDelete = { vm.deleteContact(contact.id); contactSheetId = null },
            onDismiss = { contactSheetId = null },
        )
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    ListRow(
        headline = contact.name,
        supporting = contact.subtitle,
        onClick = onClick,
        maxSupportingLines = 1,
        leading = { ContactGlyph(contact, size = AvatarSize) },
        trailing = {
            when {
                contact.pendingPublicKey != null -> StatusChip("Key changed", ChipKind.DANGER)
                contact.trustLevel == ContactTrustLevel.BLOCKED -> StatusChip("Blocked", ChipKind.DANGER)
                contact.nostrPubkey == null -> StatusChip("Old contact", ChipKind.NEUTRAL)
                contact.qrVerified -> StatusChip("Verified", ChipKind.VERIFIED)
                else -> StatusChip("Not verified", ChipKind.NEUTRAL)
            }
        },
    )
}

@Composable
private fun GroupRow(group: Group, onOpen: () -> Unit, onManage: () -> Unit) {
    ListRow(
        headline = group.name,
        supporting = if (group.members.size == 1) "1 member" else "${group.members.size} members",
        onClick = onOpen,
        leading = {
            GroupGlyph(group.members.map { GlyphGenerator.seedFor(it.nostrPubkey, null, it.name) }, size = AvatarSize)
        },
        trailing = {
            TextButton(onClick = onManage) {
                Text("Edit", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}
