package com.relay.app.call.group

import com.relay.app.groups.GroupProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * G5 signed roster attestation. The golden vector was produced independently with `@noble/curves` (the same
 * BIP-340 implementation the Worker uses for NIP-98), so this file pins the wire format across Kotlin and
 * TypeScript instead of only proving self-consistency.
 */
class RosterAttestationTest {

    private val group = "00000000-0000-4000-8000-000000000001"
    private val room = "aa".repeat(16)
    private val ownerSk = hex("5267ff4dfbaf0a58e442dcee351fe5a083797bad1ca3856208219d748d7365dd")
    private val ownerPk = "66ba53f7e83dd43e33cb84f1d72f7eff553bb49824f1325c8326985dc92d6b38"
    private val goldenMembers = listOf("11".repeat(32), "22".repeat(32), "33".repeat(32), "66ba53f7e83dd43e33cb84f1d72f7eff553bb49824f1325c8326985dc92d6b38")
    private val goldenHash = "89672e0fd4f86b7aa2fa615401b65860c0008ad29775d009a6788534597ca85d"
    private val goldenSig = "u8olEm/1d05p0KCQJKNKcpu0zeS1VTcnRo6hWTYVziOM3M54YSBMQwJYQd4LGBwbVfVzRGdKzk42AHge66byAw=="

    private val goldenCanonical = "relay-call-roster-attestation-v1\n" +
        "v=1\n" +
        "groupId=$group\n" +
        "revision=7\n" +
        "rosterHash=$goldenHash\n" +
        "members=${goldenMembers.joinToString(",")}\n" +
        "room=$room\n" +
        "issuedAt=1700000000\n" +
        "expiresAt=1700000900"

    private fun golden() = RosterAttestation(group, 7, goldenHash, goldenMembers, room, 1_700_000_000, 1_700_000_900, ownerPk, goldenSig)

    private fun expectation() = RosterAttestationExpectation(group, 7, goldenHash, room)

