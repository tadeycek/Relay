package com.relay.app.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.util.Log

/** The Android side of tap-to-pair: what the tag serves, and turning reader mode on and off. */
object NfcPairing {

    private const val TAG = "NfcPairing"

    /** The contact code to serve to a reading phone; set only while the code screen is showing. */
    @Volatile
    var payload: String? = null

    enum class Availability { NONE, OFF, ON }

    fun availability(context: Context): Availability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return Availability.NONE
        return if (adapter.isEnabled) Availability.ON else Availability.OFF
    }

    fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * Makes this app the one that answers taps while it is in front, so another app's card service cannot
     * win the tap. Returns whether NFC card emulation is available at all.
     */
    fun startServing(activity: Activity): Boolean = runCatching {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        val emulation = CardEmulation.getInstance(adapter)
        emulation.setPreferredService(activity, ComponentName(activity, PairingCardService::class.java))
        true
    }.getOrElse {
        Log.w(TAG, "could not prefer the pairing service", it)
        false
    }

    fun stopServing(activity: Activity) {
        runCatching {
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
            CardEmulation.getInstance(adapter).unsetPreferredService(activity)
        }
        payload = null
    }

    /**
     * Reads a contact code from a phone held against this one. [onCode] runs on the main thread with the
     * text of the code, exactly like a QR scan would deliver it.
     */
    fun startReading(activity: Activity, onCode: (String) -> Unit): Boolean = runCatching {
        val adapter = NfcAdapter.getDefaultAdapter(activity)?.takeIf { it.isEnabled } ?: return false
        adapter.enableReaderMode(
            activity,
            { tag ->
                val code = readTag(tag)
                if (code != null) activity.runOnUiThread { onCode(code) }
            },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        true
    }.getOrElse {
        Log.w(TAG, "could not start reader mode", it)
        false
    }

    fun stopReading(activity: Activity) {
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
    }

    private fun readTag(tag: android.nfc.Tag): String? {
        val iso = IsoDep.get(tag) ?: return null
        return try {
            iso.connect()
            iso.timeout = 3000
            Type4Tag.readContactCode { iso.transceive(it) }
        } catch (e: Exception) {
            Log.i(TAG, "tag read failed: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { iso.close() }
        }
    }
}
