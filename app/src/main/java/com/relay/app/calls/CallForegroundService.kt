package com.relay.app.calls

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive for the duration of a call (WP13 step 2). It starts as `phoneCall` while ringing
 * and is re-entered as `phoneCall|microphone` after accept; the choice is [CallServicePolicy]'s.
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
        val stage = if (intent?.getStringExtra(EXTRA_STAGE) == STAGE_IN_CALL) ServiceStage.IN_CALL else ServiceStage.RINGING
        val notification = CallNotifier.ongoing(this, name, callId ?: "")
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val types = CallServicePolicy.typesFor(Build.VERSION.SDK_INT, stage, micGranted).fold(0) { acc, t ->
            acc or when (t) {
                FgsType.PHONE_CALL -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                FgsType.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        val started = if (types != 0) {
            try {
                startForeground(CallNotifier.NOTIFICATION_ID, notification, types)
                true
            } catch (e: Exception) {
                // Android 14 can refuse the microphone type from the background: keep running as phoneCall.
                Log.w(TAG, "couldn't start with types=$types: ${e.message}")
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
        const val EXTRA_STAGE = "stage"
        const val STAGE_RINGING = "ringing"
        const val STAGE_IN_CALL = "in_call"
        private const val TAG = "RelayCallService"
        private const val MAX_MS = 2 * 60 * 60 * 1000L

        /** Never throws: Android may refuse a background foreground-service start, and ringing still works. */
        fun start(context: Context, callId: String, peerName: String?, stage: ServiceStage) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_CALL_ID, callId)
                .putExtra(EXTRA_PEER_NAME, peerName)
                .putExtra(EXTRA_STAGE, if (stage == ServiceStage.IN_CALL) STAGE_IN_CALL else STAGE_RINGING)
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
