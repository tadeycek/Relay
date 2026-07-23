package com.relay.app.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsSender {

    // Conservative threshold: encrypted/base64 payloads (TYPE:ENC|CT:..., TYPE:PUBKEY|KEY:...)
    // routinely exceed a single 160-char GSM-7 segment, so always split via SmsManager rather
    // than relying on sendTextMessage's implicit (and, for long bodies, silently truncating) limit.
    private const val SINGLE_SEGMENT_SAFE_LENGTH = 140

    fun sendSms(context: Context, phone: String, body: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            if (body.length > SINGLE_SEGMENT_SAFE_LENGTH) {
                val parts = smsManager.divideMessage(body)
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, body, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e("SmsSender", "Failed to send SMS to $phone", e)
            false
        }
    }
}
