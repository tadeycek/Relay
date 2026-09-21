package com.relay.app.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.Message
import com.relay.app.ui.glyph.GlyphGenerator
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextSecondary

/** Group messages share the 1:1 bubbles; the sender's contact id lets the timeline tell members apart. */
private fun GroupMessage.asMessage() = Message(
    id = id,
    contactId = contactId ?: 0L,
    body = body,
    type = type,
    lat = lat,
    lng = lng,
    isSent = isSent,
    timestamp = timestamp,
    mediaUri = mediaUri,
    pinLabel = pinLabel,
    expiryAt = expiryAt,
    unread = unread,
)

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

    val items = remember(messages) {
        ChatTimeline.build(messages.map { it.asMessage() }, firstUnreadId = null, nowMs = System.currentTimeMillis())
    }
    val names = remember(group) { group?.members.orEmpty().associate { it.id to it.name } }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.scrollToItem(items.size - 1)
    }

    Scaffold(
        topBar = {
            GroupChatTopBar(
                name = group?.name.orEmpty(),
                memberSeeds = group?.members.orEmpty().map { GlyphGenerator.seedFor(it.nostrPubkey, null, it.name) },
                memberCount = group?.members?.size ?: 0,
                onBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = { vm.sendLocationRequest(context) }) {
                        Icon(
                            imageVector = Icons.Outlined.LocationSearching,
                            contentDescription = "Ask the group for their location",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
        containerColor = Background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = RelaySpacing.lg, vertical = RelaySpacing.md),
            ) {
                items(items, key = { it.key }) { item ->
                    ChatItemRow(item, contactName = "Someone", senderName = { names[it] })
                }
            }
            Composer(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = "Message the group",
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
