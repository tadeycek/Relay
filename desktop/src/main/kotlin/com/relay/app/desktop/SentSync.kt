package com.relay.app.desktop

import com.relay.app.linking.ReassemblyResult
import com.relay.app.linking.SyncBody
import com.relay.app.linking.SyncCodec
import com.relay.app.linking.SyncReassembler
import com.relay.app.linking.SyncResult
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/*
 * Sent-copy sync, desktop side (WP05-AC2).
 *
 *  - Outgoing: after the contact send SUCCEEDED, the message row and a SENT sync wire body are
 *    committed in one local transaction (the outbox). A separate flush posts the outbox to
 *    `POST /v1/sync`. A failed or unreachable sync never touches the user-visible send.
 *  - Incoming: `GET /v1/sync?after=<local cursor>`, reassemble, apply SENT as an outgoing row with
 *    the SAME payload id, move the local cursor in the same transaction, and only then ack.
 *
 * Nothing here logs or returns a message body, a key or a payload: reports carry counts and
 * short reason codes only.
 */

// ---- seams ---------------------------------------------------------------------------------------

sealed interface SyncHttpResult<out T> {
    data class Ok<T>(val value: T) : SyncHttpResult<T>

    /** Transient (network, 5xx, 429, auth hiccup, retryable 409): try again later, keep the outbox. */
    data class Retry(val reason: String) : SyncHttpResult<Nothing>

    /** Permanent for this request (bad request, conflict, too large). Retrying the same bytes cannot help. */
    data class Rejected(val reason: String) : SyncHttpResult<Nothing>
}

/** One assembled event as `GET /v1/sync` returns it. [dataBase64] is the body bytes. */
data class SyncEventDto(
    val cursor: Long,
    val id: String,
    val rev: Long,
    val total: Long,
    val hash: String,
    val dataBase64: String,
)

data class SyncPage(val events: List<SyncEventDto>, val more: Boolean, val cursor: Long)

/** The three sync routes. The production implementation is [DesktopSyncHttp]; tests use a fake. */
interface SyncHttp {
    /** `POST /v1/sync`: one envelope. The value is true when the server answered `duplicate`. */
    suspend fun post(envelopeJson: String): SyncHttpResult<Boolean>

    suspend fun list(after: Long, limit: Int): SyncHttpResult<SyncPage>

    suspend fun ack(through: Long): SyncHttpResult<Long>
}

data class OutboxEntry(val id: Long, val wire: String, val createdAt: Long, val attempts: Int, val mediaDigests: List<String> = emptyList())

enum class SyncCommit { INSERTED, MERGED, NONE }

/** The slice of the encrypted store the sync needs. [DesktopMessageStore] implements it. */
interface SyncStore {
    /** Inserts [message] and queues [wires] in one transaction. False = the id already existed (nothing queued). */
    fun enqueueSent(message: DesktopMessage, wires: List<String>, now: Long): Boolean
    fun outbox(limit: Int): List<OutboxEntry>
    fun outboxDone(id: Long)
    fun outboxAttempt(id: Long)
    fun syncCursor(): Long

    /** Inserts [message] (nullable) and moves the cursor to [cursor], atomically. Never moves the cursor back. */
    fun commitSync(message: DesktopMessage?, cursor: Long): SyncCommit
}

// ---- server envelope ------------------------------------------------------------------------------

/**
 * `POST /v1/sync` envelope for one [SyncCodec] wire body. The server body is the wire string's UTF-8
 * bytes (≤ 12 KiB, `SyncCodec.MAX_RAW_BYTES`), sent as ONE server part (`part 0 of 1`).
 *
 * The server id is derived from the bytes (first 32 hex of SHA-256), so re-posting the same wire body
 * after a crash is byte-identical and the server answers `duplicate:true` instead of storing a second
 * event. A chunked SENT (several `PART` wire bodies) is therefore several server events, one per
 * wire body; the codec's `SID` ties them together on the reading side.
 */
object SyncWire {
    const val SERVER_TTL_MS = 24L * 60 * 60 * 1000

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Deterministic sync id for one payload (same rule as the phone), so a re-stage names the same event. */
    fun syncIdFor(payloadId: String): String =
        sha256Hex("relay-sent:$payloadId".toByteArray(Charsets.UTF_8)).substring(0, 32)

    fun serverId(wire: String): String = sha256Hex(wire.toByteArray(Charsets.UTF_8)).substring(0, 32)

