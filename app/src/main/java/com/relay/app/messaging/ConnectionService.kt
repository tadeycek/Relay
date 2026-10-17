package com.relay.app.messaging

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Retired continuous service. No foreground-service start and no service-status notification.
 * The process connection and ordinary WorkManager polling handle message reception. */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); stopSelf() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
    companion object {
        fun onAppForeground(context: Context) = stop(context)
        fun onAppBackground(context: Context) = stop(context)
        fun start(context: Context) = stop(context)
        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(1001)
            nm.deleteNotificationChannel("relay_connection")
        }
    }
}
