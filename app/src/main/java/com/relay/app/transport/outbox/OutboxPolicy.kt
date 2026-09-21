package com.relay.app.transport.outbox

enum class OutboxState(val dbValue: Int) {
    QUEUED(0),
    SENT(1),
    FAILED(2);

    companion object {
        fun fromDb(value: Int): OutboxState = entries.firstOrNull { it.dbValue == value } ?: QUEUED
    }
}

/** A message waiting to be (re)published. The payload JSON is stored verbatim so retries are byte-identical. */
data class OutboxEntry(
    val id: Long = 0L,
    val contactId: Long,
    val recipientPubkeyHex: String,
    val payloadId: String,
    val payloadJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = createdAt,
    val state: OutboxState = OutboxState.QUEUED,
)

/**
 * Retry schedule for outgoing messages: exponential backoff (5 s doubling up to 15 min), and give
 * up entirely after [GIVE_UP_AFTER_MS]. Pure functions so the schedule is unit-testable.
 */
object OutboxPolicy {

    const val BASE_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 15 * 60 * 1000L
    const val GIVE_UP_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    /** Delay before the next try after [attempts] failed tries (attempts >= 1). */
    fun nextDelayMs(attempts: Int): Long {
        if (attempts <= 0) return 0L
        val shift = (attempts - 1).coerceAtMost(20)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }

    fun isExpired(createdAt: Long, now: Long): Boolean = now - createdAt >= GIVE_UP_AFTER_MS

    fun isDue(entry: OutboxEntry, now: Long): Boolean =
        entry.state == OutboxState.QUEUED && entry.nextAttemptAt <= now
}
