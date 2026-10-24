package com.relay.app.transport

/** Reconnect schedule for a dropped relay connection. Pure so it can be unit-tested. */
object Backoff {

    private const val BASE_MS = 1_000L
    private const val CAP_MS = 60_000L

    /** 2 s, 4 s, 8 s ... capped at 60 s, for the Nth consecutive failure (N >= 1). */
    fun reconnectDelayMs(failures: Int): Long =
        (BASE_MS shl failures.coerceIn(1, 6)).coerceAtMost(CAP_MS)
}
