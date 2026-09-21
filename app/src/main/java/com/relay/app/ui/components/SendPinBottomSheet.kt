package com.relay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.app.data.model.Contact
import com.relay.app.data.model.Group
import com.relay.app.data.model.PinExpiry
import com.relay.app.ui.theme.Accent
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
fun SendPinBottomSheet(
    contacts: List<Contact>,
    groups: List<Group> = emptyList(),
    selectedContactId: Long?,
    selectedGroupId: Long? = null,
    pinLabel: String = "",
    pinExpiry: PinExpiry = PinExpiry.NEVER,
    onContactSelected: (Long) -> Unit,
    onGroupSelected: (Long) -> Unit = {},
    onPinLabelChange: (String) -> Unit = {},
    onPinExpiryChange: (PinExpiry) -> Unit = {},
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canSend = selectedContactId != null || selectedGroupId != null
    var showExpiryMenu by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surface2,
        contentColor = TextPrimary,
        shape = RectangleShape,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .size(36.dp, 4.dp)
                    .clip(RectangleShape)
                    .background(Surface3)
                    .align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Send Pin To",
                fontFamily = IbmPlexSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Pin label field
            OutlinedTextField(
                value = pinLabel,
                onValueChange = { onPinLabelChange(it.take(30)) },
                placeholder = { Text("Label (optional)", color = TextSecondary, fontFamily = IbmPlexSans) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Accent,
                    focusedContainerColor = Surface1,
                    unfocusedContainerColor = Surface1,
                ),
            )
            Spacer(Modifier.height(8.dp))

            // Expiry picker
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RectangleShape)
                        .background(Surface1)
                        .clickable { showExpiryMenu = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Expires:",
                        color = TextSecondary,
                        fontFamily = IbmPlexSans,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = pinExpiry.label,
                        color = Accent,
                        fontFamily = IbmPlexMono,
                        fontSize = 13.sp,
                    )
                }
                DropdownMenu(
                    expanded = showExpiryMenu,
                    onDismissRequest = { showExpiryMenu = false },
                ) {
                    PinExpiry.entries.forEach { expiry ->
                        DropdownMenuItem(
                            text = { Text(expiry.label, fontFamily = IbmPlexSans, color = if (expiry == pinExpiry) Accent else TextPrimary) },
                            onClick = { onPinExpiryChange(expiry); showExpiryMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (contacts.isEmpty() && groups.isEmpty()) {
                Text(
                    text = "No Relay contacts yet. Relay auto-detects when a contact sends you a pin.",
                    color = TextSecondary,
                    fontFamily = IbmPlexSans,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    if (groups.isNotEmpty()) {
                        item {
                            Text(
                                text = "GROUPS",
                                color = TextSecondary,
                                fontFamily = IbmPlexMono,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        items(groups, key = { "g_${it.id}" }) { group ->
                            val selected = group.id == selectedGroupId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RectangleShape)
                                    .background(if (selected) Surface3 else Color.Transparent)
                                    .clickable { onGroupSelected(group.id) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RectangleShape)
                                        .background(if (selected) Accent else Surface3),
                                ) {
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.Outlined.Group,
                                        contentDescription = null,
                                        tint = if (selected) OnAccent else TextSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(text = group.name, color = TextPrimary, fontFamily = IbmPlexSans, fontSize = 14.sp)
                                    Text(
                                        text = "${group.members.size} members",
                                        color = TextSecondary,
                                        fontFamily = IbmPlexMono,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }

                    if (contacts.isNotEmpty()) {
                        item {
                            Text(
                                text = "CONTACTS",
                                color = TextSecondary,
                                fontFamily = IbmPlexMono,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        items(contacts, key = { it.id }) { contact ->
                            val selected = contact.id == selectedContactId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RectangleShape)
                                    .background(if (selected) Surface3 else Color.Transparent)
                                    .clickable { onContactSelected(contact.id) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RectangleShape)
                                        .background(if (selected) Accent else Surface3),
                                ) {
                                    Text(
                                        text = contact.name.first().uppercaseChar().toString(),
                                        color = if (selected) OnAccent else TextSecondary,
                                        fontFamily = IbmPlexSans,
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 16.sp,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(text = contact.name, color = TextPrimary, fontFamily = IbmPlexSans, fontSize = 14.sp)
                                    Text(text = contact.subtitle, color = TextSecondary, fontFamily = IbmPlexMono, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    disabledContainerColor = Surface3,
                    contentColor = OnAccent,
                    disabledContentColor = TextSecondary,
                ),
            ) {
                Text(text = "Send Pin", fontFamily = IbmPlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