    fun envelope(wire: String, revision: Long, now: Long, mediaDigests: List<String> = emptyList()): String {
        val bytes = wire.toByteArray(Charsets.UTF_8)
        return JSONObject()
            .put("v", 1)
            .also { j ->
                require(mediaDigests.size <= 8 && mediaDigests.distinct().size == mediaDigests.size && mediaDigests.all { Regex("[0-9a-f]{64}").matches(it) })
                if (mediaDigests.isNotEmpty()) j.put("media", org.json.JSONArray(mediaDigests))
            }
            .put("id", serverId(wire))
            .put("rev", maxOf(1L, revision))
            .put("part", 0)
            .put("parts", 1)
            .put("total", bytes.size)
            .put("hash", sha256Hex(bytes))
            .put("expires", now + SERVER_TTL_MS)
            .put("data", Base64.getEncoder().encodeToString(bytes))
            .toString()
    }
}

// ---- publisher --------------------------------------------------------------------------------------

data class FlushReport(
    val posted: Int = 0,
    val duplicates: Int = 0,
    val dropped: Int = 0,
    val remaining: Int = 0,
    /** Short reason of the transient failure that stopped the flush, or null. */
    val error: String? = null,
)

class SentSyncPublisher(
    private val deviceId: String,
    private val store: SyncStore,
    private val http: SyncHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newSyncId: (payloadId: String) -> String = { SyncWire.syncIdFor(it) },
    /**
     * Seals a plain body to our own HPKE key (the identity keyset every linked device holds) so the
     * sync server never sees message text. Null = no sealing (tests only). When set and it returns
     * null, no copy is published: the plain body is never uploaded as a fallback.
     */
    private val sealToSelf: ((String) -> String?)? = null,
    private val maxAgeMs: Long = SyncWire.SERVER_TTL_MS,
) {
    private val flushLock = Mutex()

    /** Messages whose copy could not be encoded (counted, never fatal to the send). */
    @Volatile var encodeFailures: Int = 0
        private set

    /**
     * Builds the wire bodies for one sent message. Pure: `TO` is the contact pubkey or
     * `group:OWNER:ID`; `P` is the base64 [RelayPayload] JSON of [payload]; bodies over the
     * single-part bound are chunked by [SyncCodec.chunk].
     */
    fun buildWires(target: String, payload: RelayPayload): List<String> {
        val carried = if (sealToSelf == null) payload else {
            val sealed = sealToSelf.invoke(payload.body) ?: throw IllegalArgumentException("cannot seal the copy to our own key")
            payload.copy(body = sealed)
        }
        val body = SyncBody.Sent(
            origin = deviceId,
            syncId = newSyncId(payload.id),
            revision = 1,
            target = target,
            payloadBase64 = Base64.getEncoder().encodeToString(PayloadCodec.encode(carried).toByteArray(Charsets.UTF_8)),
        )
        return SyncCodec.chunk(body)
    }

    /**
     * Commits the local sent row and its sync copy together. Call ONLY after the contact send was
     * accepted: a failed send never reaches this method, so no copy exists for it. A copy that cannot
     * be built (for example an over-bound body) is counted and skipped; the row is still stored.
     * Returns true when the row was newly inserted.
     */
    fun recordSent(message: DesktopMessage, target: String, payload: RelayPayload): Boolean {
        val wires = try {
            buildWires(target, payload)
        } catch (e: IllegalArgumentException) {
            encodeFailures++
            emptyList()
        }
        return store.enqueueSent(message, wires, clock())
    }

    /** Posts queued copies in order. Stops at the first transient failure so ordering and retry state survive. */
    suspend fun flush(limit: Int = 50): FlushReport = flushLock.withLock {
        var posted = 0
        var duplicates = 0
        var dropped = 0
        var error: String? = null
        for (entry in store.outbox(limit)) {
            // Age applies to EVERY entry, file copies included. A file copy older than the server's
            // 24 h window can never be accepted again, so keeping it at the head only wedges the lane.
            if (clock() - entry.createdAt > maxAgeMs) {
                if (entry.mediaDigests.isNotEmpty()) (store as? DesktopMessageStore)?.dropFileSync(entry.wire)
                store.outboxDone(entry.id)
                dropped++
                error = "expired: ${if (entry.mediaDigests.isEmpty()) "text" else "file"} SENT copy dropped"
                continue
            }
            // expires is computed at post time. Signing it from createdAt (an earlier attempt) made any
            // retry after the server window a permanent 400; the signature is on the Nostr auth header,
            // which is fresh on every POST, so byte-identical envelopes buy nothing.
            when (val result = http.post(SyncWire.envelope(entry.wire, 1, clock(), entry.mediaDigests))) {
                is SyncHttpResult.Ok -> {
                    store.outboxDone(entry.id)
                    (store as? DesktopMessageStore)?.refreshFileSyncPublication()
                    if (result.value) duplicates++ else posted++
                }
                is SyncHttpResult.Rejected -> {
                    // A file copy gets ONE retry with a fresh expiry, then it is dropped: a permanently
                    // rejected file must never block the text copies queued behind it (it used to break
                    // the whole lane forever). The file keeps syncPublished = false.
                    if (entry.mediaDigests.isNotEmpty() && entry.attempts == 0) {
                        store.outboxAttempt(entry.id)
                        continue
                    }
                    if (entry.mediaDigests.isNotEmpty()) (store as? DesktopMessageStore)?.dropFileSync(entry.wire)
                    store.outboxDone(entry.id)
                    dropped++
                    error = if (entry.mediaDigests.isEmpty()) "rejected: ${result.reason}" else "file SENT rejected"
                }
                is SyncHttpResult.Retry -> {
                    store.outboxAttempt(entry.id)
                    error = result.reason
                    break
                }
            }
        }
        (store as? DesktopMessageStore)?.refreshFileSyncPublication()
        FlushReport(posted, duplicates, dropped, store.outbox(1000).size, error)
    }
}

