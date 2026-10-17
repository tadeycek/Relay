package com.relay.app.ui.screens.chat

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.messaging.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MessageActions(message: Message?, group: Long? = null, onReply: (Message) -> Unit, onDismiss: () -> Unit) {
    if (message == null) return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var deleting by remember(message.id) { mutableStateOf(false) }
    var busy by remember(message.id) { mutableStateOf(false) }
    var error by remember(message.id) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (deleting) "Delete message?" else "Message") },
        text = { Column {
            if (deleting) {
                Text("Delete on this phone, or request deletion from everyone for a message you sent. Other devices must run a compatible app and receive the request. Saved copies and screenshots cannot be erased.")
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { MessageDeletion.delete(context, message, group, false) } }
                        busy = false
                        result.onSuccess { onDismiss() }.onFailure { error = it.message }
                    }
                }) { Text("Delete for me") }
                if (message.isSent && message.msgId?.let(DeletionBody::validId) == true) TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { MessageDeletion.delete(context, message, group, true) } }
                        busy = false
                        result.onSuccess { onDismiss() }.onFailure { error = it.message }
                    }
                }) { Text("Delete for everyone") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else {
                if (message.msgId?.let(DeletionBody::validId) == true) {
                    Text("React to this message")
                    androidx.compose.foundation.layout.FlowRow {
                        ConversationEventBody.EMOJI.forEach { emoji ->
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { runCatching { ConversationEvents.sendReaction(context, message, group, emoji) } }
                                    busy = false; result.onSuccess { onDismiss() }.onFailure { error = it.message }
                                }
                            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) { Text(emoji) }
                        }
                    }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { ConversationEvents.sendReaction(context, message, group, "") } }
                            busy = false; result.onSuccess { onDismiss() }.onFailure { error = it.message }
                        }
                    }) { Text("Remove my reaction") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { onReply(message); onDismiss() }) { Text("Reply") }
                TextButton(onClick = {
                    val text = ReplyBody.preview(message.body).ifBlank { when(message.type) { MessageType.IMAGE -> "Photo"; MessageType.VIDEO -> "Video"; else -> "" } }
                    val clip = android.content.ClipData.newPlainText("Relay private message", text)
                    if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                    onDismiss()
                }) { Text("Copy") }
                TextButton(onClick = { deleting = true }) { Text("Delete…") }
            }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}
