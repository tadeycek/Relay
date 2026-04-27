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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Group
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.Group
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.Border
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface2
import com.relay.app.ui.theme.Surface3
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(navController: NavController) {
    val vm: ContactsViewModel = viewModel()
    val contacts by vm.contacts.collectAsState()
    val groups by vm.groups.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showNewGroupDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            RelayTopBar(
                title = "Contacts",
                onBack = { navController.popBackStack() },
                actions = {
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
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableGroupRow(
    group: Group,
    onChatClick: () -> Unit,
    onDeleteClick: () -> Unit,
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFCC2200)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.padding(end = 20.dp))
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        GroupRow(group = group, onChatClick = onChatClick)
    }
}

@Composable
private fun GroupRow(group: Group, onChatClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .clickable(onClick = onChatClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableContactRow(
    contact: Contact,
    onChatClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onTrustLevelClick: () -> Unit,
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
                    .clip(RoundedCornerShape(12.dp))
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
        ContactRow(contact = contact, onChatClick = onChatClick, onTrustLevelClick = onTrustLevelClick)
    }
}

@Composable
private fun ContactRow(contact: Contact, onChatClick: () -> Unit, onTrustLevelClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .clickable(onClick = onChatClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
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
                            .clip(CircleShape)
                            .background(Accent),
                    )
                }
            }
            Text(
                text = contact.phone,
                color = TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 12.sp,
            )
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
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
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number", fontFamily = IbmPlexSans) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = fieldColors,
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
