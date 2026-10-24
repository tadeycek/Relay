package com.relay.app.messaging

object RetentionPolicy {
    const val LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
    fun admissible(timestamp: Long, now: Long) = !expired(timestamp,now) && timestamp<=now+TimestampPolicy.MAX_FUTURE_SKEW_MS
    fun cutoff(now: Long) = now - LIFETIME_MS
    fun expired(timestamp: Long, now: Long) = timestamp <= cutoff(now)
}
