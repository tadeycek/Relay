package com.relay.app.call.group.e2ee

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupFrameCryptoTest {
    private val owner = ParticipantDevice("a".repeat(64), "phone")
    private val member = ParticipantDevice("b".repeat(64), "desktop")
    private val schedule = GroupCallKeySchedule.create(ByteArray(32) { it.toByte() }, listOf(owner, member))
    private val keys = schedule.keyRing()
    private val key = schedule.aesKeyFor(member)
    private val plaintext = ByteArray(80) { (it * 3 + 1).toByte() }
    private val iv = ByteArray(12) { (it + 1).toByte() }

    private fun seal(p: ByteArray = plaintext, index: Int = 0, k: ByteArray? = key): ByteArray =
        (GroupFrameCrypto.seal(p, index, k, iv) as FrameSealResult.Sealed).frame

    @Test
    fun roundTripAndFrozenLayout() {
        val frame = seal()
        assertEquals(plaintext.size + 30, frame.size)
        assertEquals(plaintext[0], frame[0])
        assertEquals(12, frame[frame.size - 2].toInt())
        assertEquals(0, frame[frame.size - 1].toInt())
        assertArrayEquals(iv, frame.copyOfRange(frame.size - 14, frame.size - 2))

        val opened = GroupFrameCrypto.open(frame, member, keys) as FrameOpenResult.Opened
        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(0, opened.keyIndex)
    }

    @Test
    fun dtxSingleByteFrameRoundTrips() {
        val one = byteArrayOf(0x78)
        val frame = seal(one)
        assertEquals(31, frame.size)
        assertArrayEquals(one, (GroupFrameCrypto.open(frame, member, keys) as FrameOpenResult.Opened).plaintext)
    }

    @Test
    fun tamperedHeaderTagOrCiphertextIsRejected() {
        val header = seal().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(FrameReject.AUTHENTICATION_FAILED,
            (GroupFrameCrypto.open(header, member, keys) as FrameOpenResult.Rejected).reason)

        val tag = seal().also { it[1 + plaintext.size - 1] = (it[1 + plaintext.size - 1].toInt() xor 0x40).toByte() }
        assertEquals(FrameReject.AUTHENTICATION_FAILED,
            (GroupFrameCrypto.open(tag, member, keys) as FrameOpenResult.Rejected).reason)

        val body = seal().also { it[1] = (it[1].toInt() xor 0x80).toByte() }
        assertEquals(FrameReject.AUTHENTICATION_FAILED,
            (GroupFrameCrypto.open(body, member, keys) as FrameOpenResult.Rejected).reason)
    }

    @Test
    fun unknownRetiredAndWrongSenderIndicesFailClosed() {
        val wrongIndex = seal(index = 1)
        assertEquals(FrameReject.UNKNOWN_KEY_INDEX,
            (GroupFrameCrypto.open(wrongIndex, member, keys) as FrameOpenResult.Rejected).reason)

        val rotated = schedule.rotateForRemoval(listOf(member), ByteArray(32) { (it + 32).toByte() })
        val oldFrame = seal(index = 0)
        assertEquals(FrameReject.RETIRED_KEY_INDEX,
            (GroupFrameCrypto.open(oldFrame, member, rotated.keyRing()) as FrameOpenResult.Rejected).reason)

        val unknownSender = seal(index = 1)
        assertEquals(FrameReject.UNKNOWN_SENDER,
            (GroupFrameCrypto.open(unknownSender, owner, rotated.keyRing()) as FrameOpenResult.Rejected).reason)
    }

    @Test
    fun missingKeyNeverFallsBackToPlaintext() {
        assertEquals(FrameSealReject.INVALID_KEY,
            (GroupFrameCrypto.seal(plaintext, 0, null, iv) as FrameSealResult.Rejected).reason)
        assertEquals(FrameSealReject.INVALID_KEY,
            (GroupFrameCrypto.seal(plaintext, 0, ByteArray(15), iv) as FrameSealResult.Rejected).reason)

        val noKeys = object : FrameKeyLookup {
            override fun classify(sender: ParticipantDevice, keyIndex: Int) = FrameKeyStatus.CURRENT
            override fun keyFor(sender: ParticipantDevice, keyIndex: Int): ByteArray? = null
        }
        assertEquals(FrameReject.KEY_UNAVAILABLE,
            (GroupFrameCrypto.open(seal(), member, noKeys) as FrameOpenResult.Rejected).reason)
    }

    @Test
    fun plaintextTruncatedAndOversizeFramesAreRejected() {
        assertEquals(FrameReject.TOO_SHORT,
            (GroupFrameCrypto.open(byteArrayOf(0x78), member, keys) as FrameOpenResult.Rejected).reason)
        assertEquals(FrameReject.TOO_SHORT,
            (GroupFrameCrypto.open(ByteArray(0), member, keys) as FrameOpenResult.Rejected).reason)
        assertEquals(FrameReject.TOO_LARGE,
            (GroupFrameCrypto.open(ByteArray(GroupFrameCrypto.MAX_SEALED_BYTES + 1), member, keys)
                as FrameOpenResult.Rejected).reason)

        val badIvLen = seal().also { it[it.size - 2] = 13 }
        assertEquals(FrameReject.BAD_IV_LENGTH,
            (GroupFrameCrypto.open(badIvLen, member, keys) as FrameOpenResult.Rejected).reason)

        assertEquals(FrameSealReject.EMPTY_FRAME,
            (GroupFrameCrypto.seal(ByteArray(0), 0, key, iv) as FrameSealResult.Rejected).reason)
        assertEquals(FrameSealReject.TOO_LARGE,
            (GroupFrameCrypto.seal(ByteArray(GroupFrameCrypto.MAX_PLAINTEXT_BYTES + 1), 0, key, iv)
                as FrameSealResult.Rejected).reason)
        assertEquals(FrameSealReject.INVALID_IV,
            (GroupFrameCrypto.seal(plaintext, 0, key, ByteArray(11)) as FrameSealResult.Rejected).reason)
        assertEquals(FrameSealReject.KEY_INDEX_OUT_OF_RANGE,
            (GroupFrameCrypto.seal(plaintext, 256, key, iv) as FrameSealResult.Rejected).reason)
    }

    @Test
    fun wrongSenderKeyDoesNotOpen() {
        val frame = seal()
        val opened = GroupFrameCrypto.open(frame, owner, keys)
        assertEquals(FrameReject.AUTHENTICATION_FAILED, (opened as FrameOpenResult.Rejected).reason)
    }
}
