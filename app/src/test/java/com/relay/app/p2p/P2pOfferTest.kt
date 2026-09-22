package com.relay.app.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class P2pOfferTest {

    private val nonce = "0123456789abcdef0123456789abcdef"
    private val sha = "a".repeat(64)

    private fun offer(caption: String = "hi there") = P2pOfferMessages.Offer(
        nonce = nonce, sha256Hex = sha, keyBase64 = "S0VZS0VZ", mime = "image/jpeg", size = 12345L, caption = caption,
    )

    @Test
    fun offerRoundTrips() {
        assertEquals(offer(), P2pOfferMessages.parse(P2pOfferMessages.format(offer())))
    }

    @Test
    fun anEmptyCaptionRoundTrips() {
        assertEquals("", P2pOfferMessages.parse(P2pOfferMessages.format(offer(caption = "")))!!.caption)
    }

    @Test
    fun aCaptionWithPipesOrControlCharsIsSanitised() {
        val body = P2pOfferMessages.format(offer(caption = "a|b\nc‭d"))
        val parsed = P2pOfferMessages.parse(body)!!
        assertFalse(parsed.caption.contains('|'))
        assertFalse(parsed.caption.contains('\n'))
        assertFalse(parsed.caption.contains('‭'))
    }

    @Test
    fun malformedOrHostileBodiesAreRejected() {
        val bad = listOf(
            "",
            "TYPE:P2P_OFFER",
            "TYPE:P2P_OFFER|NONCE:short|SHA:$sha|KEY:S0VZ|MIME:image/jpeg|SIZE:1",
            "TYPE:P2P_OFFER|NONCE:$nonce|SHA:nothex|KEY:S0VZ|MIME:image/jpeg|SIZE:1",
            "TYPE:P2P_OFFER|NONCE:$nonce|SHA:$sha|KEY:not base64!|MIME:image/jpeg|SIZE:1",
            "TYPE:P2P_OFFER|NONCE:$nonce|SHA:$sha|KEY:S0VZ|MIME:image/jpeg|SIZE:0",
            "TYPE:P2P_OFFER|NONCE:$nonce|SHA:$sha|KEY:S0VZ|MIME:image/jpeg|SIZE:-1",
            "TYPE:P2P_OFFER|NONCE:$nonce|SHA:$sha|KEY:S0VZ|MIME:image/jpeg|SIZE:999999999999",
            "TYPE:P2P_OFFER|NONCE:$nonce|SHA:$sha|KEY:S0VZ|SIZE:1", // no MIME
            "TYPE:PING|NONCE:$nonce",
        )
        for (b in bad) assertNull("should reject: ${b.take(50)}", P2pOfferMessages.parse(b))
    }

    @Test
    fun notAnOfferMessage() {
        assertFalse(P2pOfferMessages.isOfferMessage("hello"))
        assertFalse(P2pOfferMessages.isOfferMessage("TYPE:PONG|NONCE:$nonce"))
    }
}
