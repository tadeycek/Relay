package com.relay.app.transport.outbox

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Slow recipients cannot block unrelated chats; order within each recipient is preserved. */
object OutboxDispatch {
    suspend fun <T> run(entries: List<T>, recipient: (T) -> String, deliver: suspend (T) -> Int): Int = coroutineScope {
        val slots = Semaphore(4)
        entries.groupBy(recipient).values.map { batch -> async {
            slots.withPermit { var count = 0; for (entry in batch) count += deliver(entry); count }
        } }.awaitAll().sum()
    }
}
