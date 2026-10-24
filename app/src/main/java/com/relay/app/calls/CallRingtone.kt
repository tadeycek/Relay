package com.relay.app.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.util.Log

/**
 * The audible ring: the system ringtone (looped) for an incoming call, a quiet dial tone while the other
 * side is being called. Idempotent, because `StartRing`/`StopRing` are idempotent effects and `StopRing`
 * may arrive for a call that never rang (`CALL_ENGINE.md`).
 */
class CallRingtone(private val context: Context) {

    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null

    fun start(incoming: Boolean) {
        stop()
        try {
            if (incoming) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val r = RingtoneManager.getRingtone(context, uri) ?: return
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (android.os.Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r.play()
                ringtone = r
            } else {
                // Ringback: Android ships no ringback tone, so a short dial tone stands in for "calling".
                val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 40)
                t.startTone(ToneGenerator.TONE_SUP_DIAL, 1_000)
                tone = t
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not start the ring", e)
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
        tone = null
    }

    private companion object { const val TAG = "RelayCallRingtone" }
}
