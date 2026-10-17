package com.relay.app.ui.screens.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.relay.app.messaging.ConversationNotifications

@Composable
internal fun ConversationMenu(id: Long, group: Boolean = false, onShare: (() -> Unit)? = null,
    onRequest: (() -> Unit)? = null, onMembers: (() -> Unit)? = null) {
    val context = LocalContext.current
    var open by remember(id,group) { mutableStateOf(false) }
    var muted by remember(id,group) { mutableStateOf(ConversationNotifications.muted(context,id,group)) }
    val color = MaterialTheme.colorScheme.onSurface
    Box {
        IconButton(onClick = { muted = ConversationNotifications.muted(context,id,group); open = true },
            modifier = Modifier.semantics { contentDescription = "Conversation options" }) {
            Canvas(Modifier.size(6.dp)) { drawCircle(color) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(if(muted) "Unmute notifications" else "Mute notifications") }, onClick = {
                ConversationNotifications.setMuted(context,id,group,!muted); muted = !muted; open = false
            })
            onShare?.let { DropdownMenuItem(text = { Text("Share my location") }, onClick = { open=false; it() }) }
            onRequest?.let { DropdownMenuItem(text = { Text("Ask for their location") }, onClick = { open=false; it() }) }
            onMembers?.let { DropdownMenuItem(text = { Text("Members and group settings") }, onClick = { open=false; it() }) }
            DropdownMenuItem(text = { Text("Messages delete after 7 days") }, onClick = {}, enabled = false)
            DropdownMenuItem(text = { Text("About screenshot notices") }, onClick = { open=false
                android.app.AlertDialog.Builder(context).setTitle("Screenshot notices")
                    .setMessage("Android 14+ can report some screenshots while a chat is visible. Notices identify visible photos when possible. Other capture methods and copies made outside the app cannot be detected. Saving with the app's Save action sends a notice. Android 13 and earlier have no screenshot detection.")
                    .setPositiveButton("OK",null).show()
            })
        }
    }
}
