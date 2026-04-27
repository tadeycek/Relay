package com.relay.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.preference.PreferenceManager
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
}