// ---- applier ----------------------------------------------------------------------------------------

data class ApplyReport(
    val fetched: Int = 0,
    val inserted: Int = 0,
    val merged: Int = 0,
    val ownOrigin: Int = 0,
    val otherKind: Int = 0,
    val malformed: Int = 0,
    val unreadable: Int = 0,
    val pendingParts: Int = 0,
    val unknownContact: Int = 0,
    val cursor: Long = 0,
    val acked: Boolean = false,
    /** Transient failure that stopped the run (cursor stays where the last commit left it). */
    val error: String? = null,
)

class SentSyncApplier(
    private val ownDeviceId: String,
    private val ownPubkeyHex: String,
    private val http: SyncHttp,
    private val store: SyncStore,
    /** Opens a `TYPE:ENC` body with our own key; null if it is not for us. Plain bodies never reach it. */
    private val openBody: (String) -> String? = { null },
    /** True if [target] (contact pubkey or group conversation id) already has a conversation. */
    private val knownConversation: (String) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = 8,
    private val maxPages: Int = 64,
) {
    private val runLock = Mutex()

    private sealed interface Outcome {
        data class Apply(val message: DesktopMessage, val placeholder: Boolean, val syncId: String?) : Outcome
        data object Ignored : Outcome
        data object OwnOrigin : Outcome
        data object OtherKind : Outcome
        data class Malformed(val syncId: String? = null) : Outcome
        data object Unreadable : Outcome
        data class Pending(val syncId: String) : Outcome
    }

    /**
     * One catch-up cycle. Events are handled strictly in cursor order.
     *
     * Cursor rules: the local cursor is persisted in the SAME transaction as the message it covers;
     * a skipped event (own origin, malformed, other kind) only lets the cursor pass once everything
     * before it is resolved; the first part of a still-incomplete multi-part event holds the cursor
     * just before itself, so a restart re-fetches the parts. The server ack is sent last, from the
     * committed local cursor, never from what was merely fetched.
     */
    suspend fun runOnce(): ApplyReport = runLock.withLock {
        var committed = store.syncCursor()
        var after = committed
        var fetched = 0; var inserted = 0; var merged = 0; var own = 0; var other = 0
        var malformed = 0; var unreadable = 0; var unknown = 0
        var error: String? = null
        val reassembler = SyncReassembler()
        val pendingFirst = LinkedHashMap<String, Long>() // sync id -> cursor of its first part

        fun safe(upTo: Long): Long = pendingFirst.values.minOrNull()?.let { minOf(upTo, it - 1) } ?: upTo

        pages@ for (page in 0 until maxPages) {
            val listed = http.list(after, pageSize)
            val body = when (listed) {
                is SyncHttpResult.Ok -> listed.value
                is SyncHttpResult.Retry -> { error = listed.reason; break@pages }
                is SyncHttpResult.Rejected -> { error = "rejected: ${listed.reason}"; break@pages }
            }
            var lastResolved = after
            for (event in body.events) {
                if (event.cursor <= after) { malformed++; continue } // a server answering backwards is ignored
                fetched++
                val (result, partId) = process(event, reassembler)
                // Whatever the verdict, a finished (or rejected) multi-part event no longer holds the cursor.
                if (result !is Outcome.Pending) partId?.let { pendingFirst.remove(it) }
                when (val outcome = result) {
                    is Outcome.Apply -> {
                        outcome.syncId?.let { pendingFirst.remove(it) }
                        try {
                            val target = maxOf(committed, safe(event.cursor))
                            when (store.commitSync(outcome.message, target)) {
                                SyncCommit.INSERTED -> inserted++
                                SyncCommit.MERGED -> merged++
                                SyncCommit.NONE -> Unit
                            }
                            committed = target
                            if (outcome.placeholder) unknown++
                        } catch (e: Exception) {
                            error = "commit failed"
                            break@pages
                        }
                    }
                    is Outcome.Pending -> pendingFirst.putIfAbsent(outcome.syncId, event.cursor)
                    Outcome.OwnOrigin -> own++
                    Outcome.OtherKind -> other++
                    is Outcome.Malformed -> { malformed++; outcome.syncId?.let { pendingFirst.remove(it) } }
                    Outcome.Unreadable -> unreadable++
                    Outcome.Ignored -> Unit
                }
                lastResolved = event.cursor
            }
            after = maxOf(after, body.cursor, lastResolved)
            val target = safe(lastResolved)
            if (target > committed) {
                try {
                    store.commitSync(null, target)
                    committed = target
                } catch (e: Exception) {
                    error = "commit failed"
                    break@pages
                }
            }
            if (!body.more || body.events.isEmpty()) break
        }

        var acked = false
        if (committed > lastAcked) {
            when (http.ack(committed)) {
                is SyncHttpResult.Ok -> { acked = true; lastAcked = committed }
                else -> Unit // the local cursor is the truth; the next cycle acks again
            }
        }
        ApplyReport(fetched, inserted, merged, own, other, malformed, unreadable, pendingFirst.size, unknown, committed, acked, error)
    }

    @Volatile private var lastAcked = 0L

    /** Returns the verdict and, for a part-based event, its sync id. */
    private fun process(event: SyncEventDto, reassembler: SyncReassembler): Pair<Outcome, String?> {
        var partId: String? = null
        val outcome = judge(event, reassembler) { partId = it }
        return outcome to partId
    }

    private fun judge(event: SyncEventDto, reassembler: SyncReassembler, onPart: (String) -> Unit): Outcome {
        if (event.dataBase64.length > SyncCodec.MAX_ENCODED_BYTES) return Outcome.Malformed()
        val bytes = runCatching { Base64.getDecoder().decode(event.dataBase64) }.getOrNull() ?: return Outcome.Malformed()
        if (bytes.size > SyncCodec.MAX_RAW_BYTES) return Outcome.Malformed()
        if (bytes.size.toLong() != event.total || SyncWire.sha256Hex(bytes) != event.hash) return Outcome.Malformed()
        val wire = String(bytes, Charsets.UTF_8)
        var decoded = SyncCodec.decode(wire) as? SyncResult.Ok ?: return Outcome.Malformed()
        var body = decoded.body
        var partSyncId: String? = null
        if (body is SyncBody.Part) {
            partSyncId = body.syncId
            onPart(body.syncId)
            when (val r = reassembler.offer(body, clock())) {
                is ReassemblyResult.Pending, is ReassemblyResult.Duplicate -> return Outcome.Pending(body.syncId)
                is ReassemblyResult.Rejected -> { reassembler.discard(body.syncId); return Outcome.Malformed(body.syncId) }
                is ReassemblyResult.Complete -> {
                    decoded = SyncCodec.decode(r.body) as? SyncResult.Ok ?: return Outcome.Malformed(partSyncId)
                    body = decoded.body
                    if (body is SyncBody.Part) return Outcome.Malformed(partSyncId)
                }
            }
        }
        if (body.origin == ownDeviceId) return Outcome.OwnOrigin
        val sent = body as? SyncBody.Sent ?: return Outcome.OtherKind

        val payload = runCatching {
            PayloadCodec.decode(String(Base64.getDecoder().decode(sent.payloadBase64), Charsets.UTF_8))
        }.getOrNull() ?: return Outcome.Malformed()
        val text = if (SmsMessageParser.parseEncryptedPayload(payload.body) != null) openBody(payload.body) ?: return Outcome.Unreadable else payload.body
        val placeholder = !knownConversation(sent.target)
        return Outcome.Apply(
            DesktopMessage(
                id = payload.id,
                conversationId = sent.target,
                sender = ownPubkeyHex,
                body = text,
                ts = payload.ts,
                outgoing = true,
            ),
            placeholder,
            partSyncId,
        )
    }
}

// ---- background loop ----------------------------------------------------------------------------------

/**
 * Runs apply + flush while the app is open: every [intervalMs], right after a send ([kick]) and on
 * start. The transport has no sync hint frame, so this polls; one signed GET per cycle.
 */
class DesktopSyncService(
    private val publisher: SentSyncPublisher,
    private val applier: SentSyncApplier,
    private val onApplied: (ApplyReport) -> Unit = {},
    private val intervalMs: Long = 15_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    @Volatile var lastApply: ApplyReport? = null
        private set
    @Volatile var lastFlush: FlushReport? = null
        private set

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                cycle()
                withTimeoutOrNull(intervalMs) { kicks.receive() }
            }
        }
    }

    fun kick() {
        kicks.trySend(Unit)
    }

    suspend fun cycle() {
        runCatching { publisher.flush() }.onSuccess { lastFlush = it }
        runCatching { applier.runOnce() }.onSuccess {
            lastApply = it
            if (it.inserted > 0) onApplied(it)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
