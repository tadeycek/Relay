package com.relay.app.ui.screens.chat

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import com.relay.app.data.model.Message
import com.relay.app.messaging.ConversationEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MessageReactions(message:Message,group:Long?) {
    val target=message.msgId ?: return
    val context=LocalContext.current
    val revision by ConversationEvents.changes.collectAsState()
    val summary by produceState("",target,group,revision) {
        value=withContext(Dispatchers.IO) {
            val own=com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context)
            val db=com.relay.app.messaging.MessagingDb.get(context)
            ConversationEvents.entries(context,ConversationEvents.scope(message.contactId,group),target).joinToString(" · ") { (actor,emoji) ->
                val name=if(actor==own) "You" else if(group!=null) com.relay.app.groups.GroupProfiles.display(db,group,actor,"Member")
                    else com.relay.app.data.repository.ContactRepository(db).findByNostrPubkeySync(actor)?.name ?: "Contact"
                "$emoji $name"
            }
        }
    }
    if(summary.isNotEmpty()) Text(summary,style=androidx.compose.material3.MaterialTheme.typography.labelSmall)
}
