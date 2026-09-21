package com.relay.app.transport.nostr

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import rust.nostr.sdk.Keys

/**
 * This install's Nostr identity (secp256k1). It is the stable network address ("npub") of the user.
 *
 * Android Keystore has no secp256k1 support, so the secret key is generated in software and stored
 * in EncryptedSharedPreferences, whose encryption key is wrapped by a Keystore master key — the same
 * pattern [com.relay.app.data.db.RelayDbPassphrase] already uses. The file is excluded from cloud
 * backup and device transfer (see backup_rules.xml / data_extraction_rules.xml); like all
 * Keystore-wrapped material it does not survive a restore to another device, so a new phone means
 * a new identity and re-pairing by QR.
 */
object NostrIdentity {

    private const val PREF_FILE = "relay_nostr_identity"
    private const val KEY_SECRET_HEX = "nostr_secret_hex"

    @Volatile private var cached: Keys? = null

    fun keys(context: Context): Keys {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = prefs(context.applicationContext)
            val existing = prefs.getString(KEY_SECRET_HEX, null)
            val keys = if (existing != null) {
                Keys.parse(existing)
            } else {
                // Generate and persist under the lock so two first-time callers can't each mint a
                // different identity (same race RelayDbPassphrase guards against).
                val fresh = Keys.generate()
                prefs.edit().putString(KEY_SECRET_HEX, fresh.secretKey().toHex()).commit()
                fresh
            }
            cached = keys
            return keys
        }
    }

    /** 64-char lowercase hex public key: how contacts are addressed on the wire and in the DB. */
    fun publicKeyHex(context: Context): String = keys(context).publicKey().toHex()

    /** Human-shareable bech32 form (`npub1...`). */
    fun npub(context: Context): String = keys(context).publicKey().toBech32()

    private fun prefs(appContext: Context) = EncryptedSharedPreferences.create(
        appContext,
        PREF_FILE,
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
