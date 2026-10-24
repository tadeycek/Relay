package com.relay.app.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Enqueue synchronously on the engine thread; a single worker drains each recipient in FIFO order. */
internal class RecipientSignalQueue(
    private val scope: CoroutineScope,
    private val deliver: suspend (String, String) -> Unit,
) {
    private data class Item(val wire: String?, val barrier: CompletableDeferred<Unit>? = null)
    private val channels = HashMap<String, Channel<Item>>()

    @Synchronized private fun channel(recipient: String): Channel<Item> = channels.getOrPut(recipient) {
        Channel<Item>(64).also { channel ->
            scope.launch {
                for (item in channel) {
                    if (item.barrier != null) { item.barrier.complete(Unit); continue }
                    try { deliver(recipient, item.wire!!) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { runCatching { android.util.Log.w("RelayCallSignal", "call signal send failed", e) } }
                }
            }
        }
    }

    fun send(recipient: String, wire: String): Boolean = channel(recipient).trySend(Item(wire)).isSuccess

    suspend fun flush() {
        val barriers = synchronized(this) { channels.values.map { channel ->
            CompletableDeferred<Unit>().also { channel.sendBarrier(it) }
        } }
        barriers.forEach { it.await() }
    }

    private fun Channel<Item>.sendBarrier(barrier: CompletableDeferred<Unit>) {
        check(trySend(Item(null, barrier)).isSuccess)
    }
}
