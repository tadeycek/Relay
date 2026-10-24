package com.relay.app.linking

/**
 * HPKE for the link bundle, supplied by the platform (the same seam pattern as
 * [com.relay.app.crypto.KeyMaterial]).
 *
 * [KeyMaterial] cannot be reused as-is: it decrypts with *this identity's* current/previous key
 * (`decryptMine`), while a link bundle is sealed to the **desktop's ephemeral link keypair**, which
 * is not part of the identity keyset and must be usable before any identity exists on the desktop.
 * A separate two-sided interface is therefore genuinely required (justified in WP05-codecs.md).
 *
 * Both methods return null on any cryptographic failure — implementations must never throw for
 * hostile ciphertext, and must enforce the same input bounds as [LinkBundleCodec] (it checks them
 * first anyway). The one-time link code is NOT passed here: it is proof of retrieval, not key
 * transport.
 */
interface LinkHpke {
    /** Encrypts [plaintext] to [recipientPublicKeyBase64] (the desktop's ephemeral link public key). */
    fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray?

    /** Decrypts [ciphertext] with [recipientPrivateKeyBase64] (the desktop's ephemeral link private key). */
    fun decryptWith(recipientPrivateKeyBase64: String, ciphertext: ByteArray): ByteArray?
}
