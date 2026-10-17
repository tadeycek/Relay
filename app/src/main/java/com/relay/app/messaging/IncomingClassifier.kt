package com.relay.app.messaging

import com.relay.app.data.model.PinExpiry
import com.relay.app.media.MediaBody
import com.relay.app.media.MediaRef
import com.relay.app.util.SmsMessageParser

/**
 * What a decrypted message body means. Pure (regex parsing only) so it is unit-testable; the
 * handler decides what to do with each kind.
 */
sealed class Classified {
    /** A `TYPE:PUBKEY` handshake or key-rotation announcement. */
    data object PublicKey : Classified()
    data object LocationRequest : Classified()
    data object LocationDeclined : Classified()
    data class ReadReceipt(val upToTimestamp: Long) : Classified()
    data class Pin(val lat: Double, val lng: Double, val expiry: PinExpiry, val label: String?) : Classified()
    data object Text : Classified()

    /** A reference to an encrypted image/video on a media server; see [com.relay.app.media.MediaBody]. */
    data class Media(val ref: MediaRef) : Classified()

    /** A control message that is malformed (e.g. a read receipt whose cursor is not a number): drop, never show as chat text. */
    data object Ignore : Classified()
}

object IncomingClassifier {

    /**
     * Same precedence the SMS receiver used: key message, then location request, then read receipt,
     * then pin, then declined, else plain text.
     */
    fun classify(body: String): Classified {
        if (SmsMessageParser.isPublicKeyMessage(body)) return Classified.PublicKey
        if (SmsMessageParser.isLocationRequest(body)) return Classified.LocationRequest
        if (MediaBody.isMedia(body)) {
            // A media message that fails validation (bad URL/hash/key/mime/size) is dropped, never
            // shown as chat text and never fetched.
            val ref = MediaBody.parse(body) ?: return Classified.Ignore
            return Classified.Media(ref)
        }
        if (SmsMessageParser.isReadReceipt(body)) {
            val ts = SmsMessageParser.parseReadReceipt(body) ?: return Classified.Ignore
            return Classified.ReadReceipt(ts)
        }
        SmsMessageParser.parseLocation(body)?.let { loc ->
            return Classified.Pin(
                lat = loc.lat,
                lng = loc.lng,
                expiry = SmsMessageParser.parseExpiry(body),
                label = SmsMessageParser.parseLabel(body),
            )
        }
        if (SmsMessageParser.isLocationDeclined(body)) return Classified.LocationDeclined
        return Classified.Text
    }
}
