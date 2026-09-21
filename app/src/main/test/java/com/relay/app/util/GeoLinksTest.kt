package com.relay.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoLinksTest {

    @Test
    fun withoutLabel() {
        assertEquals("geo:46.056900,14.505800?q=46.056900,14.505800", GeoLinks.geoString(46.0569, 14.5058))
    }

    @Test
    fun negativeCoordinatesAndLocaleIndependentDecimalPoint() {
        assertEquals("geo:-33.500000,151.250000?q=-33.500000,151.250000", GeoLinks.geoString(-33.5, 151.25))
    }

    @Test
    fun labelIsPercentEncoded() {
        assertEquals(
            "geo:1.000000,2.000000?q=1.000000,2.000000(Caf%C3%A9%20Central)",
            GeoLinks.geoString(1.0, 2.0, "Café Central"),
        )
    }

    @Test
    fun parenthesesAndControlCharsCannotBreakOutOfTheLabel() {
        val s = GeoLinks.geoString(1.0, 2.0, "a)(b\nc")
        assertEquals("geo:1.000000,2.000000?q=1.000000,2.000000(abc)", s)
    }

    @Test
    fun blankOrWhitespaceLabelIsOmitted() {
        assertNull(GeoLinks.sanitizeLabel("   "))
        assertNull(GeoLinks.sanitizeLabel(null))
        assertNull(GeoLinks.sanitizeLabel("()"))
        assertEquals("geo:1.000000,2.000000?q=1.000000,2.000000", GeoLinks.geoString(1.0, 2.0, "  "))
    }

    @Test
    fun longLabelIsCapped() {
        assertEquals(60, GeoLinks.sanitizeLabel("x".repeat(200))!!.length)
    }
}
