package com.relay.app.ui.screens.chat

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.relay.app.groups.SharedGroups
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.MessagingRuntime
import com.relay.app.messaging.ReplyBody
import com.relay.app.messaging.TypingSignals
import com.relay.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun ReplyPreview(draft: String?, onCancel: () -> Unit) {
    val reply = draft?.let { ReplyBody.parse(it) } ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text("Reply to ${reply.name}", style = MaterialTheme.typography.labelMedium)
            Text(reply.quote, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        TextButton(onClick = onCancel) { Text("Cancel reply") }
    }
}

/** Signals contain no text; stopping/expiry also handles an interrupted or disconnected client. */
@Composable
internal fun ConversationTyping(text: String, contactId: Long? = null, groupId: Long? = null, revisionKey: Any? = null) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val entries by TypingSignals.entries.collectAsState()
    val group by produceState<SharedGroups.Stored?>(null, groupId, revisionKey) {
        value = groupId?.let { withContext(Dispatchers.IO) { SharedGroups.get(MessagingDb.get(context), it) } }
    }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(500) } }
    fun signal(typing: Boolean) {
        MessagingRuntime.launchIo { TypingSignals.send(context, contactId, groupId, typing) }
    }
    LaunchedEffect(text, contactId, groupId) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) signal(text.isNotBlank())
        if (text.isNotBlank()) { delay(3500); signal(false) }
    }
    DisposableEffect(lifecycle, contactId, groupId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) signal(false) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); signal(false) }
    }
    val names = TypingSignals.names(entries, contactId, group?.state, now)
    if (names != null) Text("$names typing…", style = MaterialTheme.typography.bodySmall,
        color = TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}
