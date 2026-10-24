package com.relay.app.sync

import com.relay.app.linking.ReassemblyResult
import com.relay.app.linking.SyncBody
import com.relay.app.linking.SyncCodec
import com.relay.app.linking.SyncReassembler
import com.relay.app.linking.SyncResult
import com.relay.app.transport.PayloadCodec
import java.util.Base64

/** Counters for one [SentSyncApplier.poll]; never ids, keys or bodies. */
data class PollReport(
    val fetched: Int = 0,
    val applied: Int = 0,
    val merged: Int = 0,
    val ignoredOwn: Int = 0,
    val ignoredKind: Int = 0,
    val unknownTarget: Int = 0,
    val unreadable: Int = 0,
    val malformed: Int = 0,
    val cursor: Long = 0L,
    val acked: Boolean = false,
    val stoppedOnFailure: Boolean = false,
    val fetchFailed: Boolean = false,
)

/**
 * Applies SENT events other devices published, so a message written on a computer shows up as an
 * outgoing message in the same phone thread, with the same payload id.
 *
 * Order per event: decode and validate -> (reassemble) -> skip our own origin -> locate the thread ->
 * commit the message in one transaction. The stored cursor moves only after that commit, and only to a
 * point behind which every event is finished: a multi-part event whose parts have not all arrived pins
 * the cursor just before its first part, so a restart refetches it instead of losing it.
 *
 * Policies: a SENT whose target is not a known contact or group is dropped and counted (no placeholder
 * contact is invented from a sync event); other event kinds (READ, KEYS, ...) belong to other work and
 * are skipped; malformed, unknown-version and oversize events are skipped and counted.
 */
class SentSyncApplier(
    private val net: SyncNetwork,
    private val store: SentCopyStore,
    private val cursor: SyncCursorStore,
    private val hint: LinkedHint,
    private val clock: () -> Long = System::currentTimeMillis,
    private val origin: String = SentSyncPublisher.ORIGIN_PRIMARY,
) {

    suspend fun poll(): PollReport {
        if (!hint.get()) return PollReport()
        val stored = cursor.get()
        var fetchAfter = stored
        var safe = stored
        val reassembler = SyncReassembler()
        val pendingFirst = LinkedHashMap<String, Long>()
        var r = PollReport(cursor = stored)

        pages@ for (page in 0 until SyncLimits.MAX_PAGES_PER_POLL) {
            val listed = net.list(fetchAfter, SyncLimits.MAX_PAGE_EVENTS)
            if (listed !is ListResult.Ok) return r.copy(fetchFailed = true)
            if (listed.events.isEmpty()) break
            for (event in listed.events.sortedBy { it.cursor }) {
                if (event.cursor <= fetchAfter) continue
                val outcome = try {
                    handle(event, reassembler, pendingFirst)
                } catch (e: Exception) {
                    // The Room/SQLCipher commit failed: nothing past the last finished event may move.
                    r = r.copy(stoppedOnFailure = true)
                    break@pages
                }
                r = r.copy(fetched = r.fetched + 1).count(outcome)
                fetchAfter = event.cursor
                safe = maxOf(safe, (pendingFirst.values.minOrNull()?.minus(1)) ?: event.cursor)
                    .coerceAtMost(event.cursor)
            }
            if (!listed.more) break
        }

        if (safe > stored) {
            cursor.set(safe)
            // Acknowledged only after the commit above and the local cursor write; a lost ack is retried by the next poll.
            r = r.copy(cursor = safe, acked = net.ack(safe))
        }
        return r
    }

    private enum class Outcome { APPLIED, MERGED, OWN, KIND, TARGET, UNREADABLE, MALFORMED, PENDING }

    private fun PollReport.count(o: Outcome) = when (o) {
        Outcome.APPLIED -> copy(applied = applied + 1)
        Outcome.MERGED -> copy(merged = merged + 1)
        Outcome.OWN -> copy(ignoredOwn = ignoredOwn + 1)
        Outcome.KIND -> copy(ignoredKind = ignoredKind + 1)
        Outcome.TARGET -> copy(unknownTarget = unknownTarget + 1)
        Outcome.UNREADABLE -> copy(unreadable = unreadable + 1)
        Outcome.MALFORMED -> copy(malformed = malformed + 1)
        Outcome.PENDING -> this
    }

    private fun handle(event: SyncEvent, reassembler: SyncReassembler, pendingFirst: MutableMap<String, Long>): Outcome {
        if (event.total > SentSyncWire.MAX_EVENT_BYTES || event.data.size != event.total) return Outcome.MALFORMED
        if (SentSyncWire.sha256Hex(event.data) != event.hash) return Outcome.MALFORMED
        val wire = String(event.data, Charsets.UTF_8)
        val body = when (val decoded = SyncCodec.decode(wire)) {
            is SyncResult.Err -> return Outcome.MALFORMED
            is SyncResult.Ok -> decoded.body
        }
        if (body !is SyncBody.Part) return logical(body)
        return when (val joined = reassembler.offer(body, clock())) {
            is ReassemblyResult.Pending, is ReassemblyResult.Duplicate -> {
                pendingFirst.putIfAbsent(body.syncId, event.cursor)
                Outcome.PENDING
            }
            is ReassemblyResult.Rejected -> {
                pendingFirst.remove(body.syncId)
                Outcome.MALFORMED
            }
            is ReassemblyResult.Complete -> {
                pendingFirst.remove(body.syncId)
                when (val whole = SyncCodec.decode(joined.body)) {
                    is SyncResult.Err -> Outcome.MALFORMED
                    is SyncResult.Ok -> if (whole.body is SyncBody.Part) Outcome.MALFORMED else logical(whole.body)
                }
            }
        }
    }

    private fun logical(body: SyncBody): Outcome {
        if (body !is SyncBody.Sent) return Outcome.KIND
        if (body.origin == origin) return Outcome.OWN
        val payload = runCatching { PayloadCodec.decode(String(Base64.getDecoder().decode(body.payloadBase64), Charsets.UTF_8)) }.getOrNull()
            ?: return Outcome.MALFORMED
        val thread = threadFor(body.target) ?: return Outcome.TARGET
        return store.inTransaction {
            if (store.hasOutgoing(thread, payload.id)) {
                store.mergeOutgoing(thread, payload.id)
                Outcome.MERGED
            } else {
                val plain = store.open(payload.body) ?: return@inTransaction Outcome.UNREADABLE
                check(store.insertOutgoing(thread, payload, plain)) { "message store refused the sent copy" }
                Outcome.APPLIED
            }
        }
    }

    private fun threadFor(target: String): ThreadRef? {
        if (!target.startsWith("group:")) return store.findDirect(target)
        val parts = target.removePrefix("group:").split(':')
        return if (parts.size == 2) store.findGroup(parts[0], parts[1]) else null
    }
}
