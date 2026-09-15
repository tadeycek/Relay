package com.relay.app.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.config.TinkConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.signature.SignatureConfig
import com.relay.app.util.RelayPreferences
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
 *
 * Because there's no ratchet, the encryption (hybrid) keypair carries no
 * forward secrecy on its own: if it's ever extracted, every message ever
 * sent to it becomes decryptable. To bound that exposure, the encryption
 * keypair is periodically rotated (see [rotateIdentityKey]); a *separate*,
 * never-rotated Ed25519 signing keypair lets peers verify a rotation really
 * came from the same device that was originally paired, so routine
 * rotations can be auto-accepted instead of tripping the spoofed-key-change
 * warning (see SmsReceiver).
 */
object RelayCrypto {

    /** Magic prefix marking an MMS media part as hybrid-encrypted (vs. legacy/plain bytes). */
    val MMS_ENC_MAGIC = byteArrayOf(0x52, 0x4C, 0x59, 0x45) // "RLYE"

    /** How long a retired encryption private key is kept around, to decrypt messages that were
     *  already in flight (encrypted to it) when a rotation happened, before being wiped for good. */
    const val GRACE_PERIOD_MS = 7L * 24 * 60 * 60 * 1000

    private const val KEYSET_NAME_CURRENT = "relay_identity_keyset"
    private const val PREF_FILE_CURRENT = "relay_identity_keyset_prefs"
    private const val KEYSET_NAME_PREVIOUS = "relay_identity_keyset_previous"
    private const val PREF_FILE_PREVIOUS = "relay_identity_keyset_prefs_previous"
    private const val KEYSET_NAME_TEMP_ROTATION = "relay_identity_keyset_temp_rotation"
    private const val PREF_FILE_TEMP_ROTATION = "relay_identity_keyset_prefs_temp_rotation"
    private const val SIGNING_KEYSET_NAME = "relay_signing_keyset"
    private const val SIGNING_PREF_FILE = "relay_signing_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://relay_identity_master_key"
    private const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    private const val SIGNATURE_TEMPLATE = "ED25519"

    @Volatile private var currentIdentity: KeysetHandle? = null
    @Volatile private var signingIdentity: KeysetHandle? = null
    @Volatile private var configRegistered = false

    private fun ensureConfig() {
        if (configRegistered) return
        synchronized(this) {
            if (configRegistered) return
            TinkConfig.register()
            SignatureConfig.register()
            configRegistered = true
        }
    }

