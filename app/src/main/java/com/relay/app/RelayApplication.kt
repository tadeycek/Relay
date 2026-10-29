package com.relay.app

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.relay.app.messaging.MessagingRuntime
import com.relay.app.messaging.PollWorker
import com.relay.app.sms.KeyRotationReceiver
import com.relay.app.sms.PinExpiryReceiver

class RelayApplication : Application(), coil.ImageLoaderFactory {
    init {
        // The shared :core module has no BuildConfig; hand it the build-time server URL before any component runs.
        com.relay.app.transport.mailbox.MailboxConfig.install(BuildConfig.MAILBOX_URL)
    }

    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this).components {
        if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory())
        else add(coil.decode.GifDecoder.Factory())
    }.build()


    override fun onCreate() {
        super.onCreate()
        if (com.relay.app.calculator.CalculatorOnly.enabled(this)) {
            com.relay.app.calculator.CalculatorOnly.clean(this)
            return
        }
        com.relay.app.messaging.ConnectionService.stop(this)
        com.relay.app.messaging.LauncherBadges.disable(this)
        // Remove view copies left behind if the previous process died while showing media.
        java.io.File(cacheDir, "private-media-view").listFiles()?.forEach { it.delete() }
        schedulePinExpiryAlarm()
        scheduleKeyRotationCheck()
        // Connect the internet transport, receive messages and drain the outbox for as long as the
        // process lives. Android may suspend/kill it; ordinary periodic work reconnects later.
        MessagingRuntime.ensureStarted(this)
        // Group calls are part of the app graph from process start: inbound call frames need the
        // coordinator, a live session needs the foreground service, and a cold start must purge any
        // persisted banner before it can be mistaken for a live call.
        com.relay.app.calls.group.GroupCallController.initialize(this)
        PollWorker.schedule(this)
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
