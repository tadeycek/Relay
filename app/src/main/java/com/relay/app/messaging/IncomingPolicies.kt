package com.relay.app.messaging

/**
 * Small pure policies for incoming internet messages, kept free of Android types so they can be
 * unit-tested on the JVM.
 */
object TimestampPolicy {

    const val MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * The sender's own timestamp is the only ordering signal that survives relays (NIP-17 fuzzes the
     * wrapper's `created_at` by up to two days), but it is attacker-controlled: a far-future value
     * would pin a message to the bottom of the chat forever and a huge age could reorder history.
     * Anything outside a plausible window falls back to the local receive time.
     */
    fun resolve(senderTs: Long, now: Long): Long =
        if (senderTs in (now - MAX_AGE_MS)..(now + MAX_FUTURE_SKEW_MS)) senderTs else now

    /** A location request older than this is no longer actionable (the person asking has moved on). */
    const val STALE_REQUEST_MS = 60 * 60 * 1000L

    fun isStaleRequest(senderTs: Long, now: Long): Boolean = now - senderTs > STALE_REQUEST_MS
}

/** At most one event per [windowMs] per key. Replaces the ad-hoc maps SmsReceiver used. */
class CooldownLimiter(private val windowMs: Long) {
    private val last = HashMap<Long, Long>()

    @Synchronized
    fun allow(key: Long, now: Long): Boolean {
        val prev = last[key]
        if (prev != null && now - prev < windowMs) return false
        last[key] = now
        return true
    }
}

/** At most [maxEvents] in any sliding window of [windowMs]; guards against a flood of unknown senders. */
class SlidingWindowLimiter(private val maxEvents: Int, private val windowMs: Long) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (times.size >= maxEvents) return false
        times.addLast(now)
        return true
    }
}
