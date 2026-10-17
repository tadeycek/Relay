package com.relay.app.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class QrImagesTest {
    @Test fun exportedInvitationImagePreservesExactNonceAndExpiry() {
        val nonce="a".repeat(32)
        val expiry=1_900_000_000_000L
        val payload=QrContactCode.encode("Saved image","b".repeat(64),"AbCd+/12==",pairNonce=nonce,expiresAtMs=expiry)
        val matrix=QRCodeWriter().encode(payload,BarcodeFormat.QR_CODE,800,800)
        val pixels=IntArray(800*800) { i -> if(matrix.get(i%800,i/800)) 0xff000000.toInt() else 0xffffffff.toInt() }
        val raw=QrImages.decodePixels(800,800,pixels)
        assertEquals(payload,raw)
        val decoded=QrContactCode.decode(raw!!)!!
        assertEquals(nonce,decoded.pairNonce); assertEquals(expiry,decoded.expiresAtMs)
        // Reading the same image again cannot issue a new invitation or change its original expiry.
        assertEquals(raw,QrImages.decodePixels(800,800,pixels))
    }
    @Test fun unreadableOrOversizedImagesFailWithoutAPairingAttempt() {
        assertNull(QrImages.decodePixels(100,100,IntArray(10000) { -1 }))
        assertNull(QrImages.decodePixels(3000,3000,IntArray(0)))
    }
}
