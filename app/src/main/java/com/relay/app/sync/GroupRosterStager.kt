package com.relay.app.sync

import com.relay.app.linking.GroupRosterSync
import com.relay.app.linking.SyncCodec
import com.relay.app.linking.SyncFormatException

/**
 * Phone-side producer of `GROUP` roster sync events (WP05-AC3, LP-D03).
 *
 * [stage] seals one roster snapshot to this account's own key, builds the `GROUP` sync body (stable sync id
 * per snapshot, `origin = primary`) and writes it, already confirmed, into the durable sync outbox. It
 * touches no network: [SentSyncPublisher.flush] posts it later with the same retry schedule as sent copies,
 * so a failing network can never block or fail the group edit, and a retry after a restart re-posts the
 * identical stored bytes. An install that never linked a computer ([LinkedHint] false) writes no row,
 * seals nothing and so makes no request later: the legacy path is untouched.
 *
 * Ordering: the outbox lane is `created_at`-ordered and a retryable failure ends the flush before any later
 * row, so anything staged earlier (a KEYS event, an earlier roster) is posted first. Nothing here changes who
 * receives group keys: removed members keep getting no future roster or key traffic from the group paths.
 */
class GroupRosterStager(
    private val outbox: SyncOutbox,
    private val hint: LinkedHint,
    private val seal: (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastCreatedAt = Long.MIN_VALUE

    /** True when a new row was queued; false when unlinked, already queued, unsealable or outside the schema. */
    fun stage(roster: GroupRosterSync.Roster): Boolean {
        if (!hint.get()) return false
        val body = try {
            GroupRosterSync.seal(roster, SentSyncPublisher.ORIGIN_PRIMARY, seal)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val logical = try {
            SyncCodec.encodeLogical(body)
        } catch (e: SyncFormatException) {
            return false
        }
        // Strictly increasing creation time keeps the lane order total even when two edits land in one millisecond.
        val createdAt = synchronized(this) { maxOf(clock(), lastCreatedAt + 1).also { lastCreatedAt = it } }
        return outbox.enqueue(
            SyncOutboxItem(
                payloadId = ID_PREFIX + body.syncId,
                target = "group:${roster.state.owner}:${roster.state.id}",
                payloadJson = logical,
                createdAt = createdAt,
                confirmed = true, // nothing to wait for: the edit itself is local
            ),
        )
    }

    companion object {
        const val ID_PREFIX = "gr:"
        fun isRosterItem(payloadId: String) = payloadId.startsWith(ID_PREFIX)
    }
}
