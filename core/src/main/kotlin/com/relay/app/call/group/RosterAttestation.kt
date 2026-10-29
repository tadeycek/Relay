package com.relay.app.call.group

import com.relay.app.call.StrictJson
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/**
 * G5 signed group roster (WP15/WP16 contract).
 *
 * The frozen member list of a group-call room is claimed by whoever creates the room, so an enrolled member who
 * names a group UUID could otherwise squat the group's revision slot or supersede a live call. This file defines
 * the owner's attestation over one roster revision: a BIP-340 Schnorr signature (the owner's existing Nostr
 * secp256k1 key) over the SHA-256 of a fixed canonical byte string. The server verifies exactly the same bytes
 * (`server/cloudflare/src/callroom.ts`); both sides must stay byte-identical, so the format is quoted verbatim in
 * `PROTOCOL.md` and pinned by golden vectors on both sides.
 *
 * Scope and honesty:
 *  - This is not E2EE key management and does not touch the SFU. It binds group id, revision, roster hash, the
 *    sorted member set, room id and attestation lifetime to the owner's key.
 *  - A compromised owner key still wins: the signature proves "the owner's key signed this roster", nothing more.
 *  - Verification is plain `BigInteger` secp256k1 arithmetic: correct and bounded, **not constant-time**. It handles
 *    only public data (a signature and a public key), so no secret is exposed by timing here, but do not reuse this
 *    arithmetic for signing or for secret-dependent work.
 *  - `:core` never holds a secret key. Signing goes through [RosterAttestationSigner], which Android implements over
 *    the rust-nostr `Keys` and the desktop over its secret store; the owner's key never enters this module.
 */

/**
 * Signs a 32-byte digest with the owner's Nostr secp256k1 key (BIP-340). Returns 64 raw bytes, or null when the
 * key is unavailable or locked. Implementations MUST NOT sign arbitrary caller-supplied text: the caller passes the
 * digest of [RosterAttestations.canonicalBytes].
 */
fun interface RosterAttestationSigner {
    fun signSchnorr(digest32: ByteArray): ByteArray?
}

/**
 * One roster revision as the owner signed it. [signature] is standard base64 of the 64-byte Schnorr signature;
 * null means an unsigned template, which can be canonicalised but never verified or sent.
 * [members] is sorted ascending and deduplicated (strictly ascending), owner included, at most
 * [RosterAttestations.MAX_MEMBERS].
 */
data class RosterAttestation(
    val groupId: String,
    val revision: Long,
    val rosterHash: String,
    val members: List<String>,
    val room: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val owner: String,
    val signature: String? = null,
) {
    /** Never let a signature or the member list reach a log line by accident. */
    override fun toString(): String =
        "RosterAttestation(group=$groupId, revision=$revision, room=$room, owner=$owner, members=${members.size})"
}

enum class RosterAttestationReject {
    /** Wrong shape, wrong field types, extra keys, non-canonical member order, signature not 64 base64 bytes. */
    MALFORMED,
    /** Input over [RosterAttestations.MAX_BYTES]; rejected before parsing. */
    TOO_LARGE,
    /** The attestation names a different group than the caller's roster. */
    WRONG_GROUP,
    /** A different revision, roster hash or room than the caller expects. */
    WRONG_BINDING,
    /** `expiresAt` has passed (or is not after `issuedAt`). */
    EXPIRED,
    /** `issuedAt` is too far from the verifier's clock, or the attested lifetime is too long. */
    STALE,
    /** No signature present: an unsigned template is not proof. */
    UNSIGNED,
    /** The base64 signature is not 64 bytes, the key is not a valid x-only point, or BIP-340 verification fails. */
    BAD_SIGNATURE,
}

/** Everything a verifier already knows and expects the attestation to repeat exactly. */
data class RosterAttestationExpectation(
    val groupId: String,
    val revision: Long,
    val rosterHash: String,
    val room: String,
)

object RosterAttestations {
    const val VERSION = 1
    /** A maximal attestation (64 members) is well under this; anything larger is refused unparsed. */
    const val MAX_BYTES = 8 * 1024
    const val MAX_MEMBERS = 64
    const val MAX_REVISION = 2_147_483_647L
    /** Longest attested validity window. A group invite lives 10 minutes; this leaves room for a re-issued start. */
    const val MAX_LIFETIME_S = 900L
    /** Tolerated clock difference between the owner's device and the verifier, in either direction. */
    const val CLOCK_SKEW_S = 300L
    /** Domain separator: the first canonical line and the purpose of the signature. */
    const val DOMAIN = "relay-call-roster-attestation-v1"

    private val hex64 = Regex("[0-9a-f]{64}")
    private val hex32 = Regex("[0-9a-f]{32}")
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val keys = setOf("v", "groupId", "revision", "rosterHash", "members", "room", "issuedAt", "expiresAt",
        "owner", "signature")

    /** The roster hash the server and this module recompute; identical to [GroupCallCodec.rosterHash]. */
    fun rosterHash(groupId: String, owner: String, revision: Long, members: List<String>): String {
        require(validBase(groupId, owner, revision, members)) { "invalid roster" }
        val canonical = "relay-group-call-roster-v1\n$groupId\n$owner\n$revision\n" + members.sorted().joinToString("\n")
        return sha256Hex(canonical.toByteArray(Charsets.UTF_8))
    }

