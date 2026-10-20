package com.relay.app.groups

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.Group
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.Outgoing
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.RelayPreferences
import java.util.UUID

/** Private groups with a creator-controlled roster. Posts, deletions, purges and name updates travel straight
 * from their author to every member, so nobody waits for the creator's phone; only roster changes come from
 * the creator. Membership is never promoted to a verified contact on another phone. */
object SharedGroups {
    const val CREATE = """CREATE TABLE shared_groups (
        group_id INTEGER PRIMARY KEY REFERENCES groups(_id) ON DELETE CASCADE,
        owner TEXT NOT NULL, shared_id TEXT NOT NULL, state TEXT NOT NULL,
        hidden INTEGER NOT NULL DEFAULT 0, UNIQUE(owner, shared_id))"""
    const val CREATE_SEEN = """CREATE TABLE shared_group_seen (
        group_id INTEGER NOT NULL REFERENCES groups(_id) ON DELETE CASCADE,
        event TEXT NOT NULL, PRIMARY KEY(group_id, event))"""
    /** Events naming a roster we haven't received yet (see [GroupProtocol.checkEvent]); keyed by the group's
     * identity because the group may not exist on this phone yet. */
    const val CREATE_PENDING = """CREATE TABLE shared_group_pending (
        owner TEXT NOT NULL, shared_id TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL,
        received_at INTEGER NOT NULL)"""
    const val PENDING_EVICT_GROUP = "DELETE FROM shared_group_pending WHERE rowid IN (SELECT rowid FROM shared_group_pending WHERE owner=? AND shared_id=? ORDER BY received_at DESC, rowid DESC LIMIT -1 OFFSET ?)"
    const val PENDING_EVICT_ALL = "DELETE FROM shared_group_pending WHERE received_at < ? OR rowid IN (SELECT rowid FROM shared_group_pending ORDER BY received_at DESC, rowid DESC LIMIT -1 OFFSET ?)"
    private const val PENDING_PER_GROUP = 100
    private const val PENDING_TOTAL = 500
    private const val PENDING_TTL_MS = 7L * 24 * 60 * 60 * 1000
    data class Stored(val localId: Long, val state: GroupProtocol.State, val hidden: Boolean)
    /** How to reach one member directly: our own verified record when we have one, else the creator's roster copy. */
    internal data class Recipient(val key: String, val contactId: Long?, val inner: String?, val relays: List<String>)

