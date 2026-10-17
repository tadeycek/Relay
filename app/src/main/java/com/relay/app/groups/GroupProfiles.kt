package com.relay.app.groups

import android.content.Context
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.repository.ContactRepository
import com.relay.app.media.ProfileNameBody
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.Outgoing
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.RelayPreferences
import java.util.UUID

/** Profile revisions are separate from owner-authorized membership revisions. Owner relays names
 * only for current members, authenticated on the existing private transport. Never forwards aliases. */
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
    private fun save(db: RelayDbHelper, group: Long, key: String, update: ProfileNameBody.Update) {
        db.writableDatabase.execSQL("""INSERT INTO group_profiles(group_id,member_key,revision,name) VALUES(?,?,?,?)
            ON CONFLICT(group_id,member_key) DO UPDATE SET revision=excluded.revision,name=excluded.name
            WHERE excluded.revision > group_profiles.revision""", arrayOf(group, key, update.revision, update.name))
    }
    fun accepts(sender: String, own: String, current: GroupProtocol.State, p: GroupProtocol.Packet): Boolean =
        p.state.id == current.id && p.state.owner == current.owner &&
            current.members.any { it.key == own } && current.members.any { it.key == p.author } &&
            ((p.kind == "profile-submit" && own == current.owner && sender == p.author && sender != own) ||
                (p.kind == "profile" && sender == current.owner))

    @Synchronized fun handle(context: Context, sender: String, stored: SharedGroups.Stored?, packet: GroupProtocol.Packet) {
        if (stored == null || stored.hidden) return
        val own = NostrIdentity.publicKeyHex(context)
        if (!accepts(sender, own, stored.state, packet)) return
        val update = ProfileNameBody.parse(packet.text) ?: return
        val db = MessagingDb.get(context)
        save(db, stored.localId, packet.author, update)
        SharedGroups.notify(context, stored.localId)
        if (own == stored.state.owner) syncPending(context)
    }

    /** Invoked on profile changes, membership changes and reconnect. Durable queue markers avoid
     * repeated fanout; roster revision makes a newly added recipient receive current names. */
    @Synchronized fun syncPending(context: Context) {
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
            if (self.myName.isNotBlank()) save(db, id, own, ProfileNameBody.Update(self.profileNameRevision, self.myName.trim()))
            run {
                for (member in state.members.filter { it.key != own }) {
                    val friend = repo.findByNostrPubkeySync(member.key) ?: continue
                    val revision = context.getSharedPreferences("profile_photo_state", Context.MODE_PRIVATE).getLong("name_received_${friend.id}", -1)
                    if (revision >= 0 && friend.originalName != null) save(db, id, member.key, ProfileNameBody.Update(revision, friend.originalName))
                }
            }
            val authors = if (own == state.owner) state.members.map { it.key } else listOf(own)
            val recipients = if (own == state.owner) state.members.map { it.key }.filter { it != own } else listOf(state.owner)
            for (author in authors) {
                val update = latest(db, id, author) ?: continue
                val body = ProfileNameBody.format(update)
                val packet = GroupProtocol.Packet(if (own == state.owner) "profile" else "profile-submit", state,
                    UUID.randomUUID().toString(), author, body, System.currentTimeMillis())
                for (key in recipients) {
                    val contact = repo.findByNostrPubkeySync(key)?.takeIf(GroupProtocol::eligible) ?: continue
                    val marker = "$id:$author:$key:${state.revision}"
                    if (markers.getLong(marker, -1) >= update.revision) continue
                    if (Outgoing.enqueue(context, contact, GroupProtocol.format(packet)) != null)
                        check(markers.edit().putLong(marker, update.revision).commit())
                }
            }
            SharedGroups.notify(context, id)
        }
    }
}
