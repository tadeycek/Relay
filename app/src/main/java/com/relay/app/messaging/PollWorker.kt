package com.relay.app.messaging

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.Transports
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Safety net for when the foreground service is off or the OS killed it: every ~15 minutes (the
 * platform minimum for periodic work) the process is woken, the runtime is started, and it gets a
 * short window to connect and pull anything that arrived. Delivery through this path is delayed by
 * up to a quarter of an hour, which is why the foreground service is the primary mechanism.
 */
class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        MessagingRuntime.ensureStarted(applicationContext)
        val transport = Transports.get(applicationContext)
        // Give the connection a chance to come up, then a short window for stored events to stream in.
        withTimeoutOrNull(CONNECT_WAIT_MS) {
            while (transport.status.value != TransportStatus.ONLINE) delay(500)
        }
        if (transport.status.value == TransportStatus.ONLINE) {
            MessagingRuntime.kick()
            delay(DRAIN_WINDOW_MS)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "relay_poll"
        private const val CONNECT_WAIT_MS = 30_000L
        private const val DRAIN_WINDOW_MS = 15_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
