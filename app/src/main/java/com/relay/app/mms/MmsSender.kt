package com.relay.app.mms

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object MmsSender {

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    fun sendMms(
        context: Context,
        phone: String,
        mediaFile: File,
        mimeType: String,
        textBody: String?,
    ): Boolean = try {
        val transId = UUID.randomUUID().toString().take(8)
        val pdu = MmsPduBuilder.build(phone, textBody, mediaFile.readBytes(), mimeType, transId)

        val pduDir = File(context.cacheDir, "mms").also { it.mkdirs() }
        val pduFile = File(pduDir, "send_$transId.mms")
        FileOutputStream(pduFile).use { it.write(pdu) }

        val pduUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pduFile,
        )

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        smsManager.sendMultimediaMessage(context, pduUri, null, null, null)
        true
    } catch (e: Exception) {
        Log.e("MmsSender", "Failed to send MMS to $phone", e)
        false
    }
}
