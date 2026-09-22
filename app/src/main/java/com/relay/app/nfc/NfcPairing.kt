package com.relay.app.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Android side of tap-to-pair: what the tag serves, and turning reader mode on and off. */
object NfcPairing {

    private const val TAG = "NfcPairing"

    /** The contact code to serve to a reading phone; set only while the code screen is showing. */
    @Volatile
    var payload: String? = null

    private val _discoveredCode = MutableStateFlow<String?>(null)

    /**
     * A contact code that arrived through the system's own NFC tag dispatch rather than our reader
     * mode — see [handleNdefIntent]. The scan screen consumes it exactly like a reader-mode read and
     * clears it back to null.
     */
    val discoveredCode: StateFlow<String?> = _discoveredCode.asStateFlow()

    fun consumeDiscoveredCode() {
        _discoveredCode.value = null
    }

    /**
     * A safety net for reader mode: if this phone was not actively reading at the exact moment of the
     * tap (a brief window right after opening the screen, or a slow first attempt), Android falls back
     * to its own default handler for an unclaimed tag instead of quietly failing — and without this,
     * that handler is the system's "Tag" app, not Relay. Declaring this MIME type in the manifest makes
     * Relay a candidate for that fallback dispatch too. Returns whether the intent held a contact code.
     */
    fun handleNdefIntent(intent: Intent?): Boolean {
        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return false
        val messages = intent.parcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return false
        for (msg in messages) {
            val ndef = msg as? NdefMessage ?: continue
            for (record in ndef.records) {
                if (record.toMimeType() == Type4Tag.MIME_TYPE) {
                    _discoveredCode.value = String(record.payload, Charsets.UTF_8)
                    return true
                }
            }
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableArrayExtra(name: String): Array<out android.os.Parcelable>? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayExtra(name, NdefMessage::class.java)
        else getParcelableArrayExtra(name)

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
