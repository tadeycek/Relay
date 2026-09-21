package com.relay.app.messaging

import com.relay.app.data.model.PinExpiry
import com.relay.app.util.SmsMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingClassifierTest {

    @Test
    fun plainTextIsText() {
        assertEquals(Classified.Text, IncomingClassifier.classify("hello there"))
        assertEquals(Classified.Text, IncomingClassifier.classify(""))
        assertEquals(Classified.Text, IncomingClassifier.classify("TYPE:UNKNOWN|X:1"))
    }

    @Test
    fun locationPinWithExpiryAndLabel() {
        val body = SmsMessageParser.formatLocation(46.0569, 14.5058, PinExpiry.SIX_HOURS, "Café")
        val c = IncomingClassifier.classify(body) as Classified.Pin
        assertEquals(46.0569, c.lat, 1e-6)
        assertEquals(14.5058, c.lng, 1e-6)
        assertEquals(PinExpiry.SIX_HOURS, c.expiry)
        assertEquals("Café", c.label)
    }

    @Test
    fun locationPinWithoutOptionalFields() {
        val c = IncomingClassifier.classify(SmsMessageParser.formatLocation(-33.5, 151.25)) as Classified.Pin
        assertEquals(PinExpiry.NEVER, c.expiry)
        assertEquals(null, c.label)
        assertEquals(-33.5, c.lat, 1e-6)
    }

    @Test
    fun controlMessages() {
        assertEquals(Classified.LocationRequest, IncomingClassifier.classify(SmsMessageParser.LOCATION_REQUEST_MSG))
        assertEquals(Classified.LocationDeclined, IncomingClassifier.classify(SmsMessageParser.LOCATION_DECLINED_MSG))
        assertEquals(Classified.ReadReceipt(1234L), IncomingClassifier.classify(SmsMessageParser.formatReadReceipt(1234L)))
        assertEquals(Classified.PublicKey, IncomingClassifier.classify(SmsMessageParser.formatPublicKey("AbC+/=", "Ana")))
    }

    @Test
    fun malformedReadReceiptIsIgnoredNotShownAsChat() {
        val tooBig = "TYPE:READ_RECEIPT|MSG_ID:" + "9".repeat(40)
        assertEquals(Classified.Ignore, IncomingClassifier.classify(tooBig))
    }

    @Test
    fun keyMessagesTakePrecedenceOverEverythingElse() {
        val sneaky = SmsMessageParser.formatPublicKey("AAAA", "x") + " TYPE:LOCATION_REQUEST"
        assertEquals(Classified.PublicKey, IncomingClassifier.classify(sneaky))
    }

    @Test
    fun encryptedEnvelopesAreNotClassifiedHereAsTheyAreOpenedFirst() {
        // ENC wrappers are decrypted before classification; if one slips through it is just text.
        assertTrue(IncomingClassifier.classify("TYPE:ENC|CT:AAAA|SIG:BBBB") is Classified.Text)
    }
}
