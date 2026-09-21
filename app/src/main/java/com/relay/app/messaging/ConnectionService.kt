package com.relay.app.messaging

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.relay.app.MainActivity
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.Transports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process (and therefore the relay connection) alive while the app is closed, so messages
 * arrive and notify in the background. Nostr relays cannot push to a sleeping phone, so this is the
 * mechanism for closed-app delivery until an optional notification bridge exists (see
 * rebuild/04-architecture.md, "Getting the message to a closed app").
 *
 * Costs battery and shows a permanent, low-priority notification — the user can turn it off in
 * Settings, in which case messages are only fetched when the app opens or the periodic safety-net
 * job runs.
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startInForeground(buildNotification(this, TransportStatus.CONNECTING))
        MessagingRuntime.ensureStarted(this)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        scope.launch {
            Transports.get(this@ConnectionService).status.collect { status ->
                nm.notify(NOTIFICATION_ID, buildNotification(this@ConnectionService, status))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "ConnectionService"
        const val CHANNEL_ID = "relay_connection"
        private const val NOTIFICATION_ID = 1001

        /** Starts the service. Safe to call repeatedly; swallows the OS refusing a background start. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
            } catch (e: Exception) {
                // Android 12+ throws if the app is not in a state that may start a foreground service.
                Log.w(TAG, "could not start connection service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_MIN)
                    .apply {
                        description = "Shown while Relay stays connected to receive messages"
                        setShowBadge(false)
                    }
            )
        }

        internal fun statusText(status: TransportStatus): String = when (status) {
            TransportStatus.ONLINE -> "Connected - waiting for messages"
            TransportStatus.CONNECTING -> "Connecting..."
            TransportStatus.OFFLINE -> "Offline - will retry"
            TransportStatus.STOPPED -> "Stopped"
            TransportStatus.WAITING_FOR_TOR -> "Waiting for Tor (start Orbot)"
        }

        private fun buildNotification(context: Context, status: TransportStatus): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Relay")
                .setContentText(statusText(status))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open)
                .build()
        }
    }
}
