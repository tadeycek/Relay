package com.relay.app.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.ByteArrayOutputStream

/**
 * End-to-end encryption for SMS/MMS payloads between two Relay installs.
 *
 * Each device holds one long-term X25519/HPKE keypair (Tink hybrid
 * encryption), stored in SharedPreferences with the private key wrapped by
 * an Android Keystore-backed master key — the raw private key never touches
 * disk in cleartext. Public keys are exchanged over SMS (TYPE:PUBKEY) and
 * cached per-contact; once both sides know each other's key, structured
 * Relay payloads sent to that contact are hybrid-encrypted before becoming
 * the SMS/MMS body, so carriers and anyone in the middle see only
 * ciphertext.
 *
 * This is opportunistic per-message encryption (Tink's HPKE mode generates a
 * fresh ephemeral key per call) rather than a Signal-style ratchet: there is
 * no shared session state to keep synchronized, which matters because SMS
 * can arrive out of order, be duplicated, or be dropped by the carrier.
 */
object RelayCrypto {

    /** Magic prefix marking an MMS media part as hybrid-encrypted (vs. legacy/plain bytes). */
    val MMS_ENC_MAGIC = byteArrayOf(0x52, 0x4C, 0x59, 0x45) // "RLYE"

    private const val KEYSET_NAME = "relay_identity_keyset"
    private const val PREF_FILE_NAME = "relay_identity_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://relay_identity_master_key"
    private const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

    @Volatile private var identity: KeysetHandle? = null
    @Volatile private var configRegistered = false

    private fun ensureConfig() {
        if (configRegistered) return
        synchronized(this) {
            if (configRegistered) return
            TinkConfig.register()
            configRegistered = true
        }
    }

    private fun identityHandle(context: Context): KeysetHandle {
        identity?.let { return it }
        synchronized(this) {
            identity?.let { return it }
            ensureConfig()
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get(HYBRID_TEMPLATE))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
            val handle = manager.keysetHandle
            identity = handle
            return handle
        }
    }

    /** This device's public identity key, base64-encoded — safe to send over SMS. Null if key setup failed. */
    fun myPublicKeyBase64(context: Context): String? = try {
        val public = identityHandle(context).publicKeysetHandle
        val out = ByteArrayOutputStream()
        public.writeNoSecret(BinaryKeysetWriter.withOutputStream(out))
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    /** Encrypts [plaintext] to a contact's public key. Returns null on any failure (falls back to plaintext send). */
    fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray, contextInfo: ByteArray = ByteArray(0)): ByteArray? = try {
        ensureConfig()
        val keyBytes = Base64.decode(recipientPublicKeyBase64, Base64.NO_WRAP)
        val handle = KeysetHandle.readNoSecret(BinaryKeysetReader.withBytes(keyBytes))
        handle.getPrimitive(HybridEncrypt::class.java).encrypt(plaintext, contextInfo)
    } catch (e: Exception) {
        null
    }

    /** Decrypts a payload sent to us. Returns null if it can't be decrypted (wrong/missing key, corrupt data). */
    fun decryptMine(context: Context, ciphertext: ByteArray, contextInfo: ByteArray = ByteArray(0)): ByteArray? = try {
        identityHandle(context).getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, contextInfo)
    } catch (e: Exception) {
        null
    }
}