    private fun currentIdentityHandle(context: Context): KeysetHandle {
        currentIdentity?.let { return it }
        synchronized(this) {
            currentIdentity?.let { return it }
            ensureConfig()
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET_NAME_CURRENT, PREF_FILE_CURRENT)
                .withKeyTemplate(KeyTemplates.get(HYBRID_TEMPLATE))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
            val handle = manager.keysetHandle
            currentIdentity = handle
            return handle
        }
    }

    /** Non-creating lookup: returns the retired keypair only if one exists and hasn't expired. */
    private fun previousIdentityHandle(context: Context): KeysetHandle? {
        val prefs = RelayPreferences(context)
        val expiresAt = prefs.previousKeyExpiresAt
        if (expiresAt == 0L) return null
        if (System.currentTimeMillis() >= expiresAt) {
            purgePreviousKey(context)
            return null
        }
        val appContext = context.applicationContext
        val sharedPrefs = appContext.getSharedPreferences(PREF_FILE_PREVIOUS, Context.MODE_PRIVATE)
        if (!sharedPrefs.contains(KEYSET_NAME_PREVIOUS)) return null
        return try {
            ensureConfig()
            AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME_PREVIOUS, PREF_FILE_PREVIOUS)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
        } catch (e: Exception) {
            null
        }
    }

    private fun purgePreviousKey(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREF_FILE_PREVIOUS, Context.MODE_PRIVATE)
            .edit().clear().apply()
        RelayPreferences(context).previousKeyExpiresAt = 0L
    }

    private fun signingHandle(context: Context): KeysetHandle {
        signingIdentity?.let { return it }
        synchronized(this) {
            signingIdentity?.let { return it }
            ensureConfig()
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, SIGNING_KEYSET_NAME, SIGNING_PREF_FILE)
                .withKeyTemplate(KeyTemplates.get(SIGNATURE_TEMPLATE))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
            val handle = manager.keysetHandle
            signingIdentity = handle
            return handle
        }
    }

    private fun publicKeysetBase64(handle: KeysetHandle): String {
        val out = ByteArrayOutputStream()
        handle.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(out))
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** This device's public identity (encryption) key, base64-encoded — safe to send over SMS. Null if key setup failed. */
    fun myPublicKeyBase64(context: Context): String? = try {
        publicKeysetBase64(currentIdentityHandle(context))
    } catch (e: Exception) {
        null
    }

    /** This device's long-term signing public key, base64-encoded — sent once at pairing time so
     *  peers can later verify that a rotated encryption key genuinely came from this device. */
    fun mySigningPublicKeyBase64(context: Context): String? = try {
        publicKeysetBase64(signingHandle(context))
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

    /** Decrypts a payload sent to us. Tries the current identity key first, then falls back to the
     *  retired one (if still within its grace period) for messages encrypted just before a
     *  rotation reached the sender. Returns null if it can't be decrypted by either. */
    fun decryptMine(context: Context, ciphertext: ByteArray, contextInfo: ByteArray = ByteArray(0)): ByteArray? {
        try {
            return currentIdentityHandle(context).getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, contextInfo)
        } catch (e: Exception) {
            // fall through to the retired key
        }
        val previous = previousIdentityHandle(context) ?: return null
        return try {
            previous.getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, contextInfo)
        } catch (e: Exception) {
            null
        }
    }

    /** Signs arbitrary [data] with our long-term signing key. Used both for rotation broadcasts
     *  (signing the new encryption public key) and for per-message sender authentication (signing
     *  the outgoing ciphertext) — same Ed25519 keypair, same primitive, different payloads. */
    fun signBytes(context: Context, data: ByteArray): String? = try {
        val sig = signingHandle(context).getPrimitive(PublicKeySign::class.java).sign(data)
        Base64.encodeToString(sig, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    /** Verifies that [signatureBase64] over [data] was produced by the holder of
     *  [signerSigningPublicKeyBase64]. Used both to auto-accept a contact's routine key rotation
     *  (data = the new encryption public key) and to authenticate a message's real sender
     *  (data = the received ciphertext) instead of trusting decryptability alone — Tink's hybrid
     *  encryption here has no sender authentication built in (HPKE base mode), so without this a
     *  spoofed SMS sender ID plus anyone's known public key can produce a ciphertext that decrypts
     *  cleanly but was never sent by the contact it claims to be from. */
    fun verifyBytes(
        data: ByteArray,
        signatureBase64: String,
        signerSigningPublicKeyBase64: String,
    ): Boolean = try {
        ensureConfig()
        val signerKeyBytes = Base64.decode(signerSigningPublicKeyBase64, Base64.NO_WRAP)
        val handle = KeysetHandle.readNoSecret(BinaryKeysetReader.withBytes(signerKeyBytes))
        val sig = Base64.decode(signatureBase64, Base64.NO_WRAP)
        handle.getPrimitive(PublicKeyVerify::class.java).verify(sig, data)
        true
    } catch (e: Exception) {
        false
    }

    /** Result of rotating this device's encryption identity key: the new public key plus a
     *  signature over it (from our stable signing key) to broadcast to already-paired contacts. */
    data class RotationResult(val newPublicKeyBase64: String, val signatureBase64: String)

    /**
     * Retires the current encryption keypair (kept only for [GRACE_PERIOD_MS] to decrypt
     * messages already in flight) and generates a fresh one. The signing keypair is untouched —
     * it's the long-term anchor peers use to verify this and future rotations.
     *
     * Returns null if rotation failed (e.g. Keystore error) — callers should leave the existing
     * key in place and simply retry later rather than broadcast a half-completed rotation.
     */
    fun rotateIdentityKey(context: Context): RotationResult? {
        synchronized(this) {
            return try {
                ensureConfig()
                val appContext = context.applicationContext

                // Generate + sign the replacement key into a scratch slot first, and only once
                // that has fully succeeded touch the real current/previous slots. Generating
                // directly into the current slot (the old approach) meant clearing the working
                // key *before* confirming a replacement could be produced — any failure after
                // that point (Keystore error, signing failure) left the device with no usable
                // identity key and nothing to roll back to, contradicting this function's own
                // documented "leave the existing key in place on failure" contract.
                val tempPrefs = appContext.getSharedPreferences(PREF_FILE_TEMP_ROTATION, Context.MODE_PRIVATE)
                tempPrefs.edit().clear().apply() // discard any scratch state from a prior failed attempt
                val tempHandle = AndroidKeysetManager.Builder()
                    .withSharedPref(appContext, KEYSET_NAME_TEMP_ROTATION, PREF_FILE_TEMP_ROTATION)
                    .withKeyTemplate(KeyTemplates.get(HYBRID_TEMPLATE))
                    .withMasterKeyUri(MASTER_KEY_URI)
                    .build()
                    .keysetHandle
                val newKeyBase64 = publicKeysetBase64(tempHandle)
                val signature = signBytes(context, Base64.decode(newKeyBase64, Base64.NO_WRAP))
                if (signature == null) {
                    tempPrefs.edit().clear().apply()
                    return null
                }

                // Replacement is generated and signed — safe to retire the old key now. Move
                // current -> previous by copying the still-wrapped keyset bytes directly (both
                // keysets share the same Keystore master key, so no decryption needed here).
                val currentPrefs = appContext.getSharedPreferences(PREF_FILE_CURRENT, Context.MODE_PRIVATE)
                val wrappedCurrent = currentPrefs.getString(KEYSET_NAME_CURRENT, null)
                if (wrappedCurrent != null) {
                    appContext.getSharedPreferences(PREF_FILE_PREVIOUS, Context.MODE_PRIVATE)
                        .edit().putString(KEYSET_NAME_PREVIOUS, wrappedCurrent).apply()
                    RelayPreferences(context).previousKeyExpiresAt = System.currentTimeMillis() + GRACE_PERIOD_MS
                }

                // Move temp -> current (same copy-the-wrapped-bytes approach) and clean up scratch state.
                val wrappedTemp = tempPrefs.getString(KEYSET_NAME_TEMP_ROTATION, null)
                currentPrefs.edit().clear().putString(KEYSET_NAME_CURRENT, wrappedTemp).apply()
                tempPrefs.edit().clear().apply()
                currentIdentity = null

                RelayPreferences(context).lastKeyRotationAt = System.currentTimeMillis()
                RotationResult(newKeyBase64, signature)
            } catch (e: Exception) {
                null
            }
        }
    }
}
