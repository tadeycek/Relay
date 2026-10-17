package com.relay.app.ui.screens.chat

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import com.relay.app.messaging.ConversationNotifications

@Composable
internal fun ConversationMute(id: Long, group: Boolean = false) {
    val context = LocalContext.current
    var muted by remember(id, group) { mutableStateOf(ConversationNotifications.muted(context, id, group)) }
    TextButton(onClick = {
        ConversationNotifications.setMuted(context, id, group, !muted)
        muted = !muted
    }) {
        Icon(if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsActive,
            contentDescription = if (muted) "Notifications muted" else "Notifications enabled")
        Text(if (muted) "Unmute" else "Mute")
    }
}
