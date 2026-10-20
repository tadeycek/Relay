package com.relay.app.messaging

import android.content.Context
import android.content.Intent
import com.relay.app.data.model.Message
import com.relay.app.data.repository.ContactRepository
import com.relay.app.groups.SharedGroups
import com.relay.app.transport.nostr.NostrIdentity
import java.io.File

object MessageDeletion {
    val viewGeneration = kotlinx.coroutines.flow.MutableStateFlow(0L)
    const val CREATE = "CREATE TABLE message_deletions (scope TEXT NOT NULL, author TEXT NOT NULL, event TEXT NOT NULL, PRIMARY KEY(scope,author,event))"
    const val CREATE_PURGES = "CREATE TABLE message_purges (scope TEXT NOT NULL, author TEXT NOT NULL, before_ms INTEGER NOT NULL, PRIMARY KEY(scope,author))"
    fun isDeleted(db: com.relay.app.data.db.RelayDbHelper, scope: String, author: String, event: String, timestamp: Long = Long.MAX_VALUE) =
        db.readableDatabase.rawQuery("SELECT 1 FROM message_deletions WHERE scope=? AND author=? AND event=?", arrayOf(scope, author, event)).use { it.moveToFirst() } ||
        db.readableDatabase.rawQuery("SELECT 1 FROM message_purges WHERE scope=? AND author=? AND before_ms>=?", arrayOf(scope, author, timestamp.toString())).use { it.moveToFirst() }
    fun purge(context: Context, scope: String, author: String, before: Long, outgoing: Boolean = false) {
        val db = MessagingDb.get(context).writableDatabase
        db.execSQL("INSERT OR IGNORE INTO message_purges(scope,author,before_ms) VALUES(?,?,?)", arrayOf(scope, author, before))
        db.execSQL("UPDATE message_purges SET before_ms=MAX(before_ms,?) WHERE scope=? AND author=?", arrayOf(before, scope, author))
        val id = scope.substringAfter(':').toLong()
        if (scope.startsWith("direct:")) synchronized(this) {
            remove(context, "messages", "contact_id=? AND is_sent=? AND timestamp<=?", arrayOf(id.toString(), if(outgoing) "1" else "0", before.toString()))
            MessageNotifier.cancel(context, id)
            context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id", id))
        } else {
            remove(context, "group_messages", "group_id=? AND sender_key=? AND timestamp<=?", arrayOf(id.toString(), author, before.toString()))
            GroupMessageNotifier.cancel(context, id); SharedGroups.notify(context, id)
        }
    }
    private fun tombstone(context: Context, scope: String, author: String, event: String) {
        MessagingDb.get(context).writableDatabase.execSQL("INSERT OR IGNORE INTO message_deletions(scope,author,event) VALUES(?,?,?)", arrayOf(scope, author, event))
    }
    fun expire(context: Context, before: Long) {
        val db = MessagingDb.get(context).writableDatabase
        val contacts = db.rawQuery("SELECT DISTINCT contact_id FROM messages WHERE timestamp<=?", arrayOf(before.toString())).use { c ->
            buildList { while(c.moveToNext()) add(c.getLong(0)) } }
        val groups = db.rawQuery("SELECT DISTINCT group_id FROM group_messages WHERE timestamp<=?", arrayOf(before.toString())).use { c ->
            buildList { while(c.moveToNext()) add(c.getLong(0)) } }
        // Cancel every fanout leg before removing its source message.
        db.execSQL("DELETE FROM group_dispatch WHERE created_at<=? OR (requires_source=1 AND NOT EXISTS(SELECT 1 FROM group_messages WHERE group_messages.group_id=group_dispatch.group_id AND group_messages.event_id=group_dispatch.event AND is_sent=1))", arrayOf(before))
        db.execSQL("DELETE FROM outbox WHERE payload_id IN (SELECT d.payload_id FROM group_delivery d JOIN group_messages m ON m.group_id=d.group_id AND m.event_id=d.event WHERE m.timestamp<=? AND m.is_sent=1)", arrayOf(before))
        db.execSQL("DELETE FROM outbox WHERE payload_id IN (SELECT msg_id FROM messages WHERE timestamp<=? AND is_sent=1) OR created_at<=?", arrayOf(before,before))
        db.execSQL("DELETE FROM group_delivery WHERE NOT EXISTS (SELECT 1 FROM outbox WHERE outbox.payload_id=group_delivery.payload_id) AND EXISTS (SELECT 1 FROM group_messages m WHERE m.group_id=group_delivery.group_id AND m.event_id=group_delivery.event AND m.timestamp<=?)", arrayOf(before))
        if (contacts.isNotEmpty()) remove(context, "messages", "timestamp<=?", arrayOf(before.toString()))
        if (groups.isNotEmpty()) remove(context, "group_messages", "timestamp<=?", arrayOf(before.toString()))
        val paths = db.rawQuery("SELECT path FROM pending_attachments WHERE created_at<=? OR (is_group=0 AND NOT EXISTS(SELECT 1 FROM messages WHERE messages._id=pending_attachments.row_id AND messages.msg_id=pending_attachments.id)) OR (is_group=1 AND NOT EXISTS(SELECT 1 FROM group_messages WHERE group_messages._id=pending_attachments.row_id AND group_messages.event_id=pending_attachments.id))", arrayOf(before.toString())).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        paths.forEach { path ->
            val file = File(path).canonicalFile
            if (file.path.startsWith(context.filesDir.canonicalPath + "/")) file.delete()
            db.delete("pending_attachments", "path=?", arrayOf(path))
        }
        db.delete("reactions", "received_at<=?", arrayOf(before.toString()))
        db.execSQL("DELETE FROM group_reads WHERE NOT EXISTS(SELECT 1 FROM group_messages m WHERE m.group_id=group_reads.group_id AND m.event_id=group_reads.event AND m.is_sent=1)")
        db.execSQL("DELETE FROM group_read_sent WHERE NOT EXISTS(SELECT 1 FROM group_messages m WHERE m.group_id=group_read_sent.group_id AND m.event_id=group_read_sent.event AND m.sender_key=group_read_sent.author AND m.is_sent=0)")
        contacts.forEach { id -> MessageNotifier.cancel(context,id)
            context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id",id)) }
        groups.forEach { id -> GroupMessageNotifier.cancel(context,id); SharedGroups.notify(context,id) }
    }
    private fun remove(context: Context, table: String, where: String, args: Array<String>) {
        val db = MessagingDb.get(context).writableDatabase
        val paths = db.rawQuery("SELECT media_uri FROM $table WHERE $where", args).use { c ->
            buildList { while (c.moveToNext()) if (!c.isNull(0)) add(c.getString(0)) }
        }
        val eventColumn = if(table == "messages") "msg_id" else "event_id"
        val ownerColumn = if(table == "messages") "contact_id" else "group_id"
        val prefix = if(table == "messages") "direct:" else "group:"
        val targets = db.rawQuery("SELECT $eventColumn,$ownerColumn FROM $table WHERE $where",args).use { c ->
            buildList { while(c.moveToNext()) if(!c.isNull(0)) add(c.getString(0) to c.getLong(1)) }
        }
        targets.forEach { (event,id) -> db.delete("reactions","scope=? AND target=?",arrayOf("$prefix$id",event)) }
        ConversationEvents.changes.value += 1
        db.delete(table, where, args)
        paths.forEach { path ->
            // App-private media only; never remove external files or arbitrary sender paths.
            val f = File(path).canonicalFile
            if (f.path.startsWith(context.filesDir.canonicalPath + "/") || f.path.startsWith(context.cacheDir.canonicalPath + "/")) f.delete()
        }
        File(context.cacheDir, "private-media-view").deleteRecursively()
        File(context.cacheDir, "mms_view").deleteRecursively()
        viewGeneration.value += 1
    }
    @Synchronized fun applyDirect(context: Context, contact: Long, author: String, event: String, outgoing: Boolean) {
        tombstone(context, "direct:$contact", author, event)
        remove(context, "messages", "contact_id=? AND msg_id=? AND is_sent=?", arrayOf(contact.toString(), event, if (outgoing) "1" else "0"))
        MessageNotifier.cancel(context, contact)
        context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id", contact))
    }
    fun applyGroup(context: Context, group: Long, author: String, event: String) {
        tombstone(context, "group:$group", author, event)
        remove(context, "group_messages", "group_id=? AND event_id=? AND sender_key=?", arrayOf(group.toString(), event, author))
        GroupMessageNotifier.cancel(context, group)
        SharedGroups.notify(context, group)
    }
    fun delete(context: Context, message: Message, group: Long? = null, everyone: Boolean) {
        if (group != null) {
            if (everyone) {
                require(message.isSent && message.msgId != null)
                SharedGroups.deleteMessage(context, group, message.msgId!!)
            } else synchronized(SharedGroups) {
                if (message.msgId != null) {
                    val author = if (message.isSent) NostrIdentity.publicKeyHex(context) else
                        MessagingDb.get(context).readableDatabase.rawQuery("SELECT sender_key FROM group_messages WHERE group_id=? AND _id=?", arrayOf(group.toString(), message.id.toString())).use { if (it.moveToFirst()) it.getString(0) else null }
                    if (author != null) tombstone(context, "group:$group", author, message.msgId)
                }
                remove(context, "group_messages", "group_id=? AND _id=?", arrayOf(group.toString(), message.id.toString()))
                GroupMessageNotifier.cancel(context, group); SharedGroups.notify(context, group)
            }
        } else {
            if (everyone) {
                require(message.isSent && message.msgId != null && DeletionBody.validId(message.msgId))
                val contact = ContactRepository(MessagingDb.get(context)).getByIdSync(message.contactId) ?: error("Contact unavailable")
                check(Outgoing.enqueue(context, contact, DeletionBody.format(message.msgId)) != null)
                applyDirect(context, contact.id, NostrIdentity.publicKeyHex(context), message.msgId, true)
            } else synchronized(this) {
                message.msgId?.let { event ->
                    val author = if (message.isSent) NostrIdentity.publicKeyHex(context) else ContactRepository(MessagingDb.get(context)).getByIdSync(message.contactId)?.nostrPubkey
                    if (author != null) tombstone(context, "direct:${message.contactId}", author, event)
                }
                remove(context, "messages", "contact_id=? AND _id=?", arrayOf(message.contactId.toString(), message.id.toString()))
                MessageNotifier.cancel(context, message.contactId)
                context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id", message.contactId))
            }
        }
    }
}
