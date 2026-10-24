package com.relay.app.groups

import android.content.Context
import android.graphics.BitmapFactory
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.Outgoing
import kotlinx.coroutines.flow.MutableStateFlow

/** JPEG blobs are inside SQLCipher and private encrypted messages. Only a group's creator writes. */
object GroupAvatars {
    const val CREATE = "CREATE TABLE group_avatars (group_id INTEGER PRIMARY KEY REFERENCES groups(_id) ON DELETE CASCADE, revision INTEGER NOT NULL, jpeg BLOB)"
    val changes = MutableStateFlow(0L)
    private const val REQUEST = "TYPE:PROFILE_PHOTO|KIND:GROUP_AVATAR_REQUEST|"
    private val requests = com.relay.app.messaging.CooldownLimiter(30_000L)
    private val outgoingRequests = com.relay.app.messaging.CooldownLimiter(30_000L)
    fun request(context: Context, id: Long, state: GroupProtocol.State) {
        if(state.owner == com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context)) return
        val owner = ContactRepository(MessagingDb.get(context)).findByNostrPubkeySync(state.owner)?.takeIf(GroupProtocol::eligible) ?: return
        if(outgoingRequests.allow(id,System.currentTimeMillis())) Outgoing.enqueue(context,owner,REQUEST+state.id)
    }

    fun load(context: Context, id: Long): ByteArray? = MessagingDb.get(context).readableDatabase.rawQuery(
        "SELECT jpeg FROM group_avatars WHERE group_id=?", arrayOf(id.toString())).use { if (it.moveToFirst() && !it.isNull(0)) it.getBlob(0) else null }
    private fun revision(context: Context, id: Long) = MessagingDb.get(context).readableDatabase.rawQuery(
        "SELECT revision FROM group_avatars WHERE group_id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getLong(0) else -1L }
    /** Revision (-1 = never set) and bytes (null = none or removed) for the roster sync. */
    internal fun snapshot(context: Context, id: Long): Pair<Long, ByteArray?> = revision(context, id) to load(context, id)
    private fun valid(jpeg: ByteArray?): Boolean {
        if (jpeg == null) return true
        if (jpeg.size > com.relay.app.media.ProfilePhotoBody.MAX_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return bounds.outWidth in 1..256 && bounds.outHeight in 1..256
    }
    private fun save(context: Context, id: Long, update: GroupAvatarBody.Update) {
        val db = MessagingDb.get(context).writableDatabase
        db.execSQL("INSERT INTO group_avatars(group_id,revision,jpeg) VALUES(?,?,?) ON CONFLICT(group_id) DO UPDATE SET revision=excluded.revision,jpeg=excluded.jpeg WHERE excluded.revision > group_avatars.revision",
            arrayOf(id, update.revision, update.jpeg))
    }
    fun set(context: Context, id: Long, jpeg: ByteArray?) = synchronized(SharedGroups) {
        require(valid(jpeg)) { "Couldn't use that group photo" }
        val db = MessagingDb.get(context); val group = SharedGroups.get(db, id) ?: error("Create a shared group first")
        check(!group.hidden && group.state.owner == com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context)) { "Only the creator can change the group photo" }
        val update = GroupAvatarBody.Update(group.state.id, revision(context, id) + 1, jpeg)
        val sql = db.writableDatabase; sql.beginTransaction()
        try {
            save(context, id, update)
            sync(context, id, group.state, force = true)
            com.relay.app.sync.SentSync.stageGroupRoster(context, id)
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        changes.value += 1; SharedGroups.notify(context, id)
        com.relay.app.messaging.MessagingRuntime.kick()
    }
    fun sync(context: Context, id: Long, state: GroupProtocol.State, force: Boolean = false) {
        if (state.owner != com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context)) return
        val revision = revision(context, id); if (revision < 0) return
        val body = GroupAvatarBody.format(GroupAvatarBody.Update(state.id, revision, load(context, id)))
        val markers = context.getSharedPreferences("group_avatar_queue", Context.MODE_PRIVATE)
        for (member in state.members.filter { it.key != state.owner }) {
            val contact = ContactRepository(MessagingDb.get(context)).findByNostrPubkeySync(member.key)?.takeIf(GroupProtocol::eligible) ?: continue
            val marker = "$id:${member.key}:${state.revision}"
            if (!force && markers.getLong(marker, -1) >= revision) continue
            check(Outgoing.enqueue(context, contact, body) != null) { "Couldn't queue group picture; try again" }
            // A duplicate after a crash is safe: the recipient's revision guard is authoritative.
            if (!force) check(markers.edit().putLong(marker, revision).commit())
        }
    }
    fun handle(context: Context, sender: Contact, body: String): Boolean {
        if(body.startsWith(REQUEST)) {
            synchronized(SharedGroups) {
                if(!GroupProtocol.eligible(sender)) return true
                val sharedId=body.removePrefix(REQUEST)
                if(runCatching { java.util.UUID.fromString(sharedId).toString() == sharedId }.getOrDefault(false).not()) return true
                val own=com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context)
                val id=MessagingDb.get(context).readableDatabase.rawQuery("SELECT group_id FROM shared_groups WHERE owner=? AND shared_id=? AND hidden=0",arrayOf(own,sharedId))
                    .use { if(it.moveToFirst()) it.getLong(0) else null } ?: return true
                val group=SharedGroups.get(MessagingDb.get(context),id) ?: return true
                if(group.state.members.none { it.key == sender.nostrPubkey }) return true
                val key=id xor java.lang.Long.rotateLeft(sender.id,23)
                val revision=revision(context,id)
                if(revision >= 0 && requests.allow(key,System.currentTimeMillis()))
                    Outgoing.enqueue(context,sender,GroupAvatarBody.format(GroupAvatarBody.Update(sharedId,revision,load(context,id))))
                return true
            }
        }
        if (!body.startsWith(GroupAvatarBody.PREFIX)) return false
        if (!GroupProtocol.eligible(sender)) return true
        val update = GroupAvatarBody.parse(body) ?: return true
        if (!valid(update.jpeg)) return true
        synchronized(SharedGroups) {
            val db = MessagingDb.get(context)
            val id = db.readableDatabase.rawQuery("SELECT group_id FROM shared_groups WHERE owner=? AND shared_id=? AND hidden=0",
                arrayOf(sender.nostrPubkey!!, update.group)).use { if (it.moveToFirst()) it.getLong(0) else null } ?: return true
            val stored = SharedGroups.get(db, id) ?: return true
            if (stored.state.members.none { it.key == com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context) }) return true
            if (update.revision <= revision(context, id)) return true
            save(context, id, update)
            com.relay.app.sync.SentSync.stageGroupRoster(context, id)
            changes.value += 1; SharedGroups.notify(context, id)
        }
        return true
    }
}
