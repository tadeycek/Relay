package com.relay.app.linking

import com.relay.app.crypto.RelayCrypto

/**
 * The phone's [LinkHpke]: Tink hybrid encryption over the identity keyset the app already holds.
 *
 * Only [encryptTo] is meaningful here — a link bundle is sealed **to the computer's** ephemeral key.
 * The phone never opens one, so [decryptWith] is an explicit unsupported error rather than a stub
 * that pretends to work.
 */
object AndroidLinkHpke : LinkHpke {

    override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? =
        RelayCrypto.encryptTo(recipientPublicKeyBase64, plaintext)

    override fun decryptWith(recipientPrivateKeyBase64: String, ciphertext: ByteArray): ByteArray? =
        throw UnsupportedOperationException("the phone never opens a link bundle; only a computer does")
}
