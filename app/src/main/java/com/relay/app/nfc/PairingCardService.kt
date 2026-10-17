package com.relay.app.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Answers another phone's reader while the "Show my code" screen is open. What it serves is set by
 * [NfcPairing.payload]; with nothing set, it does not answer at all, so the phone never leaks a contact
 * code just because the app is installed.
 */
class PairingCardService : HostApduService() {

    private val tag = Type4Tag.Emulator {
        NfcPairing.payload?.let { Type4Tag.ndefFile(Type4Tag.ndefMimeMessage(Type4Tag.MIME_TYPE, it.toByteArray(Charsets.UTF_8))) }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        tag.process(commandApdu ?: ByteArray(0))

    override fun onDeactivated(reason: Int) {
        tag.reset()
    }
}
