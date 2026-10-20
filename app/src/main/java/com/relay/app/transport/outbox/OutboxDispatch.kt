package com.relay.app.transport.outbox

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Per-recipient send lanes. Each recipient's entries go out one at a time and in order, but a slow or
 * unreachable recipient only holds its own lane: work queued for anyone else starts straight away
 * instead of waiting for the slowest send of an earlier round to finish.
 */
class OutboxDispatch(private val scope: CoroutineScope, parallel: Int = 4) {
    private val slots = Semaphore(parallel)
    private val busy = mutableSetOf<String>()

    /** Recipients with a lane in flight. Their entries must not be offered to a second lane. */
    fun busy(): Set<String> = synchronized(busy) { busy.toSet() }

    /**
     * Opens a lane for every recipient in [entries] that doesn't have one yet and returns at once.
     * At most `parallel` entries are being delivered at any moment across all lanes. A lane stops early
     * when [deliver] returns false (a retryable failure: the rest wait for the next round, not a pile of
     * timeouts). [onLaneDone] runs after each lane so the caller can pick up work that arrived meanwhile.
     */
    fun <T> offer(entries: List<T>, recipient: (T) -> String, deliver: suspend (T) -> Boolean, onLaneDone: () -> Unit): Int {
        var opened = 0
        for ((key, lane) in entries.groupBy(recipient)) {
            if (!synchronized(busy) { busy.add(key) }) continue
            opened++
            scope.launch {
                try {
                    for (entry in lane) {
                        val keepGoing = try {
                            slots.withPermit { deliver(entry) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A lane has no caller to rethrow to; an uncaught exception here would crash the app.
                            runCatching { Log.e(TAG, "outbox entry failed", e) }
                            false
                        }
                        if (!keepGoing) break
                    }
                } finally {
                    synchronized(busy) { busy.remove(key) }
                    onLaneDone()
                }
            }
        }
        return opened
    }

    private companion object { const val TAG = "OutboxDispatch" }
}
