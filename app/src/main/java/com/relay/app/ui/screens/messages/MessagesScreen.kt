package com.relay.app.ui.screens.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.conversation.ConversationFormat
import com.relay.app.data.conversation.ConversationSummary
import com.relay.app.data.model.DeliveryState
import com.relay.app.ui.components.ChipKind
import com.relay.app.ui.components.DeliveryTick
import com.relay.app.ui.components.EmptyState
import com.relay.app.ui.components.Glyph
import com.relay.app.ui.components.GroupGlyph
import com.relay.app.ui.components.RowDivider
import com.relay.app.ui.components.ScreenHeader
import com.relay.app.ui.components.SectionHeader
import com.relay.app.ui.components.StatusChip
import com.relay.app.ui.glyph.GlyphState
import com.relay.app.ui.navigation.Screen
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.OnAccent
import com.relay.app.ui.theme.RelaySpacing
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import com.relay.app.ui.theme.Verified

private val AvatarSize = 52.dp
/** Divider inset that lines up with the text after the avatar. */
private val TextInset = RelaySpacing.gutter + AvatarSize + RelaySpacing.md

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
    fun pair() = navController.navigate(Screen.QrExchange.route)

    Column(modifier = Modifier.fillMaxSize().background(Background)) {
        ScreenHeader(title = "Messages") {
            IconButton(onClick = ::pair) {
                Icon(Icons.Outlined.Add, contentDescription = "Add a friend by scanning their code", tint = TextPrimary)
            }
        }
        when {
            !loaded -> Box(Modifier.fillMaxSize())

            conversations.isEmpty() -> EmptyState(
                modifier = Modifier.fillMaxSize(),
                title = "No conversations yet",
                message = "Meet a friend and scan each other's codes. Then say hello.",
                actionLabel = "Scan a friend's code",
                onAction = ::pair,
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (requests.isNotEmpty()) {
                    item(key = "requests_header") { SectionHeader("Requests") }
                    items(requests, key = { "req_${it.kind}_${it.id}" }) { c ->
                        ConversationRow(c, onClick = { open(c) })
                        RowDivider(inset = TextInset)
                    }
                    if (chats.isNotEmpty()) item(key = "chats_header") { SectionHeader("Chats") }
                }
                items(chats, key = { "chat_${it.kind}_${it.id}" }) { c ->
                    ConversationRow(c, onClick = { open(c) })
                    RowDivider(inset = TextInset)
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(c: ConversationSummary, onClick: () -> Unit) {
    val unread = c.unread > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = RelaySpacing.gutter, vertical = RelaySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConversationAvatar(c)
        Spacer(Modifier.width(RelaySpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RelaySpacing.sm)) {
                Text(
                    text = c.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.contactDeleted) StatusChip("Deleted", ChipKind.NEUTRAL)
                else if (c.isRequest) StatusChip("Not verified", ChipKind.NEUTRAL)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (c.lastIsSent && c.hasMessages) {
                    DeliveryTick(readAt = c.lastReadAt, deliveryState = c.lastDeliveryState)
                    Spacer(Modifier.width(RelaySpacing.xs))
                }
                Text(
                    // A sent message shows a delivery tick instead of the "You: " prefix.
                    text = ConversationFormat.preview(c.lastType, c.lastBody, c.lastIsSent).removePrefix("You: "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(RelaySpacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = ConversationFormat.timeLabel(c.lastTimestamp, System.currentTimeMillis()),
                style = MaterialTheme.typography.labelMedium,
                color = if (unread) TextPrimary else TextSecondary,
            )
            if (unread) {
                Spacer(Modifier.size(RelaySpacing.xs))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Accent)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = ConversationFormat.badge(c.unread),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnAccent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationAvatar(c: ConversationSummary) {
    if (c.kind == ConversationSummary.Kind.GROUP) {
        GroupGlyph(
            memberSeeds = c.memberSeeds.ifEmpty { listOf(c.name) },
            size = AvatarSize,
            description = "${c.name}, group of ${c.memberCount}",
        )
    } else {
        val state = GlyphState.forContact(hasNostrKey = c.hasKey, verifiedInPerson = c.verified)
        val trust = when (state) {
            GlyphState.VERIFIED -> "verified in person"
            GlyphState.UNVERIFIED -> "not verified"
            else -> "no verification"
        }
        Glyph(seed = c.glyphSeed.ifEmpty { c.name }, state = state, size = AvatarSize, description = "${c.name}, $trust")
    }
}
