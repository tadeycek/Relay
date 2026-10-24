package com.relay.app.messaging

import android.content.Context
import com.relay.app.crypto.AndroidKeyMaterial
import com.relay.app.crypto.KeyMaterial
import com.relay.app.crypto.RelayCrypto

/**
 * The phone's binding of the shared [InnerEnvelope] to [RelayCrypto]. [seal] only needs the
 * recipient's public key, so (as before) it needs no Context; [open] uses this install's keysets.
 */
object AndroidInnerEnvelope {
    private val encryptOnly = object : KeyMaterial {
        override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? =
            RelayCrypto.encryptTo(recipientPublicKeyBase64, plaintext)

        override fun decryptMine(ciphertext: ByteArray): ByteArray? =
            error("seal() never decrypts")
    }

    fun seal(recipientPublicKeyBase64: String?, plainBody: String): String =
        InnerEnvelope.seal(encryptOnly, recipientPublicKeyBase64, plainBody)

    fun open(context: Context, wireBody: String): String? = InnerEnvelope.open(AndroidKeyMaterial(context), wireBody)
}