    fun get(db: RelayDbHelper, id: Long): Stored? = db.readableDatabase.rawQuery(
        "SELECT group_id,state,hidden FROM shared_groups WHERE group_id=?", arrayOf(id.toString()),
    ).use { if (it.moveToFirst()) (GroupProtocol.parse(it.getString(1)) as? GroupProtocol.Sync)?.let { p -> Stored(it.getLong(0), p.state, it.getInt(2) != 0) } else null }
    private fun find(db: RelayDbHelper, owner: String, id: String): Stored? = db.readableDatabase.rawQuery(
        "SELECT group_id FROM shared_groups WHERE owner=? AND shared_id=?", arrayOf(owner, id),
    ).use { if (it.moveToFirst()) get(db, it.getLong(0)) else null }
    private fun visible(db: RelayDbHelper, where: String = "hidden=0", args: Array<String> = emptyArray()): List<Stored> =
        db.readableDatabase.rawQuery("SELECT group_id FROM shared_groups WHERE $where", args)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.mapNotNull { get(db, it) }
    /** Whether [key] is on the roster of a group this phone shows; such a sender is a group member, not a stranger. */
    fun knows(db: RelayDbHelper, key: String): Boolean = visible(db).any { s -> s.state.members.any { it.key == key } }
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
            put("state", GroupProtocol.format(GroupProtocol.Sync(state)))
            put("hidden", if (existing?.hidden == true) 1 else 0)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        check(stored != -1L) { "Couldn’t save group membership" }
        return id
    }
    internal fun notify(context: Context, id: Long) {
        for (action in listOf("com.relay.app.NEW_MESSAGE", "com.relay.app.NEW_GROUP_MESSAGE"))
            context.sendBroadcast(Intent(action).setPackage(context.packageName).putExtra("group_id", id))
    }
    private fun member(contact: Contact, name: String = (contact.originalName ?: contact.name).take(30).trim()) =
        GroupProtocol.Member(contact.nostrPubkey!!, name, contact.publicKey, GroupProtocol.relayHints(contact.relayHints))
    private fun friend(contact: Contact) = Recipient(contact.nostrPubkey!!, contact.id, contact.publicKey, contact.relayHints)
    /** Everyone else on the roster. Anyone you blocked is skipped. */
    internal fun recipients(db: RelayDbHelper, state: GroupProtocol.State, own: String): List<Recipient> {
        val repo = ContactRepository(db)
        return state.members.filter { it.key != own }.mapNotNull { m ->
            val contact = repo.findByNostrPubkeySync(m.key)
            when {
                contact?.trustLevel == ContactTrustLevel.BLOCKED -> null
                contact != null && GroupProtocol.eligible(contact) -> friend(contact)
                else -> Recipient(m.key, contact?.id, m.inner, m.relays)
            }
        }
    }
    /** Roster updates go only to members who are still the creator's verified friends. */
    private fun rosterRecipients(db: RelayDbHelper, members: List<GroupProtocol.Member>, own: String): List<Recipient> {
        val repo = ContactRepository(db)
        return members.filter { it.key != own }.mapNotNull { repo.findByNostrPubkeySync(it.key)?.takeIf(GroupProtocol::eligible)?.let(::friend) }
    }
    internal fun queue(context: Context, db: RelayDbHelper, to: Recipient, group: Long, packet: GroupProtocol.Packet) {
        GroupDispatch.stage(context, db, to, group, packet)
    }
    private fun fanOut(context: Context, db: RelayDbHelper, stored: Stored, packet: GroupProtocol.Event, own: String) =
        recipients(db, stored.state, own).forEach { queue(context, db, it, stored.localId, packet) }
    private fun event(state: GroupProtocol.State, kind: String, own: String, text: String, event: String = UUID.randomUUID().toString()) =
        GroupProtocol.Event(kind, GroupProtocol.ref(state), event, own, text, System.currentTimeMillis()).also { GroupProtocol.format(it) }
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
        val state = GroupProtocol.fitted(GroupProtocol.State(UUID.randomUUID().toString(), own, name.trim(), 1,
            listOf(GroupProtocol.Member(own, RelayPreferences(context).myName.ifBlank { "Group creator" })) + friends.map { member(it) }))
        GroupProtocol.format(GroupProtocol.Sync(state)) // validate before writing
        val id = transaction(context) { db ->
            val id = save(db, state, null)
            friends.forEach { queue(context, db, friend(it), id, GroupProtocol.Sync(state)) }; id
        }
        com.relay.app.messaging.MessagingRuntime.launchIo { GroupProfiles.syncPending(context) }
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
            old.members + member(contact)
        } else old.members.filterNot { GroupProtocol.memberId(it.key) == contactId && it.key != old.owner }
        val next = GroupProtocol.fitted(old.copy(revision = old.revision + 1, members = members))
        GroupProtocol.format(GroupProtocol.Sync(next))
        transaction(context) {
            save(db, next, group)
            // Notify removed members too; blocked/deleted contacts are not contacted.
            rosterRecipients(db, (old.members + next.members).distinctBy { it.key }, old.owner).forEach { queue(context, db, it, id, GroupProtocol.Sync(next)) }
        }
        GroupProfiles.syncPending(context)
        notify(context, id)
    }
    /** The creator re-issues its roster when a member's verified key or relay hints change (or a roster saved
     * before direct delivery lacks them), so members can keep reaching each other without the creator. */
    @Synchronized fun refreshOwnRosters(context: Context) {
        val db = MessagingDb.get(context); val own = NostrIdentity.publicKeyHex(context); val repo = ContactRepository(db)
        for (stored in visible(db, "owner=? AND hidden=0", arrayOf(own))) {
            val old = stored.state
            val next = GroupProtocol.fitted(old.copy(revision = old.revision + 1, members = old.members.map { m ->
                repo.findByNostrPubkeySync(m.key)?.takeIf { m.key != own && GroupProtocol.eligible(it) }?.let { member(it, m.name) } ?: m
            }))
            if (next.members == old.members) continue
            transaction(context) {
                save(db, next, stored)
                rosterRecipients(db, next.members, own).forEach { queue(context, db, it, stored.localId, GroupProtocol.Sync(next)) }
            }
            notify(context, stored.localId)
        }
    }
    @Synchronized fun delete(context: Context, id: Long) {
        val db = MessagingDb.get(context); val stored = get(db, id) ?: return
        val own = NostrIdentity.publicKeyHex(context)
        transaction(context) {
            if (stored.state.owner == own) {
                val closed = stored.state.copy(revision = stored.state.revision + 1, members = stored.state.members.filter { it.key == own })
                rosterRecipients(db, stored.state.members, own).forEach { queue(context, db, it, id, GroupProtocol.Sync(closed)) }
                save(db, closed, stored)
            }
            db.writableDatabase.execSQL("UPDATE shared_groups SET hidden=1 WHERE group_id=?", arrayOf(id))
            db.writableDatabase.delete("group_messages", "group_id=?", arrayOf(id.toString()))
        }
        com.relay.app.messaging.GroupMessageNotifier.cancel(context, id)
        notify(context, id)
    }
    @Synchronized fun send(context: Context, id: Long, text: String, mediaPath: String? = null, event: String = UUID.randomUUID().toString()): GroupMessage? {
        val db = MessagingDb.get(context); val stored = get(db, id) ?: error("This is a legacy local group; create a new shared group")
        val own = NostrIdentity.publicKeyHex(context)
        require(!stored.hidden && stored.state.members.any { it.key == own }) { "You are no longer in this group" }
        val packet = event(stored.state, "post", own, text, event)
        val accepted = transaction(context) {
            fanOut(context, db, stored, packet, own)
            store(context, db, stored.localId, stored.state, packet, own, mediaPath)
            GroupMessageRepository(db).getByEventSync(id, event, own)
        }
        notify(context, id)
        return accepted
    }
    @Synchronized fun purgeOwnHistory(context: Context, id: Long, before: Long) {
        val db = MessagingDb.get(context); val stored = get(db, id) ?: return
        val own = NostrIdentity.publicKeyHex(context)
        if (stored.hidden || stored.state.members.none { it.key == own }) return
        val packet = event(stored.state, "purge", own, before.toString())
        transaction(context) { fanOut(context, db, stored, packet, own) }
    }
    @Synchronized fun deleteMessage(context: Context, id: Long, event: String) {
        val db = MessagingDb.get(context)
        val stored = get(db, id) ?: error("Legacy messages support local deletion only")
        val own = NostrIdentity.publicKeyHex(context)
        require(!stored.hidden && stored.state.members.any { it.key == own })
        val packet = event(stored.state, "delete", own, event)
        transaction(context) {
            fanOut(context, db, stored, packet, own)
            com.relay.app.messaging.MessageDeletion.applyGroup(context, id, own, event)
        }
        notify(context, id)
    }

    private fun store(context: Context, db: RelayDbHelper, id: Long, state: GroupProtocol.State, e: GroupProtocol.Event, own: String, mediaPath: String? = null): Boolean {
        if (com.relay.app.messaging.MessageDeletion.isDeleted(db, "group:$id", e.author, e.event, e.time)) return false
        if (e.kind == "post" && !com.relay.app.messaging.RetentionPolicy.admissible(e.time, System.currentTimeMillis())) return false
        // Keyed by author as well: events come from every member now, and one member must not be able to
        // suppress another's message by reusing its id.
        val fresh = db.writableDatabase.insertWithOnConflict("shared_group_seen", null, ContentValues().apply {
            put("group_id", id); put("event", "${e.author}:${e.event}")
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (!fresh) return false
        if (e.kind == "purge") {
            com.relay.app.messaging.MessageDeletion.purge(context, "group:$id", e.author, e.text.toLong())
            return false
        }
        if (e.kind == "delete") {
            com.relay.app.messaging.MessageDeletion.applyGroup(context, id, e.author, e.text)
            return false
        }
        val staged = db.readableDatabase.rawQuery("SELECT _id FROM group_messages WHERE group_id=? AND event_id=? AND is_sent=1",
            arrayOf(id.toString(), e.event)).use { it.moveToFirst() }
        val event = com.relay.app.messaging.ConversationEventBody.parse(e.text)
        if (e.text.startsWith(com.relay.app.messaging.ConversationEventBody.PREFIX) && event == null) return false
        if (event?.kind == "reaction") {
            com.relay.app.messaging.ConversationEvents.apply(context, "group:$id", e.author, event)
            return false
        }
        val message = if (staged && e.author == own) 1L else GroupMessageRepository(db).insertMessageSync(GroupMessage(groupId = id, body = com.relay.app.media.MediaBody.parse(e.text)?.caption ?: e.text,
            eventId = e.event, mediaUri = mediaPath,
            type = if(event?.kind == "screenshot" || event?.kind == "media_saved") com.relay.app.data.model.MessageType.SCREENSHOT else com.relay.app.media.MediaBody.parse(e.text)?.let { if (it.isVideo) com.relay.app.data.model.MessageType.VIDEO else com.relay.app.data.model.MessageType.IMAGE } ?: com.relay.app.data.model.MessageType.TEXT,
            senderKey = e.author, senderName = state.members.first { it.key == e.author }.name,
            timestamp = com.relay.app.messaging.TimestampPolicy.resolve(e.time, System.currentTimeMillis()),
            isSent = e.author == own, unread = e.author != own))
        check(message != -1L) { "Couldn’t save group message" }
        if (mediaPath == null) com.relay.app.media.MediaBody.parse(e.text)?.let { ref ->
            db.writableDatabase.execSQL("UPDATE group_messages SET media_ref=? WHERE group_id=? AND event_id=?", arrayOf(e.text, id, e.event))
            com.relay.app.messaging.MessagingRuntime.launchIo {
                GroupMedia.download(context, id, e.event, ref)
            }
        }
        return true
    }
    /** Group control packets from [sender]. [contact] is their contact row, if any: a roster update must come
     * from the creator as a verified friend, while other events are vouched for by our copy of the roster. */
    @Synchronized fun handle(context: Context, sender: String, contact: Contact?, body: String): Boolean {
        if (!GroupProtocol.isControl(body)) return false
        if (contact?.trustLevel == ContactTrustLevel.BLOCKED) return true
        when (val packet = GroupProtocol.parse(body)) {
            is GroupProtocol.Sync -> if (contact != null && GroupProtocol.eligible(contact)) handleSync(context, sender, packet.state)
            is GroupProtocol.Event -> handleEvent(context, sender, packet, body)
            null -> Unit
        }
        return true
    }
    private fun handleSync(context: Context, sender: String, incoming: GroupProtocol.State) {
        val db = MessagingDb.get(context); val own = NostrIdentity.publicKeyHex(context)
        val existing = find(db, incoming.owner, incoming.id)
        if (existing?.hidden == true || !GroupProtocol.acceptSync(sender, existing?.state, incoming)) return
        // An invitation that doesn't list us is someone else's group.
        if (existing == null && incoming.members.none { it.key == own }) return
        val id = transaction(context) { save(db, incoming, existing) }
        if (incoming.members.any { it.key == own }) GroupAvatars.request(context, id, incoming)
        notify(context, id)
        replayPending(context, db, incoming)
        // New members learn everyone's current name from the members themselves.
        if (incoming != existing?.state) GroupProfiles.syncPending(context)
    }
    private fun handleEvent(context: Context, sender: String, e: GroupProtocol.Event, body: String) {
        val db = MessagingDb.get(context); val own = NostrIdentity.publicKeyHex(context)
        val existing = find(db, e.group.owner, e.group.id)
        if (existing?.hidden == true) return
        when (GroupProtocol.checkEvent(sender, existing?.state, e)) {
            GroupProtocol.Verdict.REJECT -> return
            GroupProtocol.Verdict.LATER -> { hold(db, sender, e, body, existing == null); return }
            GroupProtocol.Verdict.ACCEPT -> Unit
        }
        val stored = existing ?: return
        // Removed members keep their history but take nothing new.
        if (stored.state.members.none { it.key == own }) return
        if (e.kind == "profile") { GroupProfiles.apply(context, stored, e); return }
        if (e.kind == "read") { GroupReadReceipts.apply(context, stored, e); return }
        var received = false
        transaction(context) { received = store(context, db, stored.localId, stored.state, e, own) }
        notify(context, stored.localId)
        if (received && e.author != own && com.relay.app.messaging.ConversationEventBody.parse(e.text) == null)
            com.relay.app.messaging.GroupMessageNotifier.show(context, stored.localId, stored.state, e)
    }
    private fun hold(db: RelayDbHelper, sender: String, e: GroupProtocol.Event, body: String, unknownGroup: Boolean) {
        // Anyone who knows a group id could otherwise fill this table: a group we don't have yet is only
        // plausible as a pending invitation from a verified friend, and everything held is bounded and expires.
        if (unknownGroup && ContactRepository(db).findByNostrPubkeySync(e.group.owner)?.let(GroupProtocol::eligible) != true) return
        val sql = db.writableDatabase; val now = System.currentTimeMillis()
        sql.execSQL(PENDING_EVICT_GROUP, arrayOf(e.group.owner, e.group.id, PENDING_PER_GROUP - 1))
        sql.execSQL(PENDING_EVICT_ALL, arrayOf(now - PENDING_TTL_MS, PENDING_TOTAL - 1))
        sql.insert("shared_group_pending", null, ContentValues().apply {
            put("owner", e.group.owner); put("shared_id", e.group.id); put("sender", sender); put("body", body); put("received_at", now)
        })
    }
    private fun replayPending(context: Context, db: RelayDbHelper, state: GroupProtocol.State) {
        val held = db.readableDatabase.rawQuery("SELECT rowid,sender,body FROM shared_group_pending WHERE owner=? AND shared_id=? ORDER BY received_at, rowid",
            arrayOf(state.owner, state.id)).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) } }
        for ((row, sender, body) in held) transaction(context) {
            db.writableDatabase.execSQL("DELETE FROM shared_group_pending WHERE rowid=?", arrayOf(row))
            // Still too new for this roster: it is held again by handleEvent.
            (GroupProtocol.parse(body) as? GroupProtocol.Event)?.let { handleEvent(context, sender, it, body) }
        }
    }
}
