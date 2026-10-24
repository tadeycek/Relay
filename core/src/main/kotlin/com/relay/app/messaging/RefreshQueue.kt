package com.relay.app.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class RefreshQueue(
    scope: CoroutineScope,
    onFailure: (Exception) -> Unit,
    refresh: suspend () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    refresh()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    onFailure(exception)
                }
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
