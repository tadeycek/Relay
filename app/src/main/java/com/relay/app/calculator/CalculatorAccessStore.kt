package com.relay.app.calculator

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** PIN verifier and failure count are Keystore-protected and never backed up. */
class CalculatorAccessStore internal constructor(context: Context, prefFile: String = PREF_FILE) {
    private val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context, prefFile, master,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        // Left by the removed owner recovery: its private recovery key, access reports and diagnostics consent.
        val obsolete = OBSOLETE_KEYS.filter(prefs::contains)
        if (obsolete.isNotEmpty()) prefs.edit().apply { obsolete.forEach(::remove) }.commit()
    }

    @Synchronized fun state() = AccessState(prefs.contains("verifier"), prefs.getInt("failures", 0))

    /** On [AccessResult.WIPE] the caller erases everything ([RelayWipe]); the count is saved first, so a crash can't undo it. */
    @Synchronized fun submit(code: String): AccessResult {
        require(code.matches(Regex("[0-9]{4}")))
        val before = state()
        val digest = verifier(code)
        val matches = prefs.getString("verifier", null)?.let {
            MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), digest.toByteArray(Charsets.UTF_8))
        } ?: false
        val (after, result) = AccessPolicy.submit(before, matches)
        val edit = prefs.edit().putInt("failures", after.failures)
        if (result == AccessResult.ENROLLED) edit.putString("verifier", digest)
        check(edit.commit()) { "Could not save access state" }
        return result
    }

    private fun verifier(code: String): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKey()
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return Base64.encodeToString(mac.doFinal(code.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    companion object {
        private val OBSOLETE_KEYS = listOf(
            "events", "sequence", "lockout_sequence", "device_id", "device_token", "owner_relay_secret",
            "owner_relay_seen", "diagnostics_enabled", "diagnostics_revision", "diagnostics_ack",
        )
        const val PREF_FILE = "calculator_access"
        private const val KEY_ALIAS = "calculator_pin_verifier"
        @Volatile private var instance: CalculatorAccessStore? = null
        fun get(context: Context): CalculatorAccessStore = instance ?: synchronized(this) {
            instance ?: CalculatorAccessStore(context.applicationContext).also { instance = it }
        }
    }
}
