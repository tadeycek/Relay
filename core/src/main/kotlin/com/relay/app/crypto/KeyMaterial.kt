package com.relay.app.crypto

/**
 * The inner end-to-end layer's key operations, supplied by the platform (WP03 seam).
 *
 * Android implements it over Tink + the Android Keystore (`RelayCrypto`); the desktop client over
 * its own secret store. `:core` never touches key storage, so it carries no Android or Tink types.
 */
interface KeyMaterial {
    /** HPKE-encrypts [plaintext] to [recipientPublicKeyBase64]; null if that key is unusable. */
    fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray?

    /** Decrypts [ciphertext] with this identity's current (or still-graced previous) key; null on failure. */
    fun decryptMine(ciphertext: ByteArray): ByteArray?
}
