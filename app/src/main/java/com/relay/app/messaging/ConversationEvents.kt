package com.relay.app.messaging

import android.content.Context
import android.content.Intent
import com.relay.app.data.model.*
import com.relay.app.data.repository.*
import com.relay.app.groups.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

object ConversationEvents {
    const val CREATE = "CREATE TABLE reactions (scope TEXT NOT NULL,target TEXT NOT NULL,actor TEXT NOT NULL,revision INTEGER NOT NULL,emoji TEXT NOT NULL,PRIMARY KEY(scope,target,actor))"
    val changes = MutableStateFlow(0L)
    fun scope(contact: Long, group: Long?) = group?.let { "group:$it" } ?: "direct:$contact"
    fun apply(context: Context, scope: String, actor: String, event: ConversationEventBody.Event) {
        if (event.kind != "reaction") return
        if(MessagingDb.get(context).readableDatabase.rawQuery("SELECT 1 FROM message_deletions WHERE scope=? AND event=?",arrayOf(scope,event.target)).use { it.moveToFirst() }) return
        MessagingDb.get(context).writableDatabase.execSQL("INSERT INTO reactions(scope,target,actor,revision,emoji) VALUES(?,?,?,?,?) ON CONFLICT(scope,target,actor) DO UPDATE SET revision=excluded.revision,emoji=excluded.emoji WHERE excluded.revision > reactions.revision",
            arrayOf(scope,event.target,actor,event.revision,event.emoji))
        changes.value += 1
    }
    fun entries(context: Context, scope: String, target: String): List<Pair<String,String>> = MessagingDb.get(context).readableDatabase.rawQuery(
        "SELECT actor,emoji FROM reactions WHERE scope=? AND target=? AND emoji!='' ORDER BY actor", arrayOf(scope,target)).use { c ->
        buildList { while(c.moveToNext()) add(c.getString(0) to c.getString(1)) }
    }
    fun sendReaction(context: Context, message: Message, group: Long?, emoji: String) = synchronized(SharedGroups) {
        synchronized(MessageDeletion) {
            require(emoji.isEmpty() || emoji in ConversationEventBody.EMOJI)
            val target = message.msgId ?: error("This older message doesn't support reactions")
            require(DeletionBody.validId(target))
            val own = com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context)
            val scope = scope(message.contactId, group)
            val db = MessagingDb.get(context); val sql = db.writableDatabase
            sql.beginTransaction()
            try {
                val revision = sql.rawQuery("SELECT revision FROM reactions WHERE scope=? AND target=? AND actor=?", arrayOf(scope,target,own))
                    .use { if(it.moveToFirst()) it.getLong(0)+1 else 1L }
                val event = ConversationEventBody.Event("reaction",UUID.randomUUID().toString(),target,emoji,revision)
                val body = ConversationEventBody.format(event)
                if (group != null) SharedGroups.send(context,group,body,event=event.id)
                else {
                    val contact = ContactRepository(db).getByIdSync(message.contactId)?.takeIf(GroupProtocol::eligible) ?: error("Pair with this contact before reacting")
                    check(Outgoing.enqueue(context,contact,body,payloadId=event.id)!=null)
                    apply(context,scope,own,event)
                }
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
            MessagingRuntime.kick()
        }
    }
    fun screenshot(context: Context, contact: Long?, group: Long?) {
        val event = ConversationEventBody.Event("screenshot",UUID.randomUUID().toString())
        val body = ConversationEventBody.format(event)
        if (group != null) SharedGroups.send(context, group, body, event=event.id)
        else if (contact != null) {
            val db = MessagingDb.get(context); val sql = db.writableDatabase
            val peer = ContactRepository(db).getByIdSync(contact)?.takeIf(GroupProtocol::eligible) ?: error("Screenshot notice couldn't be queued: contact needs pairing")
            sql.beginTransaction()
            try {
                check(Outgoing.enqueue(context,peer,body,payloadId=event.id)!=null)
                check(MessageRepository(db).insertMessageSync(Message(contactId=contact,body=body,type=MessageType.SCREENSHOT,msgId=event.id,deliveryState=DeliveryState.QUEUED))!=-1L)
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
            context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id",contact))
        }
    }
    fun handle(context: Context, sender: Contact, body: String, payloadId: String, time: Long): Boolean {
        if (!body.startsWith(ConversationEventBody.PREFIX)) return false
        if (!GroupProtocol.eligible(sender)) return true
        val event=ConversationEventBody.parse(body) ?: return true
        if(event.kind=="reaction") apply(context,scope(sender.id,null),sender.nostrPubkey!!,event)
        else check(MessageRepository(MessagingDb.get(context)).insertMessageSync(Message(contactId=sender.id,body=body,type=MessageType.SCREENSHOT,isSent=false,msgId=payloadId,timestamp=time))!=-1L)
        context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id",sender.id))
        return true
    }
}
