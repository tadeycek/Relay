package com.relay.app.crypto

import android.content.Context

/** Android's [KeyMaterial]: Tink HPKE keysets wrapped by the Android Keystore, via [RelayCrypto]. */
class AndroidKeyMaterial(private val context: Context) : KeyMaterial {
    override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? =
        RelayCrypto.encryptTo(recipientPublicKeyBase64, plaintext)

    override fun decryptMine(ciphertext: ByteArray): ByteArray? = RelayCrypto.decryptMine(context, ciphertext)
}