    /**
     * The exact bytes the owner signs, UTF-8, LF-separated, no trailing newline:
     * ```
     * relay-call-roster-attestation-v1
     * v=1
     * groupId=<uuid>
     * revision=<decimal>
     * rosterHash=<64 hex>
     * members=<sorted 64-hex pubkeys, comma separated>
     * room=<32 hex>
     * issuedAt=<decimal seconds>
     * expiresAt=<decimal seconds>
     * ```
     * The signature is BIP-340 over `SHA-256(these bytes)`; `owner` and `signature` are not inside the message.
     */
    fun canonicalBytes(a: RosterAttestation): ByteArray {
        require(validBase(a.groupId, a.owner, a.revision, a.members)) { "invalid roster" }
        require(hex64.matches(a.rosterHash) && hex32.matches(a.room)) { "invalid binding" }
        require(a.issuedAt > 0 && a.expiresAt > a.issuedAt && a.expiresAt - a.issuedAt <= MAX_LIFETIME_S) { "invalid lifetime" }
        require(a.rosterHash == rosterHash(a.groupId, a.owner, a.revision, a.members)) { "roster hash mismatch" }
        val members = a.members.sorted().joinToString(",")
        return (DOMAIN +
            "\nv=1" +
            "\ngroupId=${a.groupId}" +
            "\nrevision=${a.revision}" +
            "\nrosterHash=${a.rosterHash}" +
            "\nmembers=$members" +
            "\nroom=${a.room}" +
            "\nissuedAt=${a.issuedAt}" +
            "\nexpiresAt=${a.expiresAt}").toByteArray(Charsets.UTF_8)
    }

