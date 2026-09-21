package com.relay.app.ui.screens.messages

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.conversation.ConversationFormat
import com.relay.app.data.conversation.ConversationSummary
import com.relay.app.data.model.DeliveryState
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.Surface3
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary

/** The middle tab: every chat and group, newest activity first, with unread counts. */
@Composable
fun MessagesScreen(navController: NavController) {
    val vm: MessagesViewModel = viewModel()
    val conversations by vm.conversations.collectAsState()
    val loaded by vm.loaded.collectAsState()

    val requests = conversations.filter { it.isRequest }
    val chats = conversations.filterNot { it.isRequest }

    fun open(c: ConversationSummary) = navController.navigate(
        if (c.kind == ConversationSummary.Kind.GROUP) Screen.GroupChat.routeFor(c.id) else Screen.Chat.routeFor(c.id)
    )

    Scaffold(
        topBar = {
            RelayTopBar(
                title = "Messages",
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.QrExchange.route) }) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Add a contact by QR code",
                            tint = TextSecondary,
                        )
                    }
                },
            )
        },
        containerColor = Background,
    ) { padding ->
        when {
            !loaded -> Box(Modifier.fillMaxSize().padding(padding))

            conversations.isEmpty() -> EmptyState(
                modifier = Modifier.fillMaxSize().padding(padding),
                onPair = { navController.navigate(Screen.QrExchange.route) },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                if (requests.isNotEmpty()) {
                    item { SectionLabel("REQUESTS  ·  people you haven't met in person") }
                    items(requests, key = { "req_${it.kind}_${it.id}" }) { c -> ConversationRow(c, onClick = { open(c) }) }
                    if (chats.isNotEmpty()) item { SectionLabel("CHATS") }
                }
                items(chats, key = { "chat_${it.kind}_${it.id}" }) { c -> ConversationRow(c, onClick = { open(c) }) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontFamily = IbmPlexMono,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun EmptyState(modifier: Modifier, onPair: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No conversations yet", color = TextPrimary, fontFamily = IbmPlexSans, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Relay contacts are added by scanning each other's QR codes in person.",
            color = TextSecondary,
            fontFamily = IbmPlexSans,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPair,
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
        ) { Text("Pair with a friend") }
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, onClick: () -> Unit) {
    val unread = c.unread > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(RectangleShape).background(Surface3),
        ) {
            if (c.kind == ConversationSummary.Kind.GROUP) {
                Icon(Icons.Outlined.Group, contentDescription = "Group", tint = Accent, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    text = c.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    color = Accent,
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = c.name,
                color = TextPrimary,
                fontFamily = IbmPlexSans,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.lastIsSent && c.hasMessages) {
                    DeliveryTick(c)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    // A sent message shows a delivery tick instead of the "You: " prefix.
                    text = ConversationFormat.preview(c.lastType, c.lastBody, c.lastIsSent).removePrefix("You: "),
                    color = if (unread) TextPrimary else TextSecondary,
                    fontFamily = IbmPlexSans,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = ConversationFormat.timeLabel(c.lastTimestamp, System.currentTimeMillis()),
                color = if (unread) Accent else TextSecondary,
                fontFamily = IbmPlexMono,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
            if (unread) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.clip(RectangleShape).background(Accent).padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = ConversationFormat.badge(c.unread),
                        color = OnAccent,
                        fontFamily = IbmPlexMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
            } else {
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun DeliveryTick(c: ConversationSummary) {
    val (icon, desc) = when {
        c.lastReadAt != null -> Icons.Filled.DoneAll to "Read"
        c.lastDeliveryState == DeliveryState.QUEUED -> Icons.Outlined.Schedule to "Waiting to send"
        c.lastDeliveryState == DeliveryState.FAILED -> Icons.Outlined.ErrorOutline to "Failed to send"
        else -> Icons.Filled.Done to "Sent"
    }
    Icon(imageVector = icon, contentDescription = desc, tint = TextSecondary, modifier = Modifier.size(13.dp))
}
