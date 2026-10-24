package com.relay.app.messaging

import android.content.Context
import android.os.SystemClock
import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.groups.GroupProtocol
import com.relay.app.groups.SharedGroups
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.Transports
import com.relay.app.transport.nostr.NostrIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Best effort only: no durable outbox, no message history, no offline retries. */
object TypingSignals {
    private val presence = TypingPresence()
    val entries = presence.entries
    private val sent = mutableMapOf<String, Pair<Long, Boolean>>()
    private val sendSlots = kotlinx.coroutines.sync.Semaphore(4)
    private fun direct(id: Long) = "contact:$id"
    private fun group(owner: String, id: String, author: String) = "group:$owner:$id:$author"
    fun names(entries: Map<String, TypingPresence.Entry>, contactId: Long?, state: GroupProtocol.State?, now: Long) =
        TypingPresence.names(entries, contactId, state, now)
    private fun update(key: String, name: String, s: TypingBody.Signal) =
        presence.update(key, name, s, SystemClock.elapsedRealtime(), (System.currentTimeMillis() - s.time).coerceAtLeast(0))
    private fun deliver(context: Context, recipient: String, relayHints: List<String>, signal: TypingBody.Signal, scopeKey: String) {
        MessagingRuntime.launchIo {
            sendSlots.acquire()
            try {
                if (synchronized(sent) { sent[scopeKey]?.first != signal.time }) return@launchIo
                if (signal.typing && !ActiveChat.foreground) return@launchIo
                if (!com.relay.app.util.RelayPreferences(context).typingIndicators) return@launchIo
                val transport = Transports.get(context)
                if (transport.status.value != TransportStatus.ONLINE || System.currentTimeMillis() - signal.time > TypingBody.TTL) return@launchIo
                withTimeoutOrNull(2500) {
                    transport.send(recipient, RelayPayload(PayloadCodec.newId(), signal.time,
                        TypingBody.format(signal)), relayHints, ephemeral = true)
                }
            } finally { sendSlots.release() }
        }
    }
    fun send(context: Context, contactId: Long? = null, groupId: Long? = null, typing: Boolean) {
        if (!com.relay.app.util.RelayPreferences(context).typingIndicators) return
        if (typing && (!ActiveChat.foreground ||
                (groupId != null && ActiveChat.groupId != groupId) || (groupId == null && ActiveChat.contactId != contactId))) return
        val id = if (groupId != null) "g:$groupId" else "c:$contactId"
        val now = synchronized(sent) {
            val old = sent[id]
            val timestamp = maxOf(System.currentTimeMillis(), (old?.first ?: 0) + 1)
            if (old != null && old.second == typing && timestamp - old.first < if (typing) 3000 else 1000) return
            sent[id] = timestamp to typing
            timestamp
        }
        MessagingRuntime.launchIo {
            val db = MessagingDb.get(context)
            if (groupId == null) {
                val contact = contactId?.let { com.relay.app.data.repository.ContactRepository(db).getByIdSync(it) }
                if (contact != null && GroupProtocol.eligible(contact))
                    deliver(context, contact.nostrPubkey!!, contact.relayHints, TypingBody.Signal(typing, now), id)
            } else {
                val stored = SharedGroups.get(db, groupId) ?: return@launchIo
                val own = NostrIdentity.publicKeyHex(context)
                if (stored.hidden || stored.state.members.none { it.key == own }) return@launchIo
                val signal = TypingBody.Signal(typing, now, stored.state.id, stored.state.owner, own)
                SharedGroups.recipients(db, stored.state, own).forEach { deliver(context, it.key, it.relays, signal, id) }
            }
        }
    }
    /** [contact] is the sender's contact row, if any. Group typing comes straight from the typing member, who
     *  only needs to be on our roster; one-to-one typing needs a verified contact. */
    fun handle(context: Context, sender: String, contact: Contact?, body: String): Boolean {
        if (!body.startsWith(TypingBody.PREFIX)) return false
        if (!com.relay.app.util.RelayPreferences(context).typingIndicators) return true
        val s = TypingBody.parse(body, System.currentTimeMillis()) ?: return true
        if (s.group.isEmpty()) {
            if (contact != null && GroupProtocol.eligible(contact)) update(direct(contact.id), contact.name, s)
        } else {
            val db = MessagingDb.get(context)
            val own = NostrIdentity.publicKeyHex(context)
            val stored = db.readableDatabase.rawQuery("SELECT group_id FROM shared_groups WHERE owner=? AND shared_id=?", arrayOf(s.owner, s.group))
                .use { if (it.moveToFirst()) SharedGroups.get(db, it.getLong(0)) else null } ?: return true
            if (s.author != sender || s.author == own || contact?.trustLevel == ContactTrustLevel.BLOCKED) return true
            if (stored.hidden || stored.state.members.none { it.key == own } || stored.state.members.none { it.key == s.author }) {
                return true
            }
            update(group(s.owner, s.group, s.author), com.relay.app.groups.GroupProfiles.display(db, stored.localId, s.author,
                stored.state.members.first { it.key == s.author }.name), s)
        }
        return true
    }
}
