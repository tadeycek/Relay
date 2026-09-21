package com.relay.app.messaging

import android.content.Context
import android.util.Log
import com.relay.app.data.repository.OutboxRepository
import com.relay.app.data.repository.SeenPayloadRepository
import com.relay.app.transport.Transport
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.Transports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide engine: keeps the transport connected, feeds incoming messages to the
 * [IncomingMessageHandler], and drains the outbox whenever there is something due and the transport
 * is online. Started once from the Application (and kept alive in the background by the
 * foreground service added in the next phase). All entry points are idempotent.
 */
object MessagingRuntime {

    private const val TAG = "MessagingRuntime"
    private const val MIN_WAIT_MS = 1_000L
    private const val MAX_WAIT_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false

    /** Ask the outbox loop to run now (a message was just queued, or the network came back). */
    fun kick() {
        kicks.trySend(Unit)
    }

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val appContext = context.applicationContext
        val transport: Transport = Transports.get(appContext)

        scope.launch {
            val db = MessagingDb.get(appContext)
            runCatching { SeenPayloadRepository(db).purgeOldSync() }
            transport.start()
        }

        scope.launch {
            val handler = IncomingMessageHandler(appContext)
            transport.incoming.collect { envelope ->
                try {
                    handler.handle(envelope)
                } catch (e: Exception) {
                    // One bad message must never stop the receive loop.
                    Log.e(TAG, "handling incoming message failed", e)
                }
            }
        }

        scope.launch {
            // A reconnect is the moment queued messages can finally go out.
            transport.status.collect { if (it == TransportStatus.ONLINE) kick() }
        }

        scope.launch {
            val worker = OutboxWorker(appContext, transport)
            val outbox = OutboxRepository(MessagingDb.get(appContext))
            while (true) {
                if (transport.status.value == TransportStatus.ONLINE) {
                    try {
                        worker.drain()
                    } catch (e: Exception) {
                        Log.e(TAG, "outbox drain failed", e)
                    }
                }
                val now = System.currentTimeMillis()
                val nextDue = runCatching { outbox.nextDueAtSync() }.getOrNull()
                val wait = ((nextDue ?: (now + MAX_WAIT_MS)) - now).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
                // Sleep until the next entry is due, or until kicked, whichever comes first.
                withTimeoutOrNull(wait) { kicks.receive() }
                delay(50) // coalesce a burst of kicks
            }
        }
    }

}
