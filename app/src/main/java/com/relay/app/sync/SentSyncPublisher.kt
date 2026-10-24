package com.relay.app.sync

import com.relay.app.linking.SyncBody
import com.relay.app.linking.SyncCodec
import com.relay.app.linking.SyncFormatException
import com.relay.app.linking.SyncResult
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.outbox.OutboxPolicy
import java.util.Base64

/** What one [SentSyncPublisher.flush] did. Counters only: never ids, keys or bodies. */
data class FlushReport(
    val posted: Int = 0,
    val items: Int = 0,
    val retried: Int = 0,
    val dropped: Int = 0,
    val skippedNoLinkedDevice: Boolean = false,
    val skippedDeviceLookupFailed: Boolean = false,
)

/**
 * Publishes a SENT event for each message this phone sent while a computer is linked.
 *
 * Staging ([stage]) happens in the same database transaction as the message's own outbox row and
 * touches no network. The row stays unconfirmed until the real send is accepted ([confirm]); a send
 * that fails for good discards it ([discard]), so a failed send is never mirrored. [flush] posts the
 * confirmed rows, retrying with the app's existing [OutboxPolicy] schedule; a failed publish only
 * moves that row's next attempt and never touches the user-visible message.
 *
 * Nothing runs unless [hint] says this install has linked a computer, so an unlinked user makes no
 * request and writes no row. The linked-device list is cached for [deviceTtlMs].
 */
class SentSyncPublisher(
    private val net: SyncNetwork,
    private val outbox: SyncOutbox,
    private val hint: LinkedHint,
    private val clock: () -> Long = System::currentTimeMillis,
    private val origin: String = ORIGIN_PRIMARY,
    private val deviceTtlMs: Long = 60_000L,
    private val batch: Int = 8,
) {
    private var cachedAt = Long.MIN_VALUE
    private var cachedLinked = false

    fun stage(payloadId: String, target: String, payloadJson: String): Boolean {
        if (!hint.get()) return false
        return outbox.enqueue(SyncOutboxItem(payloadId, target, payloadJson, clock()))
    }

    fun confirm(payloadId: String) {
        if (hint.get()) outbox.confirm(payloadId)
    }

    fun discard(payloadId: String) {
        if (hint.get()) outbox.discard(payloadId)
    }

    suspend fun flush(): FlushReport {
        val now = clock()
        outbox.purgeStale(now - OutboxPolicy.GIVE_UP_AFTER_MS)
        val due = outbox.due(now, batch)
            .filter { hint.get() || it.mediaDigests.isNotEmpty() }
            .filterNot { GroupRosterStager.isRosterItem(it.payloadId) && outbox.backedOffBefore(it.createdAt, now) }
        if (due.isEmpty()) return FlushReport()

        if (due.none { it.mediaDigests.isNotEmpty() }) when (val linked = linkedDevices(now)) {
            is DevicesResult.Failed -> return FlushReport(skippedDeviceLookupFailed = true)
            is DevicesResult.Ok -> if (linked.linkedCount == 0) {
                // The last computer is gone: stop mirroring, and stop asking until the user links again.
                outbox.clear()
                hint.set(false)
                cachedAt = Long.MIN_VALUE
                return FlushReport(skippedNoLinkedDevice = true)
            }
        }

        var posted = 0
        var done = 0
        var dropped = 0
        for (item in due) {
            if (OutboxPolicy.isExpired(item.createdAt, now)) {
                outbox.remove(item.payloadId); dropped++; continue
            }
            val planned = try {
                wiresFor(item)
            } catch (e: SyncFormatException) {
                null
            }
            if (planned == null) {
                // Not a payload the codec accepts (or over its bounds): it can never be published.
                outbox.remove(item.payloadId); dropped++; continue
            }
            val (syncId, wires) = planned
            var failed = false
            for ((index, wire) in wires.withIndex()) {
                val body = SentSyncWire.envelope(wire, SentSyncWire.eventId(syncId, index, wires.size), REVISION, now, item.mediaDigests)
                when (net.post(body)) {
                    PostResult.Ok -> posted++
                    is PostResult.Permanent -> { outbox.remove(item.payloadId); dropped++; failed = true }
                    is PostResult.Retry -> {
                        val attempts = item.attempts + 1
                        outbox.recordAttempt(item.payloadId, attempts, clock() + OutboxPolicy.nextDelayMs(attempts))
                        // Same stop-the-lane rule as the message outbox: the rest waits for the next round.
                        return FlushReport(posted, done, retried = 1, dropped = dropped)
                    }
                }
                if (failed) break
            }
            if (!failed) { outbox.published(item.payloadId); done++ }
        }
        return FlushReport(posted, done, dropped = dropped)
    }

    /** The sync id and wire bodies of one queued item. A group-roster item carries its prebuilt sealed GROUP body. */
    private fun wiresFor(item: SyncOutboxItem): Pair<String, List<String>>? {
        if (GroupRosterStager.isRosterItem(item.payloadId)) {
            val body = (SyncCodec.decode(item.payloadJson) as? SyncResult.Ok)?.body as? SyncBody.Group ?: return null
            return body.syncId to SyncCodec.chunk(body)
        }
        val payload = PayloadCodec.decode(item.payloadJson) ?: return null
        val b64 = Base64.getEncoder().encodeToString(item.payloadJson.toByteArray(Charsets.UTF_8))
        val body = SyncBody.Sent(origin, SentSyncWire.syncIdFor(payload.id), REVISION, item.target, b64)
        return body.syncId to SyncCodec.chunk(body)
    }

    private suspend fun linkedDevices(now: Long): DevicesResult {
        if (cachedAt != Long.MIN_VALUE && now - cachedAt < deviceTtlMs && cachedLinked) return DevicesResult.Ok(1)
        val result = net.listDevices()
        if (result is DevicesResult.Ok) { cachedAt = now; cachedLinked = result.linkedCount > 0 }
        return result
    }

    companion object {
        const val ORIGIN_PRIMARY = "primary"
        const val REVISION = 1L
    }
}
