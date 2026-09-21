package com.relay.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.preference.PreferenceManager
import com.relay.app.messaging.MessagingRuntime
import com.relay.app.sms.KeyRotationReceiver
import com.relay.app.sms.PinExpiryReceiver
import org.osmdroid.config.Configuration
import java.io.File

class RelayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        @Suppress("DEPRECATION")
        Configuration.getInstance().apply {
            load(this@RelayApplication, PreferenceManager.getDefaultSharedPreferences(this@RelayApplication))
            userAgentValue = BuildConfig.APPLICATION_ID
            osmdroidBasePath = getExternalFilesDir(null) ?: filesDir
            osmdroidTileCache = File(cacheDir, "osmdroid")
        }
        schedulePinExpiryAlarm()
        scheduleKeyRotationCheck()
        // Connect the internet transport, receive messages and drain the outbox for as long as the
        // process lives (a foreground service keeps it alive in the background; see Phase 4).
        MessagingRuntime.ensureStarted(this)
    }

    private fun schedulePinExpiryAlarm() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, PinExpiryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 15 * 60 * 1000L,
            15 * 60 * 1000L,
            intent,
        )
    }

    /** Daily no-op-most-days check that actually rotates the E2E identity key once 30 days have
     *  passed (see KeyRotationReceiver) — daily rather than a single long alarm since AlarmManager
     *  wake-ups aren't reliably preserved across reboots/battery optimization for month-long gaps. */
    private fun scheduleKeyRotationCheck() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, KeyRotationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dayMs = 24 * 60 * 60 * 1000L
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + dayMs,
            dayMs,
            intent,
        )
    }
}
