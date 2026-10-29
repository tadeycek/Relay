package com.relay.app.call.group.e2ee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCallKeyScheduleTest {
    private val owner = ParticipantDevice("a".repeat(64), "phone")
    private val member = ParticipantDevice("b".repeat(64), "desktop")
    private val callKey = ByteArray(32) { it.toByte() }

    /** Vector generated independently with Python hashlib/hmac (HKDF-SHA256, PBKDF2-HMAC-SHA256). */
    @Test
    fun fixedInteropVector() {
        val schedule = GroupCallKeySchedule.create(callKey, listOf(owner, member))
        assertEquals("75fe2452eaecb31bc42856a63a62a76af1aa0f15199a80af15ca12a7b6d2a58f",
            GroupCallKeySchedule.hkdfSalt().toHex())
        assertEquals("86b151c8a8815b0205d5f0648fc45c1ba47946bb85af3ddfc9647d69bf3e6ca8",
            schedule.ratchetSaltHex())
        assertEquals("39b2dafb181089a8991e2b0c56fdd038b5068e368bddb62bb1d52c0833c1cfdb",
            schedule.materialFor(owner).toString(Charsets.US_ASCII))
        assertEquals("46dfb90239209d6ec81a7739d8e6436d", schedule.aesKeyFor(owner).toHex())
        assertEquals("7ff05f6f95077c30509910da25676292a584a590140fe5077283674357d06987",
            schedule.materialFor(member).toString(Charsets.US_ASCII))
        assertEquals("fc7f5ad8597fe3ea0e69e46acb6d7d83", schedule.aesKeyFor(member).toHex())
        assertEquals(0, schedule.keyIndex)
        assertEquals(GroupCallKeySchedule.PBKDF2_ITERATIONS, 100_000)
    }

    @Test
    fun deterministicRegardlessOfInputOrderAndInstance() {
        val a = GroupCallKeySchedule.create(callKey, listOf(owner, member))
        val b = GroupCallKeySchedule.fromCallKeyHex(callKey.toHex(), listOf(member, owner))
        assertEquals(a.participants, b.participants)
        assertEquals(a.ratchetSaltHex(), b.ratchetSaltHex())
        assertEquals(a.aesKeyFor(owner).toHex(), b.aesKeyFor(owner).toHex())
        assertEquals(a.materialFor(member).toHex(), b.materialFor(member).toHex())
        assertFalse(a.aesKeyFor(owner).contentEquals(a.aesKeyFor(member)))
    }

    @Test
    fun rotationRetiresOldEpochAndRequiresFreshKey() {
        val old = GroupCallKeySchedule.create(callKey, listOf(owner, member))
        val newKey = ByteArray(32) { (it + 32).toByte() }
        val rotated = old.rotateForRemoval(listOf(member), newKey)
        assertEquals(1, rotated.epoch)
        assertEquals(setOf(0), rotated.retiredEpochs)
        assertEquals("6a1be5a6d122d928f3bed691ea757968ac8256db59ca644ffb5ee8e5bea59627",
            rotated.ratchetSaltHex())
        assertEquals("db9aae0439bf962c6b3958ec803c1088", rotated.aesKeyFor(member).toHex())
        assertFalse(rotated.aesKeyFor(member).contentEquals(old.aesKeyFor(member)))

        val keys = rotated.keyRing()
        assertEquals(FrameKeyStatus.RETIRED, keys.classify(member, 0))
        assertEquals(FrameKeyStatus.UNKNOWN_KEY_INDEX, keys.classify(member, 2))
        assertEquals(FrameKeyStatus.CURRENT, keys.classify(member, 1))
        assertEquals(FrameKeyStatus.UNKNOWN_SENDER, keys.classify(owner, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rotationWithReusedCallKeyIsRejected() {
        GroupCallKeySchedule.create(callKey, listOf(owner, member))
            .rotateForRemoval(listOf(member), callKey.copyOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rotationCannotAddParticipants() {
        val third = ParticipantDevice("c".repeat(64), "tablet")
        GroupCallKeySchedule.create(callKey, listOf(owner, member))
            .rotateForRemoval(listOf(owner, member, third), ByteArray(32) { 7 })
    }

    @Test
    fun createAtEpochMatchesTheRotatedSchedule() {
        val newKey = ByteArray(32) { (it + 32).toByte() }
        val third = ParticipantDevice("c".repeat(64), "tablet")
        val rotated = GroupCallKeySchedule.create(callKey, listOf(owner, member, third))
            .rotateForRemoval(listOf(owner, member), newKey)
        // What a device that missed epoch 0 can rebuild from (newKey, epoch 1, roster):
        val late = GroupCallKeySchedule.create(newKey, rotated.epoch, listOf(member, owner))
        assertEquals(rotated.epoch, late.epoch)
        assertEquals(1, late.keyIndex)
        assertEquals(rotated.ratchetSaltHex(), late.ratchetSaltHex())
        for (p in listOf(owner, member)) {
            assertEquals(rotated.materialFor(p).toString(Charsets.US_ASCII), late.materialFor(p).toString(Charsets.US_ASCII))
            assertEquals(rotated.aesKeyFor(p).toHex(), late.aesKeyFor(p).toHex())
        }
        // It never held epoch 0, so an epoch-0 frame is rejected like a retired one, never decrypted.
        assertEquals(FrameKeyStatus.UNKNOWN_KEY_INDEX, late.keyRing().classify(owner, 0))
        assertEquals(FrameKeyStatus.CURRENT, late.keyRing().classify(owner, 1))
        // A late joiner that is not in the epoch's participant set is rejected outright.
        val fourth = ParticipantDevice("d".repeat(64), "laptop")
        assertEquals(FrameKeyStatus.UNKNOWN_SENDER, late.keyRing().classify(fourth, 1))
    }

    @Test
    fun epochZeroCreateIsUnchanged() {
        val plain = GroupCallKeySchedule.create(callKey, listOf(owner, member))
        val same = GroupCallKeySchedule.create(callKey, 0, listOf(owner, member))
        assertEquals(plain.ratchetSaltHex(), same.ratchetSaltHex())
        assertEquals(plain.aesKeyFor(owner).toHex(), same.aesKeyFor(owner).toHex())
        assertEquals(plain.retiredEpochs, same.retiredEpochs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun epochOutsideTheOneByteSelectorIsRejected() {
        GroupCallKeySchedule.create(callKey, 256, listOf(owner))
    }
}
