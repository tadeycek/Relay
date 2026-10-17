package com.relay.app.groups

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupAvatarBodyTest {
    @Test fun avatarAndRemovalRoundTripWithoutTouchingMembership() {
        val group=UUID.randomUUID().toString()
        val jpeg=byteArrayOf(-1,-40,-1,12)
        val parsed=GroupAvatarBody.parse(GroupAvatarBody.format(GroupAvatarBody.Update(group,5,jpeg)))!!
        assertEquals(group,parsed.group); assertEquals(5,parsed.revision); assertArrayEquals(jpeg,parsed.jpeg)
        assertNull(GroupAvatarBody.parse(GroupAvatarBody.format(GroupAvatarBody.Update(group,6,null)))!!.jpeg)
        assertNull(GroupAvatarBody.parse(GroupAvatarBody.PREFIX+"{}"))
    }
    @Test fun maximumAvatarFitsActualHpkePayloadLimit() {
        com.google.crypto.tink.config.TinkConfig.register()
        val keys=com.google.crypto.tink.KeysetHandle.generateNew(com.google.crypto.tink.KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"))
        val bytes=ByteArray(8192).apply { this[0]=-1; this[1]=-40; this[2]=-1 }
        val body=GroupAvatarBody.format(GroupAvatarBody.Update(UUID.randomUUID().toString(),Long.MAX_VALUE,bytes))
        val encrypted=keys.publicKeysetHandle.getPrimitive(com.google.crypto.tink.HybridEncrypt::class.java).encrypt(body.toByteArray(),null)
        val wire=com.relay.app.util.SmsMessageParser.formatEncrypted(java.util.Base64.getEncoder().encodeToString(encrypted))
        assertTrue(wire.length < com.relay.app.transport.PayloadCodec.MAX_BODY_LENGTH)
    }
}
