package com.relay.app.sync

import com.relay.app.transport.RelayPayload

/**
 * Ports of the SENT-copy sync (WP05-AC2). Everything under `com.relay.app.sync` that is not named
 * `Android*` is pure Kotlin so the decisions are unit-tested with fakes.
 */

/** One row of the durable publish queue. [confirmed] flips once the contact/group send was accepted. */
data class SyncOutboxItem(
    val payloadId: String,
    val target: String,
    /** The `RelayPayload` JSON that goes in `P:` (sealed to this account's own key, see [SentSyncPayload]). */
    val payloadJson: String,
    val createdAt: Long,
    val confirmed: Boolean = false,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val mediaDigests: List<String> = emptyList(),
)

interface SyncOutbox {
    /** Inserts unless [SyncOutboxItem.payloadId] is already queued; returns whether a row was added. */
    fun enqueue(item: SyncOutboxItem): Boolean
    /** The user-visible send was accepted by the mailbox: the item may now be published. */
    fun confirm(payloadId: String)
    /** The send failed for good: the item must never be published. */
    fun discard(payloadId: String)
    /** Confirmed items due at [now], oldest first. */
    fun due(now: Long, limit: Int): List<SyncOutboxItem>
    /**
     * True if a confirmed row created before [createdAt] is backing off at [now]. Roster items use it so a later
     * roster never overtakes an earlier one (or an earlier KEYS event) that is waiting for its retry.
     */
    fun backedOffBefore(createdAt: Long, now: Long): Boolean = false
    fun recordAttempt(payloadId: String, attempts: Int, nextAttemptAt: Long)
    fun remove(payloadId: String)
    /** Called only after all SENT parts have been accepted, never on expiry/permanent failure. */
    fun published(payloadId: String) = remove(payloadId)
    /** Drops unconfirmed items older than the send give-up window; returns how many. */
    fun purgeStale(olderThan: Long): Int
    /** Removes every row (the last linked computer is gone). */
    fun clear()
}

/** Local "this install has linked a computer" hint. False means the sync code does nothing at all. */
interface LinkedHint {
    fun get(): Boolean
    fun set(value: Boolean)
}

sealed interface DevicesResult {
    data class Ok(val linkedCount: Int) : DevicesResult
    data class Failed(val retryable: Boolean = true) : DevicesResult
}

sealed interface PostResult {
    /** Stored, or the server already had these exact bytes. */
    data object Ok : PostResult
    data class Retry(val reason: String) : PostResult
    data class Permanent(val reason: String) : PostResult
}

/** One stored sync event as `GET /v1/sync` returns it; [data] is the decoded body bytes. */
class SyncEvent(val cursor: Long, val id: String, val rev: Long, val total: Int, val hash: String, val data: ByteArray)

sealed interface ListResult {
    data class Ok(val events: List<SyncEvent>, val more: Boolean) : ListResult
    data object Failed : ListResult
}

/** The server's sync channel. The adapter signs requests; nothing here sees keys. */
interface SyncNetwork {
    suspend fun listDevices(): DevicesResult
    suspend fun post(envelopeJson: String): PostResult
    suspend fun list(after: Long, limit: Int): ListResult
    suspend fun ack(through: Long): Boolean
}

interface SyncCursorStore {
    fun get(): Long
    fun set(value: Long)
}

/** Where a SENT copy lands. [id] is the local conversation id (contact id or group id). */
data class ThreadRef(val id: Long, val group: Boolean)

/**
 * The phone's message store as the applier needs it. [inTransaction] must commit atomically: if it
 * throws, nothing from the block is kept (the real implementation is one SQLCipher transaction).
 */
interface SentCopyStore {
    fun findDirect(contactPubkey: String): ThreadRef?
    fun findGroup(ownerPubkey: String, sharedId: String): ThreadRef?
    fun hasOutgoing(thread: ThreadRef, payloadId: String): Boolean
    /** Opens the inner layer of [body]; null if it cannot be opened (a body that was never sealed is returned as is). */
    fun open(body: String): String?
    /** Inserts an outgoing message; returns false if the store refused it (nothing was written). */
    fun insertOutgoing(thread: ThreadRef, payload: RelayPayload, plainBody: String): Boolean
    /** Brings an existing outgoing row in line (status NONE becomes SENT). Never creates a row. */
    fun mergeOutgoing(thread: ThreadRef, payloadId: String)
    fun <T> inTransaction(block: () -> T): T
}

enum class ApplyOutcome { APPLIED, MERGED, UNKNOWN_TARGET, UNREADABLE }
