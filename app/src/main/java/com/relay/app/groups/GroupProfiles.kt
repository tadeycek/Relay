package com.relay.app.groups

import android.content.Context
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.repository.ContactRepository
import com.relay.app.media.ProfileNameBody
import com.relay.app.messaging.MessagingDb
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.RelayPreferences
import java.util.UUID

/** Profile revisions are separate from owner-authorized membership revisions. Each member announces their
 * own name directly to the other members, who accept it only for current roster members. Never sends aliases. */
object GroupProfiles {
    const val CREATE = """CREATE TABLE group_profiles (
        group_id INTEGER NOT NULL REFERENCES groups(_id) ON DELETE CASCADE,
        member_key TEXT NOT NULL, revision INTEGER NOT NULL, name TEXT NOT NULL,
        PRIMARY KEY(group_id, member_key))"""
    fun latest(db: RelayDbHelper, group: Long, key: String): ProfileNameBody.Update? = db.readableDatabase.rawQuery(
        "SELECT revision,name FROM group_profiles WHERE group_id=? AND member_key=?", arrayOf(group.toString(), key)
    ).use { if (it.moveToFirst()) ProfileNameBody.Update(it.getLong(0), it.getString(1)) else null }

    fun display(db: RelayDbHelper, group: Long, key: String, fallback: String): String {
        val contact = ContactRepository(db).findByNostrPubkeySync(key)
        // An explicitly local alias always wins on this device.
        if (contact != null && contact.name != contact.originalName) return contact.name
        return latest(db, group, key)?.name ?: contact?.name ?: fallback
    }
    private fun save(db: RelayDbHelper, group: Long, key: String, update: ProfileNameBody.Update): Boolean {
        if ((latest(db, group, key)?.revision ?: -1) >= update.revision) return false
        db.writableDatabase.execSQL("""INSERT INTO group_profiles(group_id,member_key,revision,name) VALUES(?,?,?,?)
            ON CONFLICT(group_id,member_key) DO UPDATE SET revision=excluded.revision,name=excluded.name
            WHERE excluded.revision > group_profiles.revision""", arrayOf(group, key, update.revision, update.name))
        return true
    }
    /** A name update [SharedGroups] already checked: sent by its author, who is on our roster. */
    internal fun apply(context: Context, stored: SharedGroups.Stored, e: GroupProtocol.Event) {
        val update = ProfileNameBody.parse(e.text) ?: return
        if (save(MessagingDb.get(context), stored.localId, e.author, update)) SharedGroups.notify(context, stored.localId)
    }

    /** Invoked on profile changes, membership changes and reconnect. Durable queue markers avoid
     * repeated fanout; roster revision makes a newly added recipient receive current names. */
    fun syncPending(context: Context) {
        synchronized(SharedGroups) {
            val db = MessagingDb.get(context)
            val own = NostrIdentity.publicKeyHex(context)
            val self = RelayPreferences(context)
            val repo = ContactRepository(db)
            val ids = db.readableDatabase.rawQuery("SELECT group_id FROM shared_groups WHERE hidden=0", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
            val markers = context.getSharedPreferences("group_profile_queue", Context.MODE_PRIVATE)
            for (id in ids) {
                val stored = SharedGroups.get(db, id) ?: continue
                val state = stored.state
                if (state.members.none { it.key == own }) continue
                var changed = false
                if (self.myName.isNotBlank()) changed = save(db, id, own, ProfileNameBody.Update(self.profileNameRevision, self.myName.trim()))
                run {
                    for (member in state.members.filter { it.key != own }) {
                        val friend = repo.findByNostrPubkeySync(member.key) ?: continue
                        val revision = context.getSharedPreferences("profile_photo_state", Context.MODE_PRIVATE).getLong("name_received_${friend.id}", -1)
                        if (revision >= 0 && friend.originalName != null) changed = save(db, id, member.key, ProfileNameBody.Update(revision, friend.originalName!!)) || changed
                    }
                }
                GroupAvatars.sync(context, id, state)
                GroupAvatars.request(context, id, state)
                // Each member announces only their own name, straight to everyone else on the roster.
                val update = latest(db, id, own)
                if (update != null) {
                    val packet = GroupProtocol.Event("profile", GroupProtocol.ref(state), UUID.randomUUID().toString(), own,
                        ProfileNameBody.format(update), System.currentTimeMillis())
                    for (to in SharedGroups.recipients(db, state, own)) {
                        val marker = "$id:$own:${to.key}:${state.revision}"
                        if (markers.getLong(marker, -1) >= update.revision) continue
                        if (runCatching { SharedGroups.queue(context, db, to, id, packet) }.isSuccess)
                            check(markers.edit().putLong(marker, update.revision).commit())
                    }
                }
                if (changed) SharedGroups.notify(context, id)
            }
        }
    }
}
