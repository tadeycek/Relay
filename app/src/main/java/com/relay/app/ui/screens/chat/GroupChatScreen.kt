package com.relay.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.MessageType
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val groupTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
fun GroupChatScreen(groupId: Long, navController: NavController) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: GroupChatViewModel = viewModel(factory = GroupChatViewModelFactory(groupId, app))
    val group by vm.group.collectAsState()
    val messages by vm.messages.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        vm.registerUpdates(context)
        onDispose { vm.unregisterUpdates(context) }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            RelayTopBar(
                title = group?.name ?: "",
                onBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = { vm.sendLocationRequest(context) }) {
                        Icon(
                            imageVector = Icons.Outlined.LocationSearching,
                            contentDescription = "Request location",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
        containerColor = Background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    GroupMessageBubble(message = message)
                }
            }

            GroupMessageInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        vm.sendText(inputText.trim(), context)
                        inputText = ""
                    }
                },
            )
        }
    }
}

@Composable
private fun GroupMessageBubble(message: GroupMessage) {
    val isSent = message.isSent
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSent) Arrangement.End else Arrangement.Start,
    ) {
        val bgColor = when {
            message.type == MessageType.LOCATION_REQUEST -> Accent.copy(alpha = 0.15f)
            message.type == MessageType.LOCATION_DECLINED -> Surface2
            isSent -> Accent
            else -> Surface2
        }
        val shape = RectangleShape
        val textColor = if (isSent && message.type == MessageType.TEXT) OnAccent else TextPrimary

        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(bgColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = when (message.type) {
                    MessageType.LOCATION_REQUEST -> if (isSent) "Location request sent" else "Location request received"
                    MessageType.LOCATION_DECLINED -> if (isSent) "You declined" else "Location declined"
                    MessageType.LOCATION -> "${"%.5f".format(message.lat)}, ${"%.5f".format(message.lng)}" +
                        (message.pinLabel?.let { "\n$it" } ?: "")
                    else -> message.body
                },
                color = if (message.type == MessageType.TEXT && isSent) OnAccent else TextPrimary,
                fontFamily = IbmPlexSans,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Text(
                text = groupTimeFormat.format(Date(message.timestamp)),
                color = if (isSent) OnAccent.copy(alpha = 0.6f) else TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun GroupMessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = value.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface1)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message group", color = TextSecondary, fontFamily = IbmPlexSans) },
            singleLine = false,
            maxLines = 4,
            shape = RectangleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent,
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface2,
            ),
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .background(
                    color = if (canSend) Accent else Surface2,
                    shape = RectangleShape,
                ),
        ) {
            IconButton(onClick = onSend, enabled = canSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send",
                    tint = if (canSend) OnAccent else TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
