package com.relay.app.desktop

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import com.relay.app.crypto.KeyMaterial
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop [KeyMaterial]: Tink HPKE (the same DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM
 * template as the phone) with its own key file under the XDG config directory. Unlike the phone
 * there is no Android Keystore; the Tink keyset JSON is sealed with a 256-bit master key from the
 * injected [SecretStore] and the resulting file is written 0600.
 *
 * Semantics mirrored from AndroidKeyMaterial/RelayCrypto:
 *  - one current hybrid keyset used to decrypt incoming messages;
 *  - a previous keyset kept for [GRACE_PERIOD_MS] after rotation so in-flight messages still open;
 *  - the public key is the Tink public keyset serialised with BinaryKeysetWriter and base64-encoded,
 *    exactly what peers expect in TYPE:PUBKEY.
 */
class DesktopKeyMaterial(
    private val paths: DesktopPaths,
    private val secrets: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : KeyMaterial {

    private val lock = Any()
    private val random = SecureRandom()

    @Volatile private var loaded: State? = null

    private data class State(
        val current: KeysetHandle,
        val previous: KeysetHandle?,
        val previousExpiresAt: Long,
    )

    init {
        TinkConfig.register()
    }

    /** Base64 public keyset for this install's current HPKE key. */
    fun publicKeyBase64(): String = synchronized(lock) {
        val state = load()
        val out = ByteArrayOutputStream()
        state.current.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(out))
        Base64.getEncoder().encodeToString(out.toByteArray())
    }

    override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? = try {
        val keyBytes = Base64.getDecoder().decode(recipientPublicKeyBase64)
        val handle = KeysetHandle.readNoSecret(BinaryKeysetReader.withBytes(keyBytes))
        handle.getPrimitive(HybridEncrypt::class.java).encrypt(plaintext, ByteArray(0))
    } catch (e: Exception) {
        null
    }

    override fun decryptMine(ciphertext: ByteArray): ByteArray? = synchronized(lock) {
        val state = load()
        try {
            state.current.getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, ByteArray(0))
        } catch (e: Exception) {
            val previous = state.previous ?: return@synchronized null
            if (clock() >= state.previousExpiresAt) {
                // Expired grace key: wipe it rather than keep trying an unusable secret.
                runCatching { retirePrevious() }
                return@synchronized null
            }
            try {
                previous.getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, ByteArray(0))
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Rotates the HPKE keypair: the current key becomes previous for [GRACE_PERIOD_MS] and a fresh
     * key becomes current. Returns the new public key, or null if anything failed (the old key is
     * left in place). The signing key used by the phone's rotation broadcast is out of scope for
     * WP06 text messaging; the seam is [publicKeyBase64] + the stored previous key.
     */
    fun rotate(): String? = synchronized(lock) {
        try {
            val old = load()
            val fresh = KeysetHandle.generateNew(KeyTemplates.get(TEMPLATE))
            val next = State(fresh, old.current, clock() + GRACE_PERIOD_MS)
            persist(next)
            loaded = next
            publicKeyBase64()
        } catch (e: Exception) {
            null
        }
    }

    private fun load(): State {
        loaded?.let { return it }
        synchronized(lock) {
            loaded?.let { return it }
            val file = paths.keyFile
            val raw = DesktopPaths.readPrivateFile(file)
            val state = if (raw == null) {
                val fresh = KeysetHandle.generateNew(KeyTemplates.get(TEMPLATE))
                State(fresh, null, 0).also { persist(it) }
            } else {
                decode(raw)
            }
            loaded = state
            return state
        }
    }

    private fun decode(raw: ByteArray): State {
        val envelope = JSONObject(raw.toString(Charsets.UTF_8))
        val version = envelope.optInt("v", -1)
        require(version == 1) { "Unsupported key file version $version" }
        val master = secrets.getOrCreateKey(MASTER_KEY_ID)
        val current = importKeyset(open(envelope.getString("current"), master))
        val previous = envelope.optString("previous").takeIf { it.isNotEmpty() }?.let {
            importKeyset(open(it, master))
        }
        return State(current, previous, envelope.optLong("previousExpiresAt", 0))
    }

    private fun persist(state: State) {
        val master = secrets.getOrCreateKey(MASTER_KEY_ID)
        val envelope = JSONObject()
            .put("v", 1)
            .put("current", seal(exportKeyset(state.current), master))
        state.previous?.let { envelope.put("previous", seal(exportKeyset(it), master)) }
        if (state.previous != null) envelope.put("previousExpiresAt", state.previousExpiresAt)
        DesktopPaths.writePrivateFile(paths.keyFile, envelope.toString().toByteArray(Charsets.UTF_8))
        require(DesktopPaths.isPrivate(paths.keyFile)) { "Could not set 0600 on ${paths.keyFile}" }
    }

    private fun retirePrevious() {
        val state = loaded ?: return
        val next = State(state.current, null, 0)
        persist(next)
        loaded = next
    }

    private fun exportKeyset(handle: KeysetHandle): String {
        val out = ByteArrayOutputStream()
        // This JSON contains private key material. It is never written directly to disk; seal() encrypts it.
        CleartextKeysetHandle.write(handle, JsonKeysetWriter.withOutputStream(out))
        return out.toString(Charsets.UTF_8)
    }

    private fun importKeyset(json: String): KeysetHandle =
        CleartextKeysetHandle.read(JsonKeysetReader.withString(json))

    /** AES-256-GCM with a random 12-byte IV; output = base64(iv || ciphertext+tag). */
    private fun seal(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    private fun open(sealedBase64: String, key: ByteArray): String {
        val all = Base64.getDecoder().decode(sealedBase64)
        require(all.size > 12) { "Sealed key material is too short" }
        val iv = all.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }

    companion object {
        const val TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
        const val GRACE_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
        private const val MASTER_KEY_ID = "keymaterial"
        private val AAD = "relay-desktop-keymaterial-v1".toByteArray(Charsets.UTF_8)
    }
}