    fun digest(a: RosterAttestation): ByteArray = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(a))

    /** Signs the template with the owner's key. Null when the signer refuses or returns a non-signature. */
    fun sign(template: RosterAttestation, signer: RosterAttestationSigner): RosterAttestation? {
        if (template.signature != null) return null
        val sig = runCatching { signer.signSchnorr(digest(template)) }.getOrNull() ?: return null
        if (sig.size != 64) return null
        return template.copy(signature = Base64.getEncoder().encodeToString(sig))
    }

    fun encode(a: RosterAttestation): String {
        require(a.signature != null) { "unsigned attestation" }
        require(isWellFormed(a)) { "invalid attestation" }
        val out = "{\"v\":1,\"groupId\":\"${a.groupId}\",\"revision\":${a.revision},\"rosterHash\":\"${a.rosterHash}\"," +
            "\"members\":[${a.members.joinToString(",") { "\"$it\"" }}],\"room\":\"${a.room}\"," +
            "\"issuedAt\":${a.issuedAt},\"expiresAt\":${a.expiresAt},\"owner\":\"${a.owner}\"," +
            "\"signature\":\"${a.signature}\"}"
        require(out.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "attestation too large" }
        return out
    }

    /** Bounded, strict parse. Unknown keys, wrong types, unsorted members or an inconsistent roster hash: null. */
    fun parse(wire: String?): RosterAttestation? {
        if (wire == null || wire.length > MAX_BYTES || wire.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val obj = try { StrictJson.parse(wire) as? Map<*, *> } catch (_: Exception) { null } ?: return null
        if (obj.keys != keys) return null
        if (obj["v"] != 1L) return null
        val groupId = obj["groupId"] as? String ?: return null
        val revision = obj["revision"] as? Long ?: return null
        val rosterHash = obj["rosterHash"] as? String ?: return null
        val room = obj["room"] as? String ?: return null
        val issuedAt = obj["issuedAt"] as? Long ?: return null
        val expiresAt = obj["expiresAt"] as? Long ?: return null
        val owner = obj["owner"] as? String ?: return null
        val signature = obj["signature"] as? String ?: return null
        val rawMembers = obj["members"] as? List<*> ?: return null
        if (rawMembers.any { it !is String }) return null
        val members = rawMembers.map { it as String }
        val a = RosterAttestation(groupId, revision, rosterHash, members, room, issuedAt, expiresAt, owner, signature)
        return if (isWellFormed(a)) a else null
    }

    /**
     * Full check for a received attestation: canonical shape, binding to what the caller expects, freshness and the
     * owner's signature. Null means accepted. [nowSeconds] is the verifier's clock.
     */
    fun verify(a: RosterAttestation, expected: RosterAttestationExpectation, nowSeconds: Long): RosterAttestationReject? {
        if (!isWellFormed(a)) return RosterAttestationReject.MALFORMED
        if (a.signature == null) return RosterAttestationReject.UNSIGNED
        if (a.groupId != expected.groupId) return RosterAttestationReject.WRONG_GROUP
        if (a.revision != expected.revision || a.rosterHash != expected.rosterHash || a.room != expected.room) {
            return RosterAttestationReject.WRONG_BINDING
        }
        fresh(a, nowSeconds)?.let { return it }
        val sig = decodeSignature(a.signature) ?: return RosterAttestationReject.BAD_SIGNATURE
        return if (Schnorr.verify(sig, digest(a), a.owner)) null else RosterAttestationReject.BAD_SIGNATURE
    }

    /** Freshness of an already well-formed attestation. Used by the server-side and client-side callers alike. */
    fun fresh(a: RosterAttestation, nowSeconds: Long): RosterAttestationReject? {
        if (a.expiresAt <= a.issuedAt || a.expiresAt - a.issuedAt > MAX_LIFETIME_S) return RosterAttestationReject.STALE
        if (nowSeconds >= a.expiresAt) return RosterAttestationReject.EXPIRED
        if (a.issuedAt > nowSeconds + CLOCK_SKEW_S) return RosterAttestationReject.STALE
        if (a.issuedAt < nowSeconds - CLOCK_SKEW_S) return RosterAttestationReject.STALE
        return null
    }

    /** Shape only: no freshness, no signature. `parse` and `encode` both require this. */
    fun isWellFormed(a: RosterAttestation): Boolean =
        validBase(a.groupId, a.owner, a.revision, a.members) && hex64.matches(a.rosterHash) && hex32.matches(a.room) &&
            a.issuedAt > 0 && a.expiresAt > a.issuedAt && a.expiresAt - a.issuedAt <= MAX_LIFETIME_S &&
            a.rosterHash == rosterHash(a.groupId, a.owner, a.revision, a.members) &&
            (a.signature == null || decodeSignature(a.signature) != null)

    private fun validBase(groupId: String, owner: String, revision: Long, members: List<String>): Boolean {
        if (!uuid.matches(groupId) || !hex64.matches(owner)) return false
        if (revision < 1 || revision > MAX_REVISION) return false
        if (members.size !in 1..MAX_MEMBERS || members.none { it == owner }) return false
        for (i in members.indices) {
            if (!hex64.matches(members[i])) return false
            if (i > 0 && !(members[i - 1] < members[i])) return false
        }
        return true
    }

    private fun decodeSignature(value: String?): ByteArray? {
        if (value == null || value.length != 88) return null
        return try {
            Base64.getDecoder().decode(value).takeIf { it.size == 64 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** BIP-340 verification only, over public data. Not constant-time; never used for signing. */
internal object Schnorr {
    private val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
    private val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private val GX = BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
    private val GY = BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)
    private val SEVEN = BigInteger.valueOf(7)
    private val TWO = BigInteger.valueOf(2)
    private val FOUR = BigInteger.valueOf(4)

    private class Point(val x: BigInteger, val y: BigInteger)

    fun verify(signature: ByteArray, message32: ByteArray, pubkeyHex: String): Boolean {
        if (signature.size != 64 || message32.size != 32) return false
        val pubkey = hexToBytes(pubkeyHex) ?: return false
        if (pubkey.size != 32) return false
        val r = BigInteger(1, signature.copyOfRange(0, 32))
        val s = BigInteger(1, signature.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val point = liftX(BigInteger(1, pubkey)) ?: return false
        val challenge = BigInteger(1, taggedHash("BIP0340/challenge",
            signature.copyOfRange(0, 32) + pubkey + message32)).mod(N)
        val negated = if (challenge.signum() == 0) BigInteger.ZERO else N.subtract(challenge)
        val result = add(multiply(s, Point(GX, GY)), multiply(negated, point)) ?: return false
        // R must have an even y (the lift in BIP-340 pinned the y parity of the key), and its x must be r.
        return !result.y.testBit(0) && result.x == r
    }

    private fun liftX(x: BigInteger): Point? {
        if (x >= P) return null
        val alpha = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        val beta = alpha.modPow(P.add(BigInteger.ONE).divide(FOUR), P)
        if (beta.modPow(TWO, P) != alpha) return null
        return Point(x, if (beta.testBit(0)) P.subtract(beta) else beta)
    }

    private fun multiply(scalar: BigInteger, point: Point): Point? {
        var k = scalar.mod(N)
        if (k.signum() == 0) return null
        var result: Point? = null
        var addend: Point? = point
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x) {
            if (a.y != b.y) return null
            if (a.y.signum() == 0) return null
            val slope = a.x.modPow(TWO, P).multiply(BigInteger.valueOf(3)).mod(P)
                .multiply(a.y.multiply(TWO).modInverse(P)).mod(P)
            val x = slope.modPow(TWO, P).subtract(a.x.multiply(TWO)).mod(P)
            val y = slope.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
            return Point(x, y)
        }
        val slope = b.y.subtract(a.y).mod(P).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        val x = slope.modPow(TWO, P).subtract(a.x).subtract(b.x).mod(P)
        val y = slope.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Point(x, y)
    }

    private fun taggedHash(tag: String, message: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val tagHash = digest.digest(tag.toByteArray(Charsets.UTF_8))
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(message)
        return digest.digest()
    }

    private fun hexToBytes(value: String): ByteArray? {
        if (value.length % 2 != 0 || !value.all { it in "0123456789abcdef" }) return null
        val out = ByteArray(value.length / 2)
        for (i in out.indices) out[i] = value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}
