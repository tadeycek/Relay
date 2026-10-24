package com.relay.app.calls

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps the process alive for the duration of a call with the `phoneCall` + `microphone` foreground
 * types (WP13 step 2).
 *
 * Scope note (recorded in `linux-port/evidence/WP13.md`): this pass does **not** register a
 * `ConnectionService`/`CallsManager` self-managed call through `androidx.core:core-telecom`. The
 * dependency is not on the classpath and Telecom registration needs a `PhoneAccount` plus a real device to
 * verify; this service plus audio focus, the CallStyle notification and the proximity lock are the
 * documented simpler equivalent. Self-managed calls are also what keeps the system call log empty.
 */
class CallForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)
        val name = intent?.getStringExtra(EXTRA_PEER_NAME)
        val notification = CallNotifier.ongoing(this, name, callId ?: "")
        val started = if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(CallNotifier.NOTIFICATION_ID, notification, FOREGROUND_TYPES)
                true
            } catch (e: Exception) {
                // RECORD_AUDIO not granted yet: run as a data-sync-less call service without the mic type.
                Log.w(TAG, "couldn't start with the microphone type: ${e.message}")
                runCatching {
                    startForeground(CallNotifier.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                }.isSuccess
            }
        } else {
            startForeground(CallNotifier.NOTIFICATION_ID, notification)
            true
        }
        if (!started) stopSelf(startId)
        holdCpu()
        return START_NOT_STICKY
    }

    private fun holdCpu() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:call").apply {
            setReferenceCounted(false)
            acquire(MAX_MS)
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PEER_NAME = "peer_name"
        private const val TAG = "RelayCallService"
        private const val MAX_MS = 2 * 60 * 60 * 1000L

        private val FOREGROUND_TYPES: Int = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }

        /** Never throws: Android may refuse a background foreground-service start, and ringing still works. */
        fun start(context: Context, callId: String, peerName: String?) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_PEER_NAME, peerName)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "foreground service refused: ${e.message}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
        }
    }
}
