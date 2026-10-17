package com.relay.app.groups

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.Group
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.Outgoing
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.RelayPreferences
import java.util.UUID

/** Creator-mediated private groups. The creator must be online to forward participants' replies.
 * Membership is never promoted to a verified contact on another phone. */
object SharedGroups {
    const val CREATE = """CREATE TABLE shared_groups (
        group_id INTEGER PRIMARY KEY REFERENCES groups(_id) ON DELETE CASCADE,
        owner TEXT NOT NULL, shared_id TEXT NOT NULL, state TEXT NOT NULL,
        hidden INTEGER NOT NULL DEFAULT 0, UNIQUE(owner, shared_id))"""
    const val CREATE_SEEN = """CREATE TABLE shared_group_seen (
        group_id INTEGER NOT NULL REFERENCES groups(_id) ON DELETE CASCADE,
        event TEXT NOT NULL, PRIMARY KEY(group_id, event))"""
    data class Stored(val localId: Long, val state: GroupProtocol.State, val hidden: Boolean)

    fun get(db: RelayDbHelper, id: Long): Stored? = db.readableDatabase.rawQuery(
        "SELECT group_id,state,hidden FROM shared_groups WHERE group_id=?", arrayOf(id.toString()),
    ).use { if (it.moveToFirst()) GroupProtocol.parse(it.getString(1))?.let { p -> Stored(it.getLong(0), p.state, it.getInt(2) != 0) } else null }
    private fun find(db: RelayDbHelper, state: GroupProtocol.State): Stored? = db.readableDatabase.rawQuery(
        "SELECT group_id FROM shared_groups WHERE owner=? AND shared_id=?", arrayOf(state.owner, state.id),
    ).use { if (it.moveToFirst()) get(db, it.getLong(0)) else null }
    fun enrich(db: RelayDbHelper, group: Group): Group? {
        val stored = get(db, group.id) ?: return group
        if (stored.hidden) return null
        return group.copy(name = stored.state.name, sharedOwner = stored.state.owner, members = stored.state.members.map {
            Contact(id = GroupProtocol.memberId(it.key), name = GroupProfiles.display(db, group.id, it.key, it.name), phone = Contact.placeholderPhoneFor(it.key), nostrPubkey = it.key)
        })
    }
    private fun save(db: RelayDbHelper, state: GroupProtocol.State, existing: Stored?): Long {
        val sql = db.writableDatabase
        val id = existing?.localId ?: sql.insertOrThrow("groups", null, ContentValues().apply { put("name", state.name) })
        sql.update("groups", ContentValues().apply { put("name", state.name) }, "_id=?", arrayOf(id.toString()))
        val stored = sql.insertWithOnConflict("shared_groups", null, ContentValues().apply {
            put("group_id", id); put("owner", state.owner); put("shared_id", state.id)
            put("state", GroupProtocol.format(GroupProtocol.Packet("sync", state)))
            put("hidden", if (existing?.hidden == true) 1 else 0)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        check(stored != -1L) { "Couldn’t save group membership" }
        return id
    }
    internal fun notify(context: Context, id: Long) {
        for (action in listOf("com.relay.app.NEW_MESSAGE", "com.relay.app.NEW_GROUP_MESSAGE"))
            context.sendBroadcast(Intent(action).setPackage(context.packageName).putExtra("group_id", id))
    }
    private fun queue(context: Context, contact: Contact, packet: GroupProtocol.Packet) {
        check(GroupProtocol.eligible(contact)) { "Pair with this friend again before adding them" }
        val payload = Outgoing.enqueue(context, contact, GroupProtocol.format(packet)) ?: error("Couldn't queue group update")
        if (packet.kind in listOf("post", "submit")) {
            val db = MessagingDb.get(context)
            val group = find(db, packet.state) ?: error("Group no longer exists")
            db.writableDatabase.execSQL("INSERT INTO group_delivery(group_id,event,payload_id) VALUES(?,?,?)",
                arrayOf(group.localId, packet.event, payload))
        }
    }
    private fun contacts(context: Context, state: GroupProtocol.State): List<Contact> {
        val repo = ContactRepository(MessagingDb.get(context))
        // A blocked/deleted friend must not receive group posts or stall delivery to everyone else.
        return state.members.filter { it.key != state.owner }.mapNotNull {
            repo.findByNostrPubkeySync(it.key)?.takeIf(GroupProtocol::eligible)
        }
    }
    private fun <T> transaction(context: Context, block: (RelayDbHelper) -> T): T {
        val db = MessagingDb.get(context); val sql = db.writableDatabase
        sql.beginTransaction()
        return try { val value = block(db); sql.setTransactionSuccessful(); value } finally { sql.endTransaction(); com.relay.app.messaging.MessagingRuntime.kick() }
    }
    @Synchronized fun create(context: Context, name: String, ids: List<Long>): Long {
        val own = NostrIdentity.publicKeyHex(context)
        val local = ContactRepository(MessagingDb.get(context))
        val friends = ids.distinct().map { local.getByIdSync(it) ?: error("Contact no longer exists") }
        require(friends.isNotEmpty() && friends.size < GroupProtocol.MAX_MEMBERS && friends.all { GroupProtocol.eligible(it) && it.nostrPubkey != own })
        val state = GroupProtocol.State(UUID.randomUUID().toString(), own, name.trim(), 1,
            listOf(GroupProtocol.Member(own, RelayPreferences(context).myName.ifBlank { "Group creator" })) +
                friends.map { GroupProtocol.Member(it.nostrPubkey!!, (it.originalName ?: it.name).take(30)) })
        GroupProtocol.format(GroupProtocol.Packet("sync", state)) // validate before writing
        val id = transaction(context) { db ->
            val id = save(db, state, null)
            friends.forEach { queue(context, it, GroupProtocol.Packet("sync", state)) }; id
        }
        GroupProfiles.syncPending(context)
        notify(context, id); return id
    }
    @Synchronized fun changeMember(context: Context, id: Long, contactId: Long, add: Boolean) {
        val db = MessagingDb.get(context); val group = get(db, id) ?: error("Create a shared group first")
        require(group.state.owner == NostrIdentity.publicKeyHex(context) && !group.hidden)
        val repo = ContactRepository(db)
        val old = group.state
        val members = if (add) {
            val contact = repo.getByIdSync(contactId) ?: error("Contact no longer exists")
            require(GroupProtocol.eligible(contact)) { "Only verified friends can be added" }
            require(old.members.none { it.key == contact.nostrPubkey }) { "Already a member" }
            require(old.members.size < GroupProtocol.MAX_MEMBERS) { "Group is full" }
            old.members + GroupProtocol.Member(contact.nostrPubkey!!, (contact.originalName ?: contact.name).take(30))
        } else old.members.filterNot { GroupProtocol.memberId(it.key) == contactId && it.key != old.owner }
        val next = old.copy(revision = old.revision + 1, members = members)
        GroupProtocol.format(GroupProtocol.Packet("sync", next))
        transaction(context) {
            save(db, next, group)
            // Notify removed members too; blocked/deleted contacts are not contacted.
            (old.members + next.members).distinctBy { it.key }.filter { it.key != old.owner }.forEach { m ->
                repo.findByNostrPubkeySync(m.key)?.takeIf { GroupProtocol.eligible(it) }?.let { queue(context, it, GroupProtocol.Packet("sync", next)) }
            }
        }
        GroupProfiles.syncPending(context)
        notify(context, id)
    }
    @Synchronized fun delete(context: Context, id: Long) {
        val db = MessagingDb.get(context); val stored = get(db, id) ?: return
        val own = NostrIdentity.publicKeyHex(context)
        transaction(context) {
            if (stored.state.owner == own) {
                val closed = stored.state.copy(revision = stored.state.revision + 1, members = stored.state.members.filter { it.key == own })
                val repo = ContactRepository(db)
                stored.state.members.filter { it.key != own }.forEach { m ->
                    repo.findByNostrPubkeySync(m.key)?.takeIf { GroupProtocol.eligible(it) }?.let { queue(context, it, GroupProtocol.Packet("sync", closed)) }
                }
                save(db, closed, stored)
            }
            db.writableDatabase.execSQL("UPDATE shared_groups SET hidden=1 WHERE group_id=?", arrayOf(id))
            db.writableDatabase.delete("group_messages", "group_id=?", arrayOf(id.toString()))
        }
        com.relay.app.messaging.GroupMessageNotifier.cancel(context, id)
        notify(context, id)
    }
    @Synchronized fun send(context: Context, id: Long, text: String, mediaPath: String? = null, event: String = UUID.randomUUID().toString()) {
        val db = MessagingDb.get(context); val stored = get(db, id) ?: error("This is a legacy local group; create a new shared group")
        val own = NostrIdentity.publicKeyHex(context); val state = stored.state
        require(!stored.hidden && state.members.any { it.key == own }) { "You are no longer in this group" }
        val packet = GroupProtocol.Packet(if (own == state.owner) "post" else "submit", state,
            event, own, text, System.currentTimeMillis())
        GroupProtocol.format(packet)
        transaction(context) {
            if (own == state.owner) contacts(context, state).forEach { queue(context, it, packet) }
            else {
                val owner = ContactRepository(db).findByNostrPubkeySync(state.owner) ?: error("Group creator is no longer a friend")
                queue(context, owner, packet)
            }
            store(context, db, stored.localId, packet, own, mediaPath)
        }
        notify(context, id)
    }
    @Synchronized fun purgeOwnHistory(context: Context, id: Long, before: Long) {
        val db = MessagingDb.get(context); val stored = get(db, id) ?: return
        val own = NostrIdentity.publicKeyHex(context)
        if (stored.hidden || stored.state.members.none { it.key == own }) return
        val packet = GroupProtocol.Packet(if (own == stored.state.owner) "purge" else "purge-submit", stored.state,
            UUID.randomUUID().toString(), own, before.toString(), System.currentTimeMillis())
        transaction(context) {
            if (own == stored.state.owner) contacts(context, stored.state).forEach { queue(context, it, packet) }
            else queue(context, ContactRepository(db).findByNostrPubkeySync(stored.state.owner) ?: error("Group creator unavailable"), packet)
        }
    }
    @Synchronized fun deleteMessage(context: Context, id: Long, event: String) {
        val db = MessagingDb.get(context)
        val stored = get(db, id) ?: error("Legacy messages support local deletion only")
        val own = NostrIdentity.publicKeyHex(context)
        require(!stored.hidden && stored.state.members.any { it.key == own })
        val packet = GroupProtocol.Packet(if (own == stored.state.owner) "delete" else "delete-submit", stored.state,
            UUID.randomUUID().toString(), own, event, System.currentTimeMillis())
        transaction(context) {
            if (own == stored.state.owner) contacts(context, stored.state).forEach { queue(context, it, packet) }
            else queue(context, ContactRepository(db).findByNostrPubkeySync(stored.state.owner) ?: error("Group creator unavailable"), packet)
            com.relay.app.messaging.MessageDeletion.applyGroup(context, id, own, event)
        }
        notify(context, id)
    }

    private fun store(context: Context, db: RelayDbHelper, id: Long, packet: GroupProtocol.Packet, own: String, mediaPath: String? = null): Boolean {
        if (com.relay.app.messaging.MessageDeletion.isDeleted(db, "group:$id", packet.author, packet.event, packet.time)) return false
        val fresh = db.writableDatabase.insertWithOnConflict("shared_group_seen", null, ContentValues().apply {
            put("group_id", id); put("event", packet.event)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (!fresh) return false
        if (packet.kind in listOf("purge", "purge-submit")) {
            com.relay.app.messaging.MessageDeletion.purge(context, "group:$id", packet.author, packet.text.toLong())
            return false
        }
        if (packet.kind in listOf("delete", "delete-submit")) {
            com.relay.app.messaging.MessageDeletion.applyGroup(context, id, packet.author, packet.text)
            return false
        }
        val staged = db.readableDatabase.rawQuery("SELECT _id FROM group_messages WHERE group_id=? AND event_id=? AND is_sent=1",
            arrayOf(id.toString(), packet.event)).use { it.moveToFirst() }
        val event = com.relay.app.messaging.ConversationEventBody.parse(packet.text)
        if (packet.text.startsWith(com.relay.app.messaging.ConversationEventBody.PREFIX) && event == null) return false
        if (event?.kind == "reaction") {
            com.relay.app.messaging.ConversationEvents.apply(context, "group:$id", packet.author, event)
            return false
        }
        val message = if (staged && packet.author == own) 1L else GroupMessageRepository(db).insertMessageSync(GroupMessage(groupId = id, body = com.relay.app.media.MediaBody.parse(packet.text)?.caption ?: packet.text,
            eventId = packet.event, mediaUri = mediaPath,
            type = if(event?.kind == "screenshot") com.relay.app.data.model.MessageType.SCREENSHOT else com.relay.app.media.MediaBody.parse(packet.text)?.let { if (it.isVideo) com.relay.app.data.model.MessageType.VIDEO else com.relay.app.data.model.MessageType.IMAGE } ?: com.relay.app.data.model.MessageType.TEXT,
            senderKey = packet.author, senderName = packet.state.members.first { it.key == packet.author }.name,
            timestamp = com.relay.app.messaging.TimestampPolicy.resolve(packet.time, System.currentTimeMillis()),
            isSent = packet.author == own, unread = packet.author != own))
        check(message != -1L) { "Couldn’t save group message" }
        if (mediaPath == null) com.relay.app.media.MediaBody.parse(packet.text)?.let { ref ->
            db.writableDatabase.execSQL("UPDATE group_messages SET media_ref=? WHERE group_id=? AND event_id=?", arrayOf(packet.text, id, packet.event))
            com.relay.app.messaging.MessagingRuntime.launchIo {
                GroupMedia.download(context, id, packet.event, ref)
            }
        }
        return true
    }
    @Synchronized fun handle(context: Context, sender: Contact, body: String): Boolean {
        if (!GroupProtocol.isControl(body)) return false
        if (!GroupProtocol.eligible(sender)) return true
        val packet = GroupProtocol.parse(body) ?: return true
        val db = MessagingDb.get(context); val own = NostrIdentity.publicKeyHex(context)
        val existing = find(db, packet.state)
        if (existing?.hidden == true) return true
        if (packet.kind in listOf("profile", "profile-submit")) {
            GroupProfiles.handle(context, sender.nostrPubkey!!, existing, packet)
            return true
        }
        if (packet.kind in listOf("submit", "delete-submit", "purge-submit")) {
            if (existing == null || existing.state.owner != own ||
                !GroupProtocol.acceptSubmit(sender.nostrPubkey!!, existing.state, packet)) return true
            var received = false
            transaction(context) {
                // Every accepted submission is stored and fanned out atomically with durable outbox writes.
                val duplicate = db.readableDatabase.rawQuery("SELECT 1 FROM shared_group_seen WHERE group_id=? AND event=?", arrayOf(existing.localId.toString(), packet.event)).use { it.moveToFirst() }
                if (!duplicate) {
                    contacts(context, existing.state).forEach { queue(context, it, packet.copy(kind = when (packet.kind) { "delete-submit" -> "delete"; "purge-submit" -> "purge"; else -> "post" }, state = existing.state)) }
                    received = store(context, db, existing.localId, packet.copy(state = existing.state), own)
                }
            }
            notify(context, existing.localId)
            if (received && com.relay.app.messaging.ConversationEventBody.parse(packet.text) == null) com.relay.app.messaging.GroupMessageNotifier.show(context, existing.localId, packet.copy(state = existing.state))
        } else {
            if (!GroupProtocol.acceptOwner(sender.nostrPubkey!!, existing?.state, packet)) return true
            val effective = if (existing != null && existing.state.revision > packet.state.revision) existing.state else packet.state
            val member = effective.members.any { it.key == own }
            if (!member && existing == null) return true
            var received = false
            val id = transaction(context) {
                val id = save(db, effective, existing)
                if (member && packet.kind in listOf("post", "delete", "purge")) received = store(context, db, id, packet, own)
                id
            }
            if(member && packet.kind == "sync") GroupAvatars.request(context,id,effective)
            notify(context, id)
            if (received && packet.author != own && com.relay.app.messaging.ConversationEventBody.parse(packet.text) == null) com.relay.app.messaging.GroupMessageNotifier.show(context, id, packet)
        }
        return true
    }
}
