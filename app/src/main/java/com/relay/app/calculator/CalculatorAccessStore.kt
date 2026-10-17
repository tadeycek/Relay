package com.relay.app.calculator

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** PIN verifier, failure count, and owner event queue are Keystore-protected and never backed up. */
class CalculatorAccessStore internal constructor(context: Context, prefFile: String = PREF_FILE) {
    private val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context, prefFile, master,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized fun state() = AccessState(prefs.contains("verifier"), prefs.getInt("failures", 0))

    /** Optional phone diagnostics are never enabled by code enrollment or an owner command. */
    @Synchronized fun diagnosticsChoice(): Pair<Long, Boolean> =
        prefs.getLong("diagnostics_revision", 0) to prefs.getBoolean("diagnostics_enabled", false)

    @Synchronized fun setDiagnosticsEnabled(enabled: Boolean) {
        val (revision, current) = diagnosticsChoice()
        if (current == enabled) return
        check(prefs.edit().putBoolean("diagnostics_enabled", enabled)
            .putLong("diagnostics_revision", revision + 1).commit())
    }

    @Synchronized fun diagnosticsPending(): Boolean =
        prefs.getLong("diagnostics_ack", -1) != diagnosticsChoice().first

    @Synchronized fun acknowledgeDiagnostics(revision: Long) {
        // An in-flight opt-in report cannot acknowledge a newer request to stop sharing.
        if (diagnosticsChoice().first == revision) {
            check(prefs.edit().putLong("diagnostics_ack", revision).commit())
        }
    }

    @Synchronized fun submit(code: String): AccessResult {
        require(code.matches(Regex("[0-9]{4}")))
        val before = state()
        if (before.locked) return AccessResult.LOCKED
        val digest = verifier(code)
        val matches = prefs.getString("verifier", null)?.let {
            MessageDigest.isEqual(it.toByteArray(Charsets.UTF_8), digest.toByteArray(Charsets.UTF_8))
        } ?: false
        val (after, result) = AccessPolicy.submit(before, matches)
        val seq = prefs.getLong("sequence", 0) + 1
        val events = pendingEvents()
        val kind = when (result) {
            AccessResult.ENROLLED -> "enrolled"
            AccessResult.UNLOCKED -> "unlocked"
            AccessResult.REJECTED -> "failed"
            AccessResult.LOCKED -> "locked"
        }
        events.put(JSONObject().put("seq", seq).put("kind", kind).put("failures", after.failures))
        val bounded = JSONArray()
        for (i in maxOf(0, events.length() - 100) until events.length()) bounded.put(events.getJSONObject(i))
        val edit = prefs.edit().putInt("failures", after.failures).putLong("sequence", seq)
            .putString("events", bounded.toString())
        if (result == AccessResult.ENROLLED) edit.putString("verifier", digest)
        if (after.locked) edit.putLong("lockout_sequence", seq)
        check(edit.commit()) { "Could not save access state" }
        return result
    }

    @Synchronized fun credentials(): Pair<String, String> {
        val existingId = prefs.getString("device_id", null)
        val existingToken = prefs.getString("device_token", null)
        if (existingId != null && existingToken != null) return existingId to existingToken
        val id = UUID.randomUUID().toString()
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE) }
        check(prefs.edit().putString("device_id", id).putString("device_token", token).commit())
        return id to token
    }

    @Synchronized fun pendingEvents(): JSONArray = JSONArray(prefs.getString("events", "[]"))

    @Synchronized fun acknowledge(through: Long) {
        val pending = pendingEvents()
        val remaining = JSONArray()
        for (i in 0 until pending.length()) {
            val event = pending.getJSONObject(i)
            if (event.getLong("seq") > through) remaining.put(event)
        }
        check(prefs.edit().putString("events", remaining.toString()).commit())
    }

    @Synchronized fun applyOwnerUnlock(through: Long): Boolean {
        if (!state().locked || !AccessPolicy.canApplyOwnerUnlock(prefs.getLong("lockout_sequence", 0), through)) return false
        val seq = prefs.getLong("sequence", 0) + 1
        val events = pendingEvents().put(JSONObject().put("seq", seq).put("kind", "recovered").put("failures", 0))
        check(prefs.edit().putInt("failures", 0).remove("lockout_sequence")
            .putLong("sequence", seq).putString("events", events.toString()).commit())
        return true
    }

    /** Separate from the conversation identity; only the owner recovery channel uses this key. */
    @Synchronized fun ownerRelayKeys(): rust.nostr.sdk.Keys {
        val existing = prefs.getString("owner_relay_secret", null)
        if (existing != null) return rust.nostr.sdk.Keys.parse(existing)
        val keys = rust.nostr.sdk.Keys.generate()
        check(prefs.edit().putString("owner_relay_secret", keys.secretKey().toHex()).commit())
        return keys
    }

    @Synchronized fun ownerLastSeenSecs(): Long = prefs.getLong("owner_relay_seen", 0)
    @Synchronized fun touchOwnerRelay(secs: Long) {
        if (secs > ownerLastSeenSecs()) check(prefs.edit().putLong("owner_relay_seen", secs).commit())
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
        const val PREF_FILE = "calculator_access"
        private const val KEY_ALIAS = "calculator_pin_verifier"
        @Volatile private var instance: CalculatorAccessStore? = null
        fun get(context: Context): CalculatorAccessStore = instance ?: synchronized(this) {
            instance ?: CalculatorAccessStore(context.applicationContext).also { instance = it }
        }
    }
}
