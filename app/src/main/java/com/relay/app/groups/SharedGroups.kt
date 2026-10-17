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
            Contact(id = GroupProtocol.memberId(it.key), name = it.name, phone = Contact.placeholderPhoneFor(it.key), nostrPubkey = it.key)
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
    private fun notify(context: Context, id: Long) {
        for (action in listOf("com.relay.app.NEW_MESSAGE", "com.relay.app.NEW_GROUP_MESSAGE"))
            context.sendBroadcast(Intent(action).setPackage(context.packageName).putExtra("group_id", id))
    }
    private fun queue(context: Context, contact: Contact, packet: GroupProtocol.Packet) {
        check(GroupProtocol.eligible(contact)) { "Pair with this friend again before adding them" }
        check(Outgoing.enqueue(context, contact, GroupProtocol.format(packet)) != null) { "Couldn't queue group update" }
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
                friends.map { GroupProtocol.Member(it.nostrPubkey!!, it.name.take(30)) })
        GroupProtocol.format(GroupProtocol.Packet("sync", state)) // validate before writing
        val id = transaction(context) { db ->
            val id = save(db, state, null)
            friends.forEach { queue(context, it, GroupProtocol.Packet("sync", state)) }; id
        }
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
            old.members + GroupProtocol.Member(contact.nostrPubkey!!, contact.name.take(30))
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
    @Synchronized fun send(context: Context, id: Long, text: String) {
        val db = MessagingDb.get(context); val stored = get(db, id) ?: error("This is a legacy local group; create a new shared group")
        val own = NostrIdentity.publicKeyHex(context); val state = stored.state
        require(!stored.hidden && state.members.any { it.key == own }) { "You are no longer in this group" }
        val packet = GroupProtocol.Packet(if (own == state.owner) "post" else "submit", state,
            UUID.randomUUID().toString(), own, text, System.currentTimeMillis())
        GroupProtocol.format(packet)
        transaction(context) {
            if (own == state.owner) contacts(context, state).forEach { queue(context, it, packet) }
            else {
                val owner = ContactRepository(db).findByNostrPubkeySync(state.owner) ?: error("Group creator is no longer a friend")
                queue(context, owner, packet)
            }
            store(db, stored.localId, packet, own)
        }
        notify(context, id)
    }
    private fun store(db: RelayDbHelper, id: Long, packet: GroupProtocol.Packet, own: String): Boolean {
        val fresh = db.writableDatabase.insertWithOnConflict("shared_group_seen", null, ContentValues().apply {
            put("group_id", id); put("event", packet.event)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (!fresh) return false
        val message = GroupMessageRepository(db).insertMessageSync(GroupMessage(groupId = id, body = packet.text,
            senderKey = packet.author, senderName = packet.state.members.first { it.key == packet.author }.name,
            timestamp = com.relay.app.messaging.TimestampPolicy.resolve(packet.time, System.currentTimeMillis()),
            isSent = packet.author == own, unread = packet.author != own))
        check(message != -1L) { "Couldn’t save group message" }
        return true
    }
    @Synchronized fun handle(context: Context, sender: Contact, body: String): Boolean {
        if (!GroupProtocol.isControl(body)) return false
        if (!GroupProtocol.eligible(sender)) return true
        val packet = GroupProtocol.parse(body) ?: return true
        val db = MessagingDb.get(context); val own = NostrIdentity.publicKeyHex(context)
        val existing = find(db, packet.state)
        if (existing?.hidden == true) return true
        if (packet.kind == "submit") {
            if (existing == null || existing.state.owner != own ||
                !GroupProtocol.acceptSubmit(sender.nostrPubkey!!, existing.state, packet)) return true
            var received = false
            transaction(context) {
                // Every accepted submission is stored and fanned out atomically with durable outbox writes.
                val duplicate = db.readableDatabase.rawQuery("SELECT 1 FROM shared_group_seen WHERE group_id=? AND event=?", arrayOf(existing.localId.toString(), packet.event)).use { it.moveToFirst() }
                if (!duplicate) {
                    contacts(context, existing.state).forEach { queue(context, it, packet.copy(kind = "post", state = existing.state)) }
                    received = store(db, existing.localId, packet.copy(state = existing.state), own)
                }
            }
            notify(context, existing.localId)
            if (received) com.relay.app.messaging.GroupMessageNotifier.show(context, existing.localId, packet.copy(state = existing.state))
        } else {
            if (!GroupProtocol.acceptOwner(sender.nostrPubkey!!, existing?.state, packet)) return true
            val effective = if (existing != null && existing.state.revision > packet.state.revision) existing.state else packet.state
            val member = effective.members.any { it.key == own }
            if (!member && existing == null) return true
            var received = false
            val id = transaction(context) {
                val id = save(db, effective, existing)
                if (member && packet.kind == "post") received = store(db, id, packet, own)
                id
            }
            notify(context, id)
            if (received && packet.author != own) com.relay.app.messaging.GroupMessageNotifier.show(context, id, packet)
        }
        return true
    }
}
