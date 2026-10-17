package com.relay.app.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.relay.app.data.model.Message
import com.relay.app.messaging.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MediaEventNote(message: Message, name: String, group: Long?) {
    val event=remember(message.body) { ConversationEventBody.parse(message.body) }
    val who=if(message.isSent) "You" else name
    val text=if(event?.kind=="media_saved") "$who saved a photo" else "$who took a screenshot"
    val targets=if(event?.kind=="media_saved") listOf(event.target) else event?.targets.orEmpty()
    Column(Modifier.fillMaxWidth().padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text(text+" · "+java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(message.timestamp)),style=MaterialTheme.typography.bodySmall)
        targets.forEach { target -> MediaEventTarget(message.contactId,group,target) }
    }
}

@Composable
private fun MediaEventTarget(contact: Long, group: Long?, target: String) {
    val context=LocalContext.current
    val path by produceState<String?>(null,contact,group,target) {
        value=withContext(Dispatchers.IO) {
            val sql=MessagingDb.get(context).readableDatabase
            val query=if(group==null) "SELECT media_uri FROM messages WHERE contact_id=? AND msg_id=? AND type='IMAGE'" else "SELECT media_uri FROM group_messages WHERE group_id=? AND event_id=? AND type='IMAGE'"
            sql.rawQuery(query,arrayOf((group ?: contact).toString(),target)).use { if(it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
        }
    }
    val file=path?.let { rememberDisplayFile(it) }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        if(file!=null) AsyncImage(file,contentDescription="Referenced photo $target",contentScale=ContentScale.Fit,modifier=Modifier.size(64.dp))
        Text("Photo ${target.take(8)}",style=MaterialTheme.typography.labelSmall)
    }
}
