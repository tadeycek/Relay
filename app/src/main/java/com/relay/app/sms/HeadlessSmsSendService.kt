package com.relay.app.sms

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Handles `ACTION_RESPOND_VIA_MESSAGE` — the "quick response" text sent when declining an
 * incoming call. Declaring this (alongside SmsReceiver/MmsReceiver's `*_DELIVER` actions and
 * MainActivity's `ACTION_SENDTO` filter) is one of the components Android requires an app to have
 * before it's even offered as a candidate in the default-SMS-app picker — this isn't a feature
 * Relay otherwise needed, it exists purely for that eligibility check.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val phone = intent.data?.schemeSpecificPart
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!phone.isNullOrBlank() && !text.isNullOrBlank()) {
                val sent = SmsSender.sendSms(applicationContext, phone, text)
                if (!sent) Log.w("HeadlessSmsSendService", "Quick-response send failed for $phone")
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
