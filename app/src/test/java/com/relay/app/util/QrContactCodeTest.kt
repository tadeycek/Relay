package com.relay.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrContactCodeTest {

    private val npub = "a".repeat(64)
    private val key = "AbCd+/12=="

    @Test
    fun v3RoundTripsAllFields() {
        val raw = QrContactCode.encode(
            name = "Ana",
            nostrPubkeyHex = npub,
            publicKeyBase64 = key,
            signingPublicKeyBase64 = "SIG+/9==",
            relayHints = listOf("wss://relay.one", "wss://relay.two"),
        )
        val d = QrContactCode.decode(raw)!!
        assertEquals("Ana", d.name)
        assertEquals(npub, d.nostrPubkeyHex)
        assertEquals(key, d.publicKeyBase64)
        assertEquals("SIG+/9==", d.signingPublicKeyBase64)
        assertEquals(listOf("wss://relay.one", "wss://relay.two"), d.relayHints)
        assertNull("v3 carries no phone number", d.phone)
    }

    @Test
    fun v3WithoutOptionalFields() {
        val d = QrContactCode.decode(QrContactCode.encode("Bo", npub, key))!!
        assertNull(d.signingPublicKeyBase64)
        assertEquals(emptyList<String>(), d.relayHints)
    }

    @Test
    fun v3EncodeDoesNotContainAPhoneField() {
        assertEquals(false, QrContactCode.encode("Bo", npub, key).contains("PHONE"))
    }

    @Test
    fun legacyV1AndV2StillDecodeWithPhoneAndNoNostrKey() {
        val v2 = "RELAYQR:2|PHONE:+38640111222|NAME:Old Friend|KEY:$key|SIGKEY:SIG=="
        val d2 = QrContactCode.decode(v2)!!
        assertEquals("+38640111222", d2.phone)
        assertEquals("Old Friend", d2.name)
        assertNull(d2.nostrPubkeyHex)
        assertEquals("SIG==", d2.signingPublicKeyBase64)

        val v1 = "RELAYQR:1|PHONE:+38640111222|NAME:Old|KEY:$key"
        assertNotNull(QrContactCode.decode(v1))
    }

    @Test
    fun v3RequiresAValidNostrKey() {
        assertNull(QrContactCode.decode("RELAYQR:3|NAME:x|KEY:$key"))
        assertNull(QrContactCode.decode("RELAYQR:3|NAME:x|NPUB:zz|KEY:$key"))
        assertNull(QrContactCode.decode("RELAYQR:3|NAME:x|NPUB:${"a".repeat(63)}|KEY:$key"))
        assertNull(QrContactCode.decode("RELAYQR:3|NAME:x|NPUB:${"g".repeat(64)}|KEY:$key"))
    }

    @Test
    fun uppercaseHexIsNormalisedToLowercase() {
        val d = QrContactCode.decode("RELAYQR:3|NAME:x|NPUB:${"AB".repeat(32)}|KEY:$key")!!
        assertEquals("ab".repeat(32), d.nostrPubkeyHex)
    }

    @Test
    fun rejectsGarbageAndMalformedFields() {
        assertNull(QrContactCode.decode(""))
        assertNull(QrContactCode.decode("hello"))
        assertNull(QrContactCode.decode("RELAYQR:x|NAME:a"))
        assertNull(QrContactCode.decode("RELAYQR:0|PHONE:1|KEY:$key"))
        assertNull(QrContactCode.decode("RELAYQR:3|NAME:x|NPUB:$npub|KEY:not base64!"))
        assertNull(QrContactCode.decode("RELAYQR:3|NAME:x|NPUB:$npub|KEY:"))
        assertNull(QrContactCode.decode("RELAYQR:3|NAME:x|NPUB:$npub|KEY:$key|broken"))
        assertNull(QrContactCode.decode("RELAYQR:2|NAME:x|KEY:$key")) // legacy needs a phone
    }

    @Test
    fun nameIsSanitisedAndCapped() {
        val evil = "Ev‮il\nName|with|pipes" + "x".repeat(60)
        val raw = QrContactCode.encode(evil, npub, key)
        val d = QrContactCode.decode(raw)!!
        assertEquals(false, d.name.contains('|'))
        assertEquals(false, d.name.contains('\n'))
        assertEquals(false, d.name.contains('‮'))
        assert(d.name.length <= 30)
    }

    @Test
    fun blankNameFallsBackToKeyPrefix() {
        val d = QrContactCode.decode("RELAYQR:3|NAME:|NPUB:$npub|KEY:$key")!!
        assertEquals("Contact aaaaaaaa", d.name)
    }

    @Test
    fun relayHintsAreFilteredDedupedAndBounded() {
        val hints = QrContactCode.cleanRelayHints(
            listOf(
                "wss://ok.example", "wss://ok.example", "ws://insecure.example", "https://nope",
                "wss://has space.example", "wss://", "wss://" + "a".repeat(200),
                "wss://a1", "wss://a2", "wss://a3", "wss://a4", "wss://a5", "wss://a6",
            )
        )
        assertEquals(listOf("wss://ok.example", "wss://a1", "wss://a2", "wss://a3", "wss://a4"), hints)
    }
}
