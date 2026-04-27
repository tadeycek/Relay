package com.relay.app.sms

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.util.Log

object SmsSender {

    fun sendSms(context: Context, phone: String, body: String): Boolean {
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phone, null, body, null, null)
            true
        } catch (e: Exception) {
            Log.e("SmsSender", "Failed to send SMS to $phone", e)
            false
        }
    }
}
