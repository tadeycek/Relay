package com.relay.app.desktop

import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.GroupCallFenceSnapshot
import com.relay.app.call.group.GroupTrackRef
import com.relay.app.call.group.e2ee.ParticipantDevice
import com.relay.app.groups.GroupProtocol
import org.json.JSONObject

/** WP16 desktop group calls: the seams between the controller ([DesktopGroupCalls]) and the network / native media. */

/**
 * One announcement that may still be joined after this computer restarts. The join secret is part of it, so it is only
 * ever written to the encrypted database and never to a log, an error or a UI string.
 */
data class StoredGroupCall(val announcement: GroupAnnouncement, val rejoin: Boolean) {
    override fun toString(): String = "StoredGroupCall(room=${announcement.room}, rejoin=$rejoin, secret=<redacted>)"
}

/** Durable engine snapshot (replay fence + ended rooms) in the encrypted store. A throwing [commit] means "not accepted". */
interface GroupCallStateStore {
    fun snapshot(conversationId: String): GroupCallFenceSnapshot?
    fun commit(conversationId: String, snapshot: GroupCallFenceSnapshot)

    /** Every still-persisted joinable announcement (one per group). Restored on start, then re-checked with the server. */
    fun invitations(): Map<String, StoredGroupCall> = emptyMap()

    /**
     * Persists the fence snapshot AND the joinable announcement in one durable operation. The controller calls this only
     * after an accepted event; a throw means neither was committed, so the stored packet is retried and nothing is burnt.
     */
    fun commit(conversationId: String, snapshot: GroupCallFenceSnapshot, invitation: StoredGroupCall?) {
        commit(conversationId, snapshot)
        if (invitation != null) saveInvitation(conversationId, invitation) else clearInvitation(conversationId)
    }

    fun saveInvitation(conversationId: String, invitation: StoredGroupCall) {
        throw UnsupportedOperationException("this store does not persist group-call invitations")
    }

    fun clearInvitation(conversationId: String) {
        // The default store has no invitation table; commit(snapshot) already fenced the event.
    }
}

data class CreateRoomRequest(
    val room: String, val secretHash: String, val group: String, val revision: Long,
    val rosterHash: String, val members: List<String>,
)

/** Every documented answer of the roster-bound `POST /v1/call/rooms` (WP15 G5). No answer ever means "fall back to a legacy create". */
sealed interface CreateRoomOutcome {
    data object Created : CreateRoomOutcome
    /** 409 group_room_exists: another member's room won this (group, revision). Join [room] using THEIR group_start. */
    data class GroupRoomExists(val room: String?) : CreateRoomOutcome
    data object StaleRevision : CreateRoomOutcome
    data object NotInRoster : CreateRoomOutcome
    data class Refused(val status: Int, val code: String?, val retryable: Boolean) : CreateRoomOutcome
    data object Unreachable : CreateRoomOutcome
}

sealed interface RoomInfoOutcome {
    data class Live(val rostered: Boolean, val revision: Long?, val rosterHash: String?, val participants: Int) : RoomInfoOutcome
    /** 404: torn down, or this identity is not (or no longer) in the room's frozen set. */
    data object Gone : RoomInfoOutcome
    data object Unknown : RoomInfoOutcome
}

interface RoomSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

interface RoomListener {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(code: Int, reason: String)
    /** [httpStatus] is set when the upgrade itself was refused (403 not_in_roster, 404, 409 room_full, ...). */
    fun onFailure(httpStatus: Int?, code: String?)
}

class SfuException(val status: Int, val code: String?, message: String) : Exception(message)

/** The Worker's SFU proxy (`/v1/call/rooms/<room>/sfu/<op>`). The client never talks to the provider directly. */
fun interface SfuApi {
    /** [op] is `sessions-new`, `tracks-new`, `renegotiate` or `tracks-close`. Blocking; throws [SfuException]. */
    fun call(op: String, body: JSONObject): JSONObject
}

interface GroupCallRoomApi {
    fun create(request: CreateRoomRequest): CreateRoomOutcome
    fun info(room: String): RoomInfoOutcome
    /** Creator only. */
    fun end(room: String): Boolean
    /** Creator only: G5 removal, optionally rotating the join capability in the same call. */
    fun removeMembers(room: String, remove: List<String>, secretHash: String?): Boolean
    /** Creator only: capability rotation without removal. */
    fun rotate(room: String, secretHash: String): Boolean
    fun connect(room: String, secret: String, pid: String?, listener: RoomListener): RoomSocket
    fun sfu(room: String): SfuApi
}

sealed interface GroupMediaEvent {
    /** The publish/subscribe path failed; the controller leaves the call. */
    data class Failed(val reason: String) : GroupMediaEvent
}

/** Native audio for one group call. All methods are non-blocking requests; the implementation serialises negotiation. */
interface GroupMediaPort : AutoCloseable {
    fun open(sfu: SfuApi, onEvent: (GroupMediaEvent) -> Unit)
    /** Starts the microphone and publishes the `mic` track. Never called before the room is confirmed. */
    fun publish()
    fun setMicMuted(muted: Boolean)
    fun subscribe(ref: GroupTrackRef)
    fun unsubscribe(ref: GroupTrackRef)
    fun setDeafened(deafened: Boolean)
    fun setVolume(pid: String, volume: Float)
    /** Receiver-stats audio level 0..1 per pid. */
    fun levels(): Map<String, Float>

    /**
     * WP17: installs (or replaces) this epoch's frame E2EE provider; `null` means "no usable key", and the port must then
     * neither seal nor play — it drops encoded frames instead of forwarding plaintext. [onAuthenticated] is called for a
     * sender device whose frame actually opened under the current epoch, and for this host once its send path is sealing.
     * Implementations that have no encoded-frame transform (the historical fakes) keep the no-op default.
     */
    fun setFrameE2ee(provider: GroupFrameE2eeProvider?, onAuthenticated: ((ParticipantDevice, Int) -> Unit)? = null) {}

    /** True only while a provider is installed AND the microphone track is sealing its frames. Never optimistic. */
    fun frameE2eeActive(): Boolean = false
}

interface GroupCallTimers {
    fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable
}

/** The group state this controller needs, derived from a stored roster (names/relays are not part of the call roster hash). */
fun StoredGroup.protocolState() = GroupProtocol.State(sharedId, owner, name, revision, members)

internal fun GroupAnnouncement.isStart() = op == "group_start"
internal typealias Snapshot = GroupCallFenceSnapshot
