package com.relay.app.ui.screens.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.Group
import com.relay.app.ui.components.PhoneNumberField
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.Surface3
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(navController: NavController) {
    val vm: ContactsViewModel = viewModel()
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val groups by vm.groups.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var manageGroupId by remember { mutableStateOf<Long?>(null) }
    var keyChangeContactId by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(Unit) {
        vm.registerUpdates(context)
        onDispose { vm.unregisterUpdates(context) }
    }

    Scaffold(
        topBar = {
            RelayTopBar(
                title = "Contacts",
                onBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.QrExchange.route) }) {
                        Icon(
                            imageVector = Icons.Outlined.QrCode,
                            contentDescription = "QR contact exchange",
                            tint = TextSecondary,
                        )
                    }
                    IconButton(onClick = { showNewGroupDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Group,
                            contentDescription = "New group",
                            tint = TextSecondary,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = Accent,
                contentColor = OnAccent,
                shape = RectangleShape,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add contact")
            }
        },
        containerColor = Background,
    ) { padding ->
        if (contacts.isEmpty() && groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No contacts yet",
                    color = TextSecondary,
                    fontFamily = IbmPlexSans,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (groups.isNotEmpty()) {
                    item {
                        Text(
                            text = "GROUPS",
                            color = TextSecondary,
                            fontFamily = IbmPlexMono,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(groups, key = { "group_${it.id}" }) { group ->
                        SwipeableGroupRow(
                            group = group,
                            onChatClick = { navController.navigate(Screen.GroupChat.routeFor(group.id)) },
                            onDeleteClick = { vm.deleteGroup(group.id) },
                            onManageClick = { manageGroupId = group.id },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                if (contacts.isNotEmpty()) {
                    item {
                        Text(
                            text = "CONTACTS",
                            color = TextSecondary,
                            fontFamily = IbmPlexMono,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(contacts, key = { it.id }) { contact ->
                        SwipeableContactRow(
                            contact = contact,
                            onChatClick = { navController.navigate(Screen.Chat.routeFor(contact.id)) },
                            onDeleteClick = { vm.deleteContact(contact.id) },
                            onTrustLevelClick = {
                                val next = when (contact.trustLevel) {
                                    ContactTrustLevel.TRUSTED -> ContactTrustLevel.ASK
                                    ContactTrustLevel.ASK -> ContactTrustLevel.BLOCKED
                                    ContactTrustLevel.BLOCKED -> ContactTrustLevel.TRUSTED
                                }
                                vm.updateTrustLevel(contact.id, next)
                            },
                            onKeyChangeClick = { keyChangeContactId = contact.id },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onConfirm = { name, phone ->
                vm.addContact(name, phone)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    if (showNewGroupDialog) {
        NewGroupDialog(
            contacts = contacts,
            onConfirm = { name, memberIds ->
                vm.createGroup(name, memberIds)
                showNewGroupDialog = false
            },
            onDismiss = { showNewGroupDialog = false },
        )
    }

    val manageGroup = manageGroupId?.let { id -> groups.find { it.id == id } }
    if (manageGroup != null) {
        ManageGroupMembersDialog(
            group = manageGroup,
            allContacts = contacts,
            onRemoveMember = { contactId -> vm.removeMemberFromGroup(manageGroup.id, contactId) },
            onAddMember = { contactId -> vm.addMemberToGroup(manageGroup.id, contactId) },
            onDismiss = { manageGroupId = null },
        )
    }

    val keyChangeContact = keyChangeContactId?.let { id -> contacts.find { it.id == id } }
    if (keyChangeContact != null && keyChangeContact.pendingPublicKey != null) {
        KeyChangeDialog(
            contact = keyChangeContact,
            onAccept = { vm.acceptKeyChange(keyChangeContact.id); keyChangeContactId = null },
            onReject = { vm.rejectKeyChange(keyChangeContact.id); keyChangeContactId = null },
            onDismiss = { keyChangeContactId = null },
        )
    }
}

/** Short, eyeballable digest of a base64 key — not a substitute for real out-of-band verification
 *  (SMS has no side channel to do that), but lets a user notice an obviously-wrong change. */
private fun keyFingerprint(base64Key: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(base64Key.toByteArray())
    return digest.take(8).joinToString(" ") { "%02X".format(it) }
}

@Composable
private fun KeyChangeDialog(
    contact: Contact,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pending = contact.pendingPublicKey ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("${contact.name}'s key changed", fontFamily = IbmPlexSans, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A different encryption key arrived for this contact. This can happen if they " +
                        "reinstalled Relay or got a new phone — or it can mean someone is spoofing " +
                        "messages from their number. Only accept if you've confirmed this with them.",
                    color = TextSecondary,
                    fontFamily = IbmPlexSans,
                    fontSize = 13.sp,
                )
                Text("CURRENT KEY", color = TextSecondary, fontFamily = IbmPlexMono, fontSize = 10.sp)
                Text(
                    contact.publicKey?.let { keyFingerprint(it) } ?: "(none yet)",
                    color = TextPrimary,
                    fontFamily = IbmPlexMono,
                    fontSize = 12.sp,
                )
                Text("NEW KEY", color = TextSecondary, fontFamily = IbmPlexMono, fontSize = 10.sp)
                Text(keyFingerprint(pending), color = TextPrimary, fontFamily = IbmPlexMono, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text("Accept", color = Color(0xFFFF6E5D), fontFamily = IbmPlexSans) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("Reject", color = Accent, fontFamily = IbmPlexSans) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableGroupRow(
    group: Group,
    onChatClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onManageClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDeleteClick(); true } else false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RectangleShape)
                    .background(Color(0xFFCC2200)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.padding(end = 20.dp))
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        GroupRow(group = group, onChatClick = onChatClick, onManageClick = onManageClick)
    }
}

@Composable
private fun GroupRow(group: Group, onChatClick: () -> Unit, onManageClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(Surface1)
            .clickable(onClick = onChatClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RectangleShape)
                .background(Surface3),
        ) {
            Icon(
                imageVector = Icons.Outlined.Group,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                color = TextPrimary,
                fontFamily = IbmPlexSans,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(
                text = "${group.members.size} member${if (group.members.size == 1) "" else "s"}",
                color = TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onManageClick) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Manage members",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onChatClick) {
            Icon(
                imageVector = Icons.Outlined.ChatBubble,
                contentDescription = "Open group chat",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ManageGroupMembersDialog(
    group: Group,
    allContacts: List<Contact>,
    onRemoveMember: (Long) -> Unit,
    onAddMember: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val memberIds = group.members.map { it.id }.toSet()
    val nonMembers = allContacts.filter { it.id !in memberIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(text = "Manage \"${group.name}\"", fontFamily = IbmPlexSans, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("MEMBERS", color = TextSecondary, fontFamily = IbmPlexMono, fontSize = 11.sp)
                if (group.members.isEmpty()) {
                    Text("No members", color = TextSecondary, fontFamily = IbmPlexSans, fontSize = 13.sp)
                }
                group.members.forEach { member ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(member.name, color = TextPrimary, fontFamily = IbmPlexSans, fontSize = 14.sp)
                        IconButton(onClick = { onRemoveMember(member.id) }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove ${member.name}",
                                tint = Color(0xFFFF6E5D),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                if (nonMembers.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("ADD MEMBER", color = TextSecondary, fontFamily = IbmPlexMono, fontSize = 11.sp)
                    nonMembers.forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddMember(contact.id) }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(contact.name, color = TextPrimary, fontFamily = IbmPlexSans, fontSize = 14.sp)
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Add ${contact.name}",
                                tint = Accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = Accent, fontFamily = IbmPlexSans)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableContactRow(
    contact: Contact,
    onChatClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onTrustLevelClick: () -> Unit,
    onKeyChangeClick: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDeleteClick()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RectangleShape)
                    .background(Color(0xFFCC2200)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = Color.White,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        ContactRow(
            contact = contact,
            onChatClick = onChatClick,
            onTrustLevelClick = onTrustLevelClick,
            onKeyChangeClick = onKeyChangeClick,
        )
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    onChatClick: () -> Unit,
    onTrustLevelClick: () -> Unit,
    onKeyChangeClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(Surface1)
            .clickable(onClick = onChatClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(RectangleShape)
                .background(Surface3),
        ) {
            Text(
                text = contact.name.first().uppercaseChar().toString(),
                color = Accent,
                fontFamily = IbmPlexSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = contact.name,
                    color = TextPrimary,
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                )
                if (contact.hasRelay) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RectangleShape)
                            .background(Accent),
                    )
                }
                if (contact.publicKey != null) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "Encrypted",
                        tint = Accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text = contact.subtitle,
                color = TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 12.sp,
            )
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RectangleShape)
                    .background(Surface2)
                    .clickable(onClick = onTrustLevelClick)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                val trustText = when (contact.trustLevel) {
                    ContactTrustLevel.TRUSTED -> "TRUSTED"
                    ContactTrustLevel.ASK -> "ASK"
                    ContactTrustLevel.BLOCKED -> "BLOCKED"
                }
                val trustColor = when (contact.trustLevel) {
                    ContactTrustLevel.TRUSTED -> Accent
                    ContactTrustLevel.ASK -> TextSecondary
                    ContactTrustLevel.BLOCKED -> Color(0xFFFF6E5D)
                }
                Text(
                    text = trustText,
                    color = trustColor,
                    fontFamily = IbmPlexMono,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                )
            }
        }
        if (contact.pendingPublicKey != null) {
            IconButton(onClick = onKeyChangeClick) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = "Encryption key changed — review",
                    tint = Color(0xFFFF6E5D),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onChatClick) {
            Icon(
                imageVector = Icons.Outlined.ChatBubble,
                contentDescription = "Open chat",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AddContactDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val canSave = name.isNotBlank() && phone.isNotBlank()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent,
        unfocusedBorderColor = Border,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextSecondary,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedContainerColor = Surface2,
        unfocusedContainerColor = Surface2,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        containerColor = Surface1,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(text = "Add Contact", fontFamily = IbmPlexSans, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", fontFamily = IbmPlexSans) },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                PhoneNumberField(
                    onE164Change = { phone = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, phone) }, enabled = canSave) {
                Text("Save", color = if (canSave) Accent else TextSecondary, fontFamily = IbmPlexSans)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary, fontFamily = IbmPlexSans)
            }
        },
    )
}

@Composable
private fun NewGroupDialog(
    contacts: List<Contact>,
    onConfirm: (String, List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<Long>() }
    val canSave = groupName.isNotBlank() && selectedIds.isNotEmpty()

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent,
        unfocusedBorderColor = Border,
        focusedLabelColor = Accent,
        unfocusedLabelColor = TextSecondary,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Accent,
        focusedContainerColor = Surface2,
        unfocusedContainerColor = Surface2,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = {
            Text(text = "New Group", fontFamily = IbmPlexSans, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group name", fontFamily = IbmPlexSans) },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (contacts.isNotEmpty()) {
                    Text(
                        text = "Select members:",
                        color = TextSecondary,
                        fontFamily = IbmPlexSans,
                        fontSize = 13.sp,
                    )
                    contacts.forEach { contact ->
                        val checked = contact.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedIds.remove(contact.id) else selectedIds.add(contact.id)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) selectedIds.add(contact.id) else selectedIds.remove(contact.id)
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Accent,
                                    uncheckedColor = TextSecondary,
                                ),
                            )
                            Text(
                                text = contact.name,
                                color = TextPrimary,
                                fontFamily = IbmPlexSans,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(groupName, selectedIds.toList()) }, enabled = canSave) {
                Text("Create", color = if (canSave) Accent else TextSecondary, fontFamily = IbmPlexSans)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary, fontFamily = IbmPlexSans)
            }
        },
    )
}
