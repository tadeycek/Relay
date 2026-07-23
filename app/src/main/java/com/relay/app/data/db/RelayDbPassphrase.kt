package com.relay.app.data.db

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Generates and stores the passphrase used to open the SQLCipher-encrypted
 * relay.db. The passphrase itself lives in EncryptedSharedPreferences, whose
 * encryption key is wrapped by an Android Keystore master key — so the
 * passphrase never sits on disk in cleartext, and (like all Keystore-backed
 * material) doesn't survive a restore to a different device.
 */
object RelayDbPassphrase {

    private const val PREF_FILE = "relay_db_secret"
    private const val KEY_PASSPHRASE = "db_passphrase"

    fun get(context: Context): String {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            appContext,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.getString(KEY_PASSPHRASE, null)?.let { return it }

        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val generated = Base64.encodeToString(bytes, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PASSPHRASE, generated).commit()
        return generated
    }
}
