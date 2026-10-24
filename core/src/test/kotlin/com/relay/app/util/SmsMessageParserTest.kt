package com.relay.app.util

import com.relay.app.data.model.PinExpiry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SmsMessageParserTest {

    private lateinit var originalLocale: Locale

    @Before
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /**
     * Regression: on a device whose region uses a comma as the decimal separator, formatting a
     * coordinate with the platform default locale writes "46,383892" — the receiver's parser expects a
     * dot, so it never recognises the message as a location and shows the raw protocol string instead.
     */
    @Test
    fun locationIsFormattedWithADotRegardlessOfTheDeviceLocale() {
        for (locale in listOf(Locale.GERMANY, Locale("sl", "SI"), Locale.FRANCE, Locale.US)) {
            Locale.setDefault(locale)
            val body = SmsMessageParser.formatLocation(46.383892, 15.994281)
            assertNotNull("locale $locale must not put a comma in the coordinates", Regex("""LAT:-?\d+\.\d+""").find(body))
            val parsed = SmsMessageParser.parseLocation(body)
            assertNotNull("locale $locale: the sender's own message must parse back", parsed)
            assertEquals(46.383892, parsed!!.lat, 0.0000005)
            assertEquals(15.994281, parsed.lng, 0.0000005)
        }
    }

    @Test
    fun negativeCoordinatesRoundTrip() {
        Locale.setDefault(Locale.GERMANY)
        val body = SmsMessageParser.formatLocation(-33.865143, -63.0)
        val parsed = SmsMessageParser.parseLocation(body)
        assertNotNull(parsed)
        assertEquals(-33.865143, parsed!!.lat, 0.0000005)
        assertEquals(-63.0, parsed.lng, 0.0000005)
    }

    @Test
    fun expiryAndLabelRoundTrip() {
        val body = SmsMessageParser.formatLocation(1.0, 2.0, PinExpiry.SIX_HOURS, "Home")
        assertEquals(PinExpiry.SIX_HOURS, SmsMessageParser.parseExpiry(body))
        assertEquals("Home", SmsMessageParser.parseLabel(body))
        assertNotNull(SmsMessageParser.parseLocation(body))
    }

    @Test
    fun aPlainTextMessageIsNotMistakenForALocation() {
        assertEquals(null, SmsMessageParser.parseLocation("just chatting, 46,38 is not a coordinate"))
    }
}
