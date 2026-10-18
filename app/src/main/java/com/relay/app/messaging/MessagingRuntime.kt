package com.relay.app.messaging

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide engine: keeps the transport connected, feeds incoming messages to the
 * [IncomingMessageHandler], and drains the outbox whenever there is something due and the transport
 * is online. Started once from the Application (and kept alive in the background by
 * [ConnectionService]). All entry points are idempotent.
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

    /** Runs [block] on the runtime's IO scope, e.g. a media download that must not stall the receive loop. */
    fun launchIo(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "background task failed", e)
            }
        }
    }

    /**
     * The app is in front of the user: a transport waiting out a back-off (e.g. after being refused
     * before its key was allowlisted) tries again now instead of minutes later.
     */
    fun onForeground(context: Context) {
        if (!started) return ensureStarted(context)
        scope.launch { Transports.get(context.applicationContext).start() }
    }

    fun ensureStarted(context: Context) {
        if (com.relay.app.calculator.CalculatorOnly.enabled(context)) return
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
            PresenceSignals.load(appContext)
            transport.start()
        }

        scope.launch {
            val handler = IncomingMessageHandler(appContext)
            transport.incoming.collect { envelope ->
                try {
                    // Only now may a transport drop its durable copy; a failure leaves it to be offered again.
                    if (handler.handle(envelope)) envelope.onHandled?.invoke()
                } catch (e: Exception) {
                    // One bad message must never stop the receive loop.
                    Log.e(TAG, "handling incoming message failed", e)
                }
            }
        }

        scope.launch {
            // A reconnect is the moment queued messages can finally go out.
            transport.status.collect {
                if (it == TransportStatus.ONLINE) {
                    kick()
                    com.relay.app.media.ProfilePhotoSync.syncPending(appContext)
                    com.relay.app.groups.GroupMedia.retryPending(appContext)
                }
            }
        }

        scope.launch {
            while (true) {
                runCatching { MessageRetention.sweep(appContext) }.onFailure { Log.e(TAG, "retention sweep failed", it) }
                delay(60_000L)
            }
        }
        scope.launch {
            while(true) {
                runCatching { PresenceSignals.heartbeat(appContext) }.onFailure { Log.w(TAG,"activity lease failed",it) }
                delay(20_000L)
            }
        }
        watchNetwork(appContext, transport)

        com.relay.app.p2p.P2pListener.ensureListening(appContext)

        scope.launch {
            while (true) {
                runCatching { com.relay.app.media.PendingAttachments.drain(appContext) }
                    .onFailure { Log.e(TAG, "attachment queue failed", it) }
                delay(1_000L)
            }
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
                // A queued message wakes the worker immediately.
            }
        }
    }

    /**
     * After the default network is lost and a (possibly different) one appears, existing relay
     * sockets are dead but may not notice for a long time, so force a fresh connection. The initial
     * "available" callback at registration is ignored (nothing was lost yet).
     */
    suspend fun shutdown(context: Context) {
        scope.cancel()
        withContext(Dispatchers.IO) { com.relay.app.transport.Transports.get(context).stop() }
    }

    private fun watchNetwork(context: Context, transport: Transport) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        var lost = false
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    lost = true
                }

                override fun onAvailable(network: Network) {
                    if (!lost) return
                    lost = false
                    scope.launch { transport.reconnect() }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "network watcher unavailable: ${e.message}")
        }
    }
}
