package com.relay.app.linking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class LinkQrTest {

    private val code = LinkCode.generate(Random(7))
    private val deviceId = "0123456789abcdef0123456789abcdef"
    private val ephemeral = "AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHH"
    private val auth = "IIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPP"

    @Test
    fun roundTripsAllFields() {
        val payload = LinkQr.decode(LinkQr.encode(code, deviceId, ephemeral, auth))!!
        assertEquals(code, payload.code)
        assertEquals(deviceId, payload.deviceId)
        assertEquals(ephemeral, payload.ephemeralPublicKey)
        assertEquals(auth, payload.deviceAuthPublicKey)
        assertTrue(payload.isLinkable)
    }

    @Test
    fun encodedTextIsAsciiAndSingleToken() {
        val text = LinkQr.encode(code, deviceId, ephemeral, auth)
        assertTrue(text.startsWith("RELAYLINK:2:"))
        assertTrue(text.all { it.code in 33..126 })
        assertFalse(text.any { it.isWhitespace() })
        assertTrue(text.length <= 1024)
    }

    @Test
    fun legacyV1QrParsesButIsNotLinkable() {
        val payload = LinkQr.decode(LinkCode.qr(code))!!
        assertEquals(code, payload.code)
        assertNull(payload.deviceId)
        assertFalse(payload.isLinkable)
    }

    @Test
    fun humanSpacedCodeStillNormalises() {
        val text = LinkQr.encode(LinkCode.human(code), deviceId, ephemeral, auth)
        assertEquals(code, LinkQr.decode(text)!!.code)
    }

    @Test
    fun rejectsUnknownPrefixVersionAndArity() {
        for (bad in listOf(
            "",
            "not a link",
            "RELAYLINK:3:$code:$deviceId:$ephemeral:$auth",
            "RELAYLINK:2:$code:$deviceId:$ephemeral",
            "RELAYLINK:2:$code:$deviceId:$ephemeral:$auth:extra",
            "RELAYLINK:1:$code:$deviceId",
        )) {
            assertNull(bad, LinkQr.decode(bad))
        }
    }

    @Test
    fun rejectsMalformedFields() {
        for (bad in listOf(
            LinkQr.encode(code, deviceId, ephemeral, auth).replace(deviceId, deviceId.uppercase()),
            LinkQr.encode(code, deviceId, ephemeral, auth).replace(deviceId, "0123456789abcdef0123456789abcde"),
            LinkQr.encode(code, deviceId, ephemeral, auth).replace(ephemeral, "short"),
            LinkQr.encode(code, deviceId, ephemeral, auth).replace(ephemeral, "!" .repeat(24)),
            LinkQr.encode(code, deviceId, ephemeral, auth).replace(auth, ephemeral),
            LinkQr.encode(code, deviceId, ephemeral, auth).replace(code, code.dropLast(1)),
        )) {
            assertNull(bad, LinkQr.decode(bad))
        }
    }

    @Test
    fun rejectsOversizeKeysAndText() {
        val huge = "A".repeat(LinkQr.MAX_PUBLIC_KEY_CHARS + 1)
        assertNull(LinkQr.decode("RELAYLINK:2:$code:$deviceId:$huge:$auth"))
        assertNull(LinkQr.decode("RELAYLINK:2:$code:$deviceId:$ephemeral:$auth".padEnd(2000, 'A')))
    }

    @Test
    fun rejectsNullAndAllowsSurroundingWhitespace() {
        assertNull(LinkQr.decode(null))
        val text = LinkQr.encode(code, deviceId, ephemeral, auth)
        assertEquals(code, LinkQr.decode("  $text\n")!!.code)
    }
}
