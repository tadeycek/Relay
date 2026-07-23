package com.relay.app.mms

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.FileProvider
import com.relay.app.crypto.RelayCrypto
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

    /**
     * Sends [mediaFile] as an MMS attachment. If [recipientPublicKey] is known, the media bytes
     * are hybrid-encrypted (E2E, same identity keys as text messages) before being embedded in the
     * PDU, prefixed with [RelayCrypto.MMS_ENC_MAGIC] so the receiving Relay install knows to
     * decrypt rather than treat the bytes as a raw image/video.
     */
    fun sendMms(
        context: Context,
        phone: String,
        mediaFile: File,
        mimeType: String,
        textBody: String?,
        recipientPublicKey: String? = null,
    ): Boolean = try {
        val transId = UUID.randomUUID().toString().take(8)

        val rawMediaBytes = mediaFile.readBytes()
        val mediaBytes = if (recipientPublicKey != null) {
            val ciphertext = RelayCrypto.encryptTo(recipientPublicKey, rawMediaBytes)
            if (ciphertext != null) RelayCrypto.MMS_ENC_MAGIC + ciphertext else rawMediaBytes
        } else {
            rawMediaBytes
        }

        val pdu = MmsPduBuilder.build(phone, textBody, mediaBytes, mimeType, transId)

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