    @Test
    fun `canonical bytes are exactly the cross-language golden string`() {
        assertArrayEquals(goldenCanonical.toByteArray(Charsets.UTF_8), RosterAttestations.canonicalBytes(golden()))
        assertEquals("0f396b6b5d0e30d6fef1ffb63261fb39f4516b7ac1d1d3246de4202bee52a241",
            RosterAttestations.digest(golden()).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `a signature made by the reference implementation verifies`() {
        assertNull(RosterAttestations.verify(golden(), expectation(), 1_700_000_100))
    }

    @Test
    fun `sign with this device's key round-trips through canonical bytes, base64 and strict json`() {
        val template = RosterAttestation(group, 7, goldenHash, goldenMembers, room, 1_700_000_000, 1_700_000_900, ownerPk)
        val signed = RosterAttestations.sign(template, RosterAttestationSigner { digest -> signDigest(ownerSk, digest) })
        assertNotNull(signed)
        assertEquals(88, signed!!.signature!!.length)
        assertNull(RosterAttestations.verify(signed, expectation(), 1_700_000_100))
        val parsed = RosterAttestations.parse(RosterAttestations.encode(signed))
        assertEquals(signed, parsed)
        assertNull(RosterAttestations.verify(parsed!!, expectation(), 1_700_000_100))
    }

    @Test
    fun `editing any signed field breaks verification`() {
        val good = golden()
        // Room, revision and group are checked before the signature, so they surface as binding errors.
        assertEquals(RosterAttestationReject.WRONG_BINDING, RosterAttestations.verify(good.copy(room = "bb".repeat(16)), expectation(), 1_700_000_100))
        assertEquals(RosterAttestationReject.WRONG_BINDING, RosterAttestations.verify(good.copy(revision = 8, rosterHash = RosterAttestations.rosterHash(group, ownerPk, 8, goldenMembers)), expectation(), 1_700_000_100))
        assertEquals(RosterAttestationReject.BAD_SIGNATURE, RosterAttestations.verify(good.copy(issuedAt = 1_700_000_001), expectation(), 1_700_000_100))
        // The group id is inside the roster hash, so moving the attestation to another group is malformed, not merely
        // a binding mismatch: the claimed roster hash no longer describes the roster.
        assertEquals(RosterAttestationReject.MALFORMED, RosterAttestations.verify(good.copy(groupId = "00000000-0000-4000-8000-000000000002"), expectation(), 1_700_000_100))
        // A consistent but different roster (hash recomputed) must fail on the signature, not slip through.
        val members = listOf("11".repeat(32), "22".repeat(32), "44".repeat(32), ownerPk)
        val edited = good.copy(members = members, rosterHash = RosterAttestations.rosterHash(group, ownerPk, 7, members))
        val editedExpectation = expectation().copy(rosterHash = edited.rosterHash)
        assertEquals(RosterAttestationReject.BAD_SIGNATURE, RosterAttestations.verify(edited, editedExpectation, 1_700_000_100))
        // Swapping the owner without rehashing is inconsistent; rehashing does not help either, because the owner is
        // part of the signed roster hash and the old signature no longer covers it.
        val otherPk = pubkeyOf(hex("11".repeat(32)))
        assertEquals(RosterAttestationReject.MALFORMED, RosterAttestations.verify(good.copy(owner = otherPk), expectation(), 1_700_000_100))
        val movedMembers = (goldenMembers - ownerPk + otherPk).sorted()
        val movedOwner = good.copy(owner = otherPk, members = movedMembers, rosterHash = RosterAttestations.rosterHash(group, otherPk, 7, movedMembers))
        assertEquals(RosterAttestationReject.BAD_SIGNATURE, RosterAttestations.verify(movedOwner, expectation().copy(rosterHash = movedOwner.rosterHash), 1_700_000_100))
        val flipped = Base64.getDecoder().decode(good.signature).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(RosterAttestationReject.BAD_SIGNATURE,
            RosterAttestations.verify(good.copy(signature = Base64.getEncoder().encodeToString(flipped)), expectation(), 1_700_000_100))
    }

    @Test
    fun `stale and future attestations are refused on a bounded clock window`() {
        val good = golden()
        assertEquals(RosterAttestationReject.EXPIRED, RosterAttestations.verify(good, expectation(), 1_700_000_900))
        assertEquals(RosterAttestationReject.STALE, RosterAttestations.verify(good, expectation(), 1_700_000_000 + 301))
        assertEquals(RosterAttestationReject.STALE, RosterAttestations.verify(good, expectation(), 1_700_000_000 - 301))
        assertNull(RosterAttestations.verify(good, expectation(), 1_700_000_000 + 300))
        assertNull(RosterAttestations.verify(good, expectation(), 1_700_000_000 - 300))
        assertEquals(RosterAttestationReject.STALE,
            RosterAttestations.fresh(good.copy(issuedAt = 1_700_000_000, expiresAt = 1_700_001_000), 1_700_000_100))
    }

    @Test
    fun `unsigned templates cannot be verified, encoded or signed twice`() {
        val template = golden().copy(signature = null)
        assertEquals(RosterAttestationReject.UNSIGNED, RosterAttestations.verify(template, expectation(), 1_700_000_100))
        assertTrue(runCatching { RosterAttestations.encode(template) }.isFailure)
        assertNull(RosterAttestations.sign(golden(), RosterAttestationSigner { ByteArray(64) }))
    }

    @Test
    fun `a signer that returns the wrong length or throws yields no attestation`() {
        val template = golden().copy(signature = null)
        assertNull(RosterAttestations.sign(template, RosterAttestationSigner { ByteArray(63) }))
        assertNull(RosterAttestations.sign(template, RosterAttestationSigner { throw IllegalStateException("locked") }))
    }

    @Test
    fun `bounded parsing refuses malformed, oversized and non-canonical documents`() {
        val wire = RosterAttestations.encode(golden())
        assertNotNull(RosterAttestations.parse(wire))
        assertNull(RosterAttestations.parse(wire.replace("\"v\":1", "\"v\":2")))
        assertNull(RosterAttestations.parse(wire.replace("\"owner\":", "\"extra\":1,\"owner\":")))
        assertNull(RosterAttestations.parse(wire.replace("\"room\":\"$room\"", "\"room\":\"$room\",\"owner2\":\"x\"")))
        assertNull(RosterAttestations.parse("x".repeat(RosterAttestations.MAX_BYTES + 1)))
        assertNull(RosterAttestations.parse(null))
        assertNull(RosterAttestations.parse("{"))
        // Members must arrive sorted and unique; a valid set in the wrong order is refused, not repaired.
        val unsortedWire = wire.replace("[${goldenMembers.joinToString(",") { "\"$it\"" }}]",
            "[${goldenMembers.reversed().joinToString(",") { "\"$it\"" }}]")
        assertNull(RosterAttestations.parse(unsortedWire))
        // A signature that is not exactly 64 base64 bytes is not an attestation.
        assertNull(RosterAttestations.parse(wire.replace(goldenSig, Base64.getEncoder().encodeToString(ByteArray(65)))))
        assertNull(RosterAttestations.parse(wire.replace(goldenSig, "not base64 !!")))
        // The roster hash must equal the hash of the members it claims.
        assertNull(RosterAttestations.parse(wire.replace(goldenHash, "00".repeat(32))))
    }

    @Test
    fun `roster hash matches the group call codec`() {
        val state = GroupProtocol.State(group, ownerPk, "Group", 7,
            goldenMembers.map { GroupProtocol.Member(it, "m") })
        assertEquals(GroupCallCodec.rosterHash(state), RosterAttestations.rosterHash(group, ownerPk, 7, goldenMembers))
    }

    // ---- minimal BIP-340 signer, test-only; the golden vector above is the independent reference ----

    private fun signDigest(secret: ByteArray, message: ByteArray): ByteArray {
        require(message.size == 32)
        val n = N
        var d = BigInteger(1, secret).mod(n)
        require(d.signum() != 0)
        val p = multiply(d, G)
        if (p.y.testBit(0)) d = n.subtract(d)
        val pub = to32(p.x)
        val aux = ByteArray(32)
        val tagHash = MessageDigest.getInstance("SHA-256").digest("BIP0340/aux".toByteArray(Charsets.UTF_8))
        val t = xor32(to32(d), tagged(tagHash, aux))
        val nonce = BigInteger(1, tagged(hash256("BIP0340/nonce".toByteArray(Charsets.UTF_8)), t + pub + message)).mod(n)
        require(nonce.signum() != 0)
        val r = multiply(nonce, G)
        val k = if (r.y.testBit(0)) n.subtract(nonce) else nonce
        val e = BigInteger(1, tagged(hash256("BIP0340/challenge".toByteArray(Charsets.UTF_8)), to32(r.x) + pub + message)).mod(n)
        val s = k.add(e.multiply(d)).mod(n)
        return to32(r.x) + to32(s)
    }

    private fun pubkeyOf(secret: ByteArray): String {
        var d = BigInteger(1, secret).mod(N)
        val p = multiply(d, G)
        if (p.y.testBit(0)) d = N.subtract(d)
        return to32(p.x).joinToString("") { "%02x".format(it) }
    }

    private class Point(val x: BigInteger, val y: BigInteger)

    private fun multiply(k: BigInteger, point: Point): Point {
        var scalar = k.mod(N)
        var result: Point? = null
        var addend: Point? = point
        while (scalar.signum() > 0) {
            if (scalar.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            scalar = scalar.shiftRight(1)
        }
        return result!!
    }

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x) {
            if (a.y != b.y || a.y.signum() == 0) return null
            val slope = a.x.modPow(TWO, P).multiply(THREE).mod(P).multiply(a.y.multiply(TWO).modInverse(P)).mod(P)
            val x = slope.modPow(TWO, P).subtract(a.x.multiply(TWO)).mod(P)
            return Point(x, slope.multiply(a.x.subtract(x)).subtract(a.y).mod(P))
        }
        val slope = b.y.subtract(a.y).mod(P).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        val x = slope.modPow(TWO, P).subtract(a.x).subtract(b.x).mod(P)
        return Point(x, slope.multiply(a.x.subtract(x)).subtract(a.y).mod(P))
    }

    private fun tagged(tagHash: ByteArray, message: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(message)
        return digest.digest()
    }

    private fun hash256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun to32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        src.copyInto(out, 32 - src.size)
        return out
    }

    private fun xor32(a: ByteArray, b: ByteArray) = ByteArray(32) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
        val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
        val G = Point(BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16),
            BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16))
        val TWO = BigInteger.valueOf(2)
        val THREE = BigInteger.valueOf(3)
    }
}
