package com.relay.app.desktop

import com.relay.app.groups.GroupAvatarBody
import com.relay.app.groups.GroupProtocol
import com.relay.app.media.ProfileNameBody
import com.relay.app.transport.SendResult
import java.util.UUID

/**
 * How a group control or post leaves this desktop. The engine implements this with the existing
 * [com.relay.app.transport.Transport.send] and [com.relay.app.messaging.InnerEnvelope.seal] seams, so
 * group traffic uses exactly the same encrypted, signed wire path as 1:1 text. A fake implementation
 * drives the unit tests.
 */
fun interface GroupSender {
    suspend fun send(peer: String, inner: String?, relays: List<String>, body: String): SendResult
}

/** Legacy roster-edit input; desktop entry points reject roster writes under LP-D03. */
data class GroupMemberInput(
    val key: String,
    val name: String,
    val inner: String? = null,
    val relays: List<String> = emptyList(),
)

/**
 * Groups on the desktop: phone-authored rosters, direct v2 delivery to every member, posts that
 * land in the ordinary encrypted message store under the group's conversation id, and replay of events
 * that arrived before their roster. The phone's [GroupProtocol] is the authority on validity and
 * membership; this class only decides what to persist and what to send.
 */
class DesktopGroups(
    private val store: DesktopMessageStore,
    private val ownPubkey: String,
    private val ownName: () -> String,
    private val ownInner: () -> String?,
    private val sender: GroupSender,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    data class SendReport(val sent: Int, val failed: Int, val firstError: String?)

    /** LP-D03: only the primary phone creates rosters. */
    suspend fun create(name: String, members: List<GroupMemberInput>): StoredGroup {
        throw IllegalStateException("Edit groups on your phone")
    }

    /** Posts to every other member directly, and stores the post under the same event id the wire uses. */
    suspend fun send(conversationId: String, text: String): SendReport {
        val group = requireGroup(conversationId)
        require(!group.hidden && group.members.any { it.key == ownPubkey }) { "You are no longer a member of this group" }
        require(text.isNotBlank()) { "Write a message first" }
        val state = group.toState()
        val eventId = newId()
        val time = clock()
        val event = GroupProtocol.Event("post", GroupProtocol.ref(state), eventId, ownPubkey, text.trim(), time)
        // Validate first so a too-large or empty body never becomes a local row.
        GroupProtocol.format(event)
        store.insertMessage(
            DesktopMessage(
                id = "$ownPubkey:$eventId",
                conversationId = conversationId,
                sender = ownPubkey,
                body = event.text,
                ts = time,
                outgoing = true,
            ),
        )
        return fanOut(state, event, skip = ownPubkey)
    }

    suspend fun addMember(conversationId: String, member: GroupMemberInput): StoredGroup {
        throw IllegalStateException("Edit groups on your phone")
    }

    suspend fun removeMember(conversationId: String, key: String): StoredGroup {
        throw IllegalStateException("Edit groups on your phone")
    }

    suspend fun rename(conversationId: String, name: String): StoredGroup {
        throw IllegalStateException("Edit groups on your phone")
    }

    suspend fun setAvatar(conversationId: String, jpeg: ByteArray?): StoredGroup {
        throw IllegalStateException("Edit groups on your phone")
    }

    /** Asks the owner for the current group picture (non-owners only). */
    suspend fun requestAvatar(conversationId: String): SendReport {
        val group = requireGroup(conversationId)
        if (group.owner == ownPubkey) return SendReport(0, 0, null)
        val owner = group.members.firstOrNull { it.key == group.owner } ?: return SendReport(0, 0, null)
        val result = sender.send(owner.key, owner.inner, owner.relays, "$GROUP_AVATAR_REQUEST${group.sharedId}")
        return if (result is SendResult.Sent) SendReport(1, 0, null)
        else SendReport(0, 1, (result as? SendResult.Failed)?.reason)
    }

    /** Returns true when [body] was a group control (valid or not), so it never renders as chat. */
    fun handle(senderKey: String, body: String): Boolean {
        if (!GroupProtocol.isControl(body)) return false
        val packet = GroupProtocol.parse(body) ?: return true
        when (packet) {
            is GroupProtocol.Sync -> handleSync(senderKey, packet.state)
            is GroupProtocol.Event -> handleEvent(senderKey, packet, body)
        }
        return true
    }

    /** True when [body] is a group-avatar control, valid or not; the engine then answers requests. */
    fun isAvatarControl(body: String): Boolean =
        body.startsWith(GROUP_AVATAR_REQUEST) || body.startsWith(GroupAvatarBody.PREFIX)

    /** The owner's reply to a peer's avatar request: one body, or zero when there is no picture. */
    suspend fun answerAvatarRequest(peer: String, body: String): SendReport {
        if (!body.startsWith(GROUP_AVATAR_REQUEST)) return SendReport(0, 0, null)
        val groupId = body.removePrefix(GROUP_AVATAR_REQUEST).trim()
        val owned = store.storedGroups().firstOrNull { it.owner == ownPubkey && it.sharedId == groupId }
            ?: return SendReport(0, 0, null)
        val member = owned.members.firstOrNull { it.key == peer } ?: return SendReport(0, 0, null)
        if (owned.avatarRevision < 0) return SendReport(0, 0, null)
        val reply = GroupAvatarBody.format(GroupAvatarBody.Update(owned.sharedId, owned.avatarRevision, owned.avatar))
        return when (val result = sender.send(member.key, member.inner, member.relays, reply)) {
            is SendResult.Sent -> SendReport(1, 0, null)
            is SendResult.Failed -> SendReport(0, 1, result.reason)
        }
    }

    fun handleAvatar(senderKey: String, body: String): Boolean {
        if (!body.startsWith(GroupAvatarBody.PREFIX)) return false
        val update = GroupAvatarBody.parse(body) ?: return true
        if (!validDesktopAvatar(update.jpeg)) return true
        // Only the group's creator may set its picture.
        val group = store.storedGroup(senderKey, update.group) ?: return true
        if (group.members.none { it.key == ownPubkey }) return true
        store.setGroupAvatar(group.conversationId, update.jpeg, update.revision)
        return true
    }

    /** Members that can be reached directly today (a known inner key and not blocked/deleted). */
    fun eligibleContacts(): List<DesktopContact> = store.contacts().filter { it.eligibleForGroups() }

    private fun handleSync(senderKey: String, incoming: GroupProtocol.State) {
        // A known owner's newer roster may remove us; retain that revision as a tombstone.
        // An unknown group still needs a verified invitation that explicitly lists us.
        if (store.storedGroups().any { it.sharedId == incoming.id && it.owner != incoming.owner }) return
        val current = store.storedGroup(groupConversationId(incoming.owner, incoming.id))
        if (current == null && (incoming.members.none { it.key == ownPubkey } ||
                store.contacts().none { it.nostrPubkey == senderKey && it.eligibleForGroups() })) return
        if (current != null && current.owner != incoming.owner) return
        if (!GroupProtocol.acceptSync(senderKey, current?.toState(), incoming)) return
        store.saveGroupState(incoming, now = clock(), visible = incoming.members.any { it.key == ownPubkey })
        replayPending(store.storedGroup(incoming.owner, incoming.id)?.toState() ?: return)
    }

    private fun handleEvent(senderKey: String, event: GroupProtocol.Event, body: String) {
        val current = store.storedGroup(event.group.owner, event.group.id)
        when (GroupProtocol.checkEvent(senderKey, current?.toState(), event)) {
            GroupProtocol.Verdict.REJECT -> return
            GroupProtocol.Verdict.LATER -> {
                // Hold only when the group owner is someone this desktop already knows; otherwise it is spam.
                val ownerKnown = store.contacts().any { it.nostrPubkey == event.group.owner && it.eligibleForGroups() }
                if (current != null || ownerKnown) {
                    store.holdGroupEvent(event.group.owner, event.group.id, senderKey, body, now = clock())
                }
                return
            }
            GroupProtocol.Verdict.ACCEPT -> Unit
        }
        val group = current ?: return
        if (group.members.none { it.key == ownPubkey }) return
        when (event.kind) {
            "post" -> store.insertMessage(
                DesktopMessage(
                    id = "${event.author}:${event.event}",
                    conversationId = group.conversationId,
                    sender = event.author,
                    body = event.text,
                    ts = event.time,
                    outgoing = event.author == ownPubkey,
                ),
            )
            "profile" -> ProfileNameBody.parse(event.text)?.let { update ->
                store.saveProfileName(event.author, update.name, update.revision, now = clock())
            }
            // delete/purge/read are not rendered in this build; they must not become message rows.
            else -> Unit
        }
    }

    private fun replayPending(state: GroupProtocol.State) {
        for (held in store.pendingGroupEvents(state.owner, state.id)) {
            val packet = GroupProtocol.parse(held.body) as? GroupProtocol.Event
            if (packet == null) {
                store.deletePendingGroupEvent(held.row)
                continue
            }
            if (GroupProtocol.checkEvent(held.sender, state, packet) == GroupProtocol.Verdict.LATER) continue
            store.deletePendingGroupEvent(held.row)
            handleEvent(held.sender, packet, held.body)
        }
    }

    private suspend fun fanOut(state: GroupProtocol.State, packet: GroupProtocol.Packet, skip: String): SendReport {
        val body = GroupProtocol.format(packet)
        var sent = 0
        var failed = 0
        var firstError: String? = null
        for (member in state.members) {
            if (member.key == skip) continue
            when (val result = sender.send(member.key, member.inner, member.relays, body)) {
                is SendResult.Sent -> sent++
                is SendResult.Failed -> {
                    failed++
                    if (firstError == null) firstError = result.reason
                }
            }
        }
        return SendReport(sent, failed, firstError)
    }

    private fun requireGroup(conversationId: String): StoredGroup =
        store.storedGroup(conversationId) ?: throw IllegalStateException("This group is not on this computer")


    private fun StoredGroup.toState() = GroupProtocol.State(sharedId, owner, name, revision, members)

    companion object {
        const val GROUP_AVATAR_REQUEST = "TYPE:PROFILE_PHOTO|KIND:GROUP_AVATAR_REQUEST|"

        /** Same ceiling as [com.relay.app.media.ProfilePhotoBody.MAX_BYTES]. */
        const val PROFILE_AVATAR_MAX_BYTES = 8 * 1024
    }
}

/** Group membership needs a reachable, non-blocked contact; the phone has already vetted trust. */
fun DesktopContact.eligibleForGroups(): Boolean =
    nostrPubkey != null && publicKey != null && qrVerified && pendingPublicKey == null && deletedAt == null &&
        trustLevel.uppercase() != "BLOCKED"
