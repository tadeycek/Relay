package com.relay.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.Outgoing
import com.relay.app.util.RelayPreferences
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fired on a daily alarm (see RelayApplication.scheduleKeyRotationAlarm); actually rotates the
 * device's encryption identity key only once [ROTATION_INTERVAL_MS] has elapsed since the last
 * rotation. Kept as a cheap no-op check the rest of the time rather than scheduling a literal
 * 30-day alarm, since AlarmManager wake-ups aren't guaranteed to survive that long without the
 * app running (reboots, battery optimization, etc).
 */
class KeyRotationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = RelayPreferences(context)
        val last = prefs.lastKeyRotationAt
        val now = System.currentTimeMillis()
        if (last == 0L) {
            // First time this alarm has fired for this install: start the rotation clock from
            // here (the identity key itself was just freshly generated) instead of immediately
            // "rotating" a brand-new key.
            prefs.lastKeyRotationAt = now
            return
        }
        if (now - last < ROTATION_INTERVAL_MS) return

        // Rotation does a Keystore-backed key generation/signing plus a DB read and a loop of SMS
        // sends to every paired contact — real work, offloaded off the receiver's (main) thread.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                rotateAndBroadcast(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ROTATION_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

        /** Rotates this device's encryption key and pushes the new key to every already-paired
         *  contact. Used by the periodic alarm and by the manual "Rotate encryption key now"
         *  action in Settings. Returns true if the rotation itself succeeded (broadcast delivery
         *  to individual contacts is best-effort — a contact who's offline just gets the new key
         *  next time they receive any PUBKEY message, e.g. via [SmsReceiver]'s own handshake). */
        fun rotateAndBroadcast(context: Context): Boolean {
            val result = RelayCrypto.rotateIdentityKey(context) ?: return false

            val db = RelayDbHelper(context)
            val contactRepo = ContactRepository(db)
            val paired = kotlinx.coroutines.runBlocking { contactRepo.getAllContacts() }
                .filter { it.publicKey != null }

            val wireBody = SmsMessageParser.formatPublicKey(
                base64Key = result.newPublicKeyBase64,
                rotationSignatureBase64 = result.signatureBase64,
            )
            for (contact in paired) {
                if (contact.canUseInternetTransport) {
                    // Not sealed with the (about to be replaced) inner key: the rotation announcement
                    // carries its own signature from the long-term signing key, and the Nostr seal
                    // already authenticates the sender. Queued durably, so an offline contact gets it later.
                    Outgoing.enqueue(context, contact, wireBody, seal = false)
                } else {
                    SmsSender.sendSms(context, contact.phone, wireBody)
                }
            }
            return true
        }
    }
}
