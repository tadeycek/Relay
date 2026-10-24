package com.relay.app.linking

import com.relay.app.transport.nostr.InboxRelays
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Encode-side rejection of a bundle the caller built outside the schema. */
class LinkBundleException(message: String) : IllegalArgumentException(message)

enum class LinkError {
    TOO_LARGE,
    TRUNCATED,
    TRAILING_BYTES,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_SUITE,
    CRYPTO_FAILED,
    MALFORMED,
    FIELD_TOO_LARGE,

    /** The producer tried to smuggle message history/media into a bundle (LP-D06). */
    HISTORY_FORBIDDEN,
}

sealed interface LinkResult {
    data class Ok(val bundle: LinkBundle) : LinkResult
    data class Err(val error: LinkError, val detail: String? = null) : LinkResult
}

/**
 * Versioned, bounded, authenticated link bundle codec (05 §4).
 *
 * Wire format (all integers big-endian):
 *
 * ```
 * offset size field
 * 0      4    magic "RLYB"
 * 4      1    envelope version (1)
 * 5      1    HPKE suite (1 = X25519/HKDF-SHA256/AES-128-GCM)
 * 6      4    ciphertext length N (uint32)
 * 10     N    HPKE ciphertext (AEAD tag included)
 * ```
 *
 * Total ≤ [MAX_BUNDLE_BYTES] (the server's 128 KiB link-slot cap). The plaintext is canonical JSON
 * (see [encode]) with `{"v":1,...}` and a closed schema; every field has an explicit bound. The
 * decoder verifies the length before touching crypto, so truncation, trailing bytes and oversize
 * input are rejected without allocating or decrypting; version/suite/magic are checked before the
 * HPKE call. Authentication (wrong key, tampered ciphertext/tag, wrong suite) fails inside HPKE and
 * maps to [LinkError.CRYPTO_FAILED]. Nothing on the failure path throws.
 */
object LinkBundleCodec {

    const val MAGIC = "RLYB"
    const val VERSION = 1
    const val SUITE = 1
    const val HEADER_BYTES = 10

    /** Hard cap of the link slot (`PUT /v1/link/<slot>`), enforced locally too. */
    const val MAX_BUNDLE_BYTES = 128 * 1024

    /** Reserve for HPKE overhead (encapsulated key + AEAD tag) inside the cap. */
    private const val CRYPTO_OVERHEAD = 64
    const val MAX_PLAINTEXT_BYTES = MAX_BUNDLE_BYTES - HEADER_BYTES - CRYPTO_OVERHEAD

    // --- field bounds (encoder and decoder share these) -------------------------------------
    const val MAX_CONTACTS = 1000
    const val MAX_GROUPS = 200
    const val MAX_NAME = 100
    const val MAX_PHONE = 64
    const val MAX_DEVICE_ID = 64
    const val MAX_DEVICE_LABEL = 64
    const val MAX_KEYSET_BASE64 = 8192
    const val MAX_PUBLIC_KEY_BASE64 = 512
    const val MAX_SIGNING_KEY_BASE64 = 128
    const val MAX_RELAY_HINTS = 5
    const val MAX_RELAY_HINT = 300
    const val MAX_GROUP_NAME = 100
    const val MAX_GROUP_ID = 36
    const val MAX_GROUP_MEMBERS = 256
    const val MAX_ORIGINAL_NAME = 100
    const val MAX_THEME = 32
    const val MAX_LOCALE = 16
    const val MAX_RETENTION_DAYS = 365

    /** The bundle schema is root → identity/settings/contact → value (depth 3); 8 is generous. */
    const val MAX_JSON_DEPTH = 8

    private val HEX64 = Regex("[0-9a-f]{64}")
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,$MAX_DEVICE_ID}")
    private val BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")
    private val TRUST = setOf("trusted", "ask", "blocked")

    private val ROOT_KEYS = setOf("v", "createdAt", "identity", "contacts", "groups", "settings")
    private val IDENTITY_KEYS = setOf(
        "nostrSecretHex", "nostrPubkeyHex", "deviceId", "deviceLabel",
        "hpkeKeysetBase64", "hpkePreviousKeysetBase64", "hpkeKeysetExpiresAt",
    )
    private val CONTACT_KEYS = setOf(
        "name", "phone", "hasRelay", "trustLevel", "publicKey", "pendingPublicKey",
        "signingPublicKey", "nostrPubkey", "relayHints", "qrVerified", "deletedAt", "originalName",
    )
    private val GROUP_KEYS = setOf("id", "name", "sharedOwner", "members")
    private val SETTINGS_KEYS = setOf(
        "typingIndicators", "notificationPreview", "retentionDays",
        "readReceipts", "mediaAutoDownload", "theme", "locale",
    )
    private val HISTORY_KEYS = setOf("messages", "history", "chats", "media", "messageHistory")

    // --- encoding ---------------------------------------------------------------------------

    /** Canonical plaintext JSON. Throws [LinkBundleException] on any bound/schema violation. */
    fun encode(bundle: LinkBundle): String {
        validate(bundle)
        val json = buildString {
            append("{\"v\":").append(VERSION)
            append(",\"createdAt\":").append(bundle.createdAt)
            append(",\"identity\":")
            appendIdentity(bundle.identity)
            append(",\"contacts\":[")
            bundle.contacts.forEachIndexed { i, c -> if (i > 0) append(','); appendContact(c) }
            append("],\"groups\":[")
            bundle.groups.forEachIndexed { i, g -> if (i > 0) append(','); appendGroup(g) }
            append("],\"settings\":")
            appendSettings(bundle.settings)
            append('}')
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PLAINTEXT_BYTES) {
            throw LinkBundleException("link bundle is ${bytes.size} bytes, over the 128 KiB slot cap")
        }
        return json
    }

    /**
     * Seals [bundle] to the desktop's ephemeral link public key. Throws [LinkBundleException] when
     * the bundle cannot fit the slot or the platform HPKE refuses the key; the caller shows a local
     * error rather than uploading an unusable slot.
     */
    fun seal(hpke: LinkHpke, recipientPublicKeyBase64: String, bundle: LinkBundle): ByteArray {
        val plaintext = encode(bundle).toByteArray(Charsets.UTF_8)
        val ciphertext = hpke.encryptTo(recipientPublicKeyBase64, plaintext)
            ?: throw LinkBundleException("couldn't seal the link bundle to the desktop key")
        if (ciphertext.size > MAX_BUNDLE_BYTES - HEADER_BYTES) {
            throw LinkBundleException("sealed link bundle is over the 128 KiB slot cap")
        }
        val out = ByteArray(HEADER_BYTES + ciphertext.size)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[4] = VERSION.toByte()
        out[5] = SUITE.toByte()
        val n = ciphertext.size
        out[6] = (n ushr 24).toByte()
        out[7] = (n ushr 16).toByte()
        out[8] = (n ushr 8).toByte()
        out[9] = n.toByte()
        ciphertext.copyInto(out, HEADER_BYTES)
        return out
    }

    // --- decoding ---------------------------------------------------------------------------

    /** Opens a sealed bundle. Never throws for any byte array; every failure is a [LinkResult.Err]. */
    fun open(hpke: LinkHpke, recipientPrivateKeyBase64: String, sealed: ByteArray): LinkResult {
        if (sealed.isEmpty()) return LinkResult.Err(LinkError.TRUNCATED, "empty")
        if (sealed.size > MAX_BUNDLE_BYTES) return LinkResult.Err(LinkError.TOO_LARGE, "envelope ${sealed.size}")
        if (sealed.size < HEADER_BYTES) return LinkResult.Err(LinkError.TRUNCATED, "header")
        if (sealed[0] != 'R'.code.toByte() || sealed[1] != 'L'.code.toByte() ||
            sealed[2] != 'Y'.code.toByte() || sealed[3] != 'B'.code.toByte()
        ) return LinkResult.Err(LinkError.BAD_MAGIC)
        if (sealed[4].toInt() and 0xff != VERSION) return LinkResult.Err(LinkError.UNSUPPORTED_VERSION, "envelope v${sealed[4]}")
        if (sealed[5].toInt() and 0xff != SUITE) return LinkResult.Err(LinkError.UNSUPPORTED_SUITE, "suite ${sealed[5]}")

        val n = ((sealed[6].toInt() and 0xff) shl 24) or ((sealed[7].toInt() and 0xff) shl 16) or
            ((sealed[8].toInt() and 0xff) shl 8) or (sealed[9].toInt() and 0xff)
        if (n > MAX_BUNDLE_BYTES - HEADER_BYTES) return LinkResult.Err(LinkError.TOO_LARGE, "declared $n")
        if (n <= 0) return LinkResult.Err(LinkError.TRUNCATED, "declared $n")
        val expected = HEADER_BYTES + n
        if (sealed.size < expected) return LinkResult.Err(LinkError.TRUNCATED, "have ${sealed.size}, need $expected")
        if (sealed.size > expected) return LinkResult.Err(LinkError.TRAILING_BYTES, "${sealed.size - expected} extra")

        val ciphertext = sealed.copyOfRange(HEADER_BYTES, expected)
        val plaintext = hpke.decryptWith(recipientPrivateKeyBase64, ciphertext)
            ?: return LinkResult.Err(LinkError.CRYPTO_FAILED)
        if (plaintext.size > MAX_PLAINTEXT_BYTES) return LinkResult.Err(LinkError.TOO_LARGE, "plaintext ${plaintext.size}")
        return decode(String(plaintext, Charsets.UTF_8))
    }

    /** Decodes canonical JSON. Never throws; every failure is a [LinkResult.Err]. */
    fun decode(json: String): LinkResult {
        if (json.length > MAX_PLAINTEXT_BYTES) return LinkResult.Err(LinkError.TOO_LARGE, "chars ${json.length}")
        if (json.toByteArray(Charsets.UTF_8).size > MAX_PLAINTEXT_BYTES) {
            return LinkResult.Err(LinkError.TOO_LARGE, "bytes")
        }
        // The schema is three levels deep. Rejecting deeper input before org.json's recursive
        // parser turns a hostile 128 KiB `[[[[…` into MALFORMED instead of a StackOverflowError.
        if (!depthWithin(json, MAX_JSON_DEPTH)) return LinkResult.Err(LinkError.MALFORMED, "nesting")
        return try {
            val root = JSONObject(json)
            rejectUnknown(root, ROOT_KEYS, "root")
            if (root.optInt("v", -1) != VERSION) {
                val v = root.opt("v")
                return if (v == null) LinkResult.Err(LinkError.MALFORMED, "missing v")
                else LinkResult.Err(LinkError.UNSUPPORTED_VERSION, "bundle v$v")
            }
            val createdAt = number(root, "createdAt", 0, Long.MAX_VALUE)
            val identity = identity(root.obj("identity"))
            val contacts = array(root, "contacts", MAX_CONTACTS).map { contact(it) }
            val groups = array(root, "groups", MAX_GROUPS).map { group(it) }
            val settings = settings(root.obj("settings"))
            LinkResult.Ok(LinkBundle(createdAt, identity, contacts, groups, settings))
        } catch (f: DecodeFailure) {
            LinkResult.Err(f.error, f.detail)
        } catch (e: JSONException) {
            LinkResult.Err(LinkError.MALFORMED, "json")
        } catch (e: IllegalArgumentException) {
            LinkResult.Err(LinkError.MALFORMED, "invalid")
        }
    }

    // --- validation (encoder side) ----------------------------------------------------------

    private fun validate(b: LinkBundle) {
        req(b.createdAt >= 0, "createdAt")
        val id = b.identity
        req(HEX64.matches(id.nostrSecretHex), "identity.nostrSecretHex")
        req(HEX64.matches(id.nostrPubkeyHex), "identity.nostrPubkeyHex")
        req(DEVICE_ID.matches(id.deviceId), "identity.deviceId")
        bound(id.deviceLabel.length.toLong(), MAX_DEVICE_LABEL.toLong(), "identity.deviceLabel")
        req(id.deviceLabel.none(::isUnsafeText), "identity.deviceLabel")
        req(id.hpkeKeysetBase64.isNotEmpty() && id.hpkeKeysetBase64.length <= MAX_KEYSET_BASE64 &&
            isBase64(id.hpkeKeysetBase64), "identity.hpkeKeysetBase64")
        id.hpkePreviousKeysetBase64?.let {
            req(it.isNotEmpty() && it.length <= MAX_KEYSET_BASE64 && isBase64(it), "identity.hpkePreviousKeysetBase64")
        }
        req(id.hpkeKeysetExpiresAt >= 0, "identity.hpkeKeysetExpiresAt")

        bound(b.contacts.size.toLong(), MAX_CONTACTS.toLong(), "contacts")
        b.contacts.forEachIndexed { i, c -> validateContact(c, "contacts[$i]") }
        bound(b.groups.size.toLong(), MAX_GROUPS.toLong(), "groups")
        b.groups.forEachIndexed { i, g -> validateGroup(g, "groups[$i]") }
        validateSettings(b.settings)
    }

    private fun validateContact(c: LinkContact, path: String) {
        req(c.name.isNotEmpty() && c.name.length <= MAX_NAME && c.name.none(::isUnsafeText), "$path.name")
        req(c.phone.length <= MAX_PHONE && c.phone.none(::isUnsafeText), "$path.phone")
        req(c.trustLevel in TRUST, "$path.trustLevel")
        c.publicKey?.let { req(it.isNotEmpty() && it.length <= MAX_PUBLIC_KEY_BASE64 && isBase64(it), "$path.publicKey") }
        c.pendingPublicKey?.let { req(it.isNotEmpty() && it.length <= MAX_PUBLIC_KEY_BASE64 && isBase64(it), "$path.pendingPublicKey") }
        c.signingPublicKey?.let { req(it.isNotEmpty() && it.length <= MAX_SIGNING_KEY_BASE64 && isBase64(it), "$path.signingPublicKey") }
        c.nostrPubkey?.let { req(HEX64.matches(it), "$path.nostrPubkey") }
        bound(c.relayHints.size.toLong(), MAX_RELAY_HINTS.toLong(), "$path.relayHints")
        c.relayHints.forEach {
            req(it.length <= MAX_RELAY_HINT && InboxRelays.isAcceptableRelay(it), "$path.relayHints")
        }
        c.deletedAt?.let { req(it >= 0, "$path.deletedAt") }
        c.originalName?.let {
            req(it.length <= MAX_ORIGINAL_NAME && it.none(::isUnsafeText), "$path.originalName")
        }
    }

    private fun validateGroup(g: LinkGroup, path: String) {
        req(uuidOrNull(g.id) != null, "$path.id")
        req(g.name.isNotEmpty() && g.name.length <= MAX_GROUP_NAME && g.name.none(::isUnsafeText), "$path.name")
        g.sharedOwner?.let { req(HEX64.matches(it), "$path.sharedOwner") }
        bound(g.members.size.toLong(), MAX_GROUP_MEMBERS.toLong(), "$path.members")
        g.members.forEach { req(HEX64.matches(it), "$path.members") }
        req(g.members.distinct().size == g.members.size, "$path.members duplicate")
    }

    private fun validateSettings(s: LinkSettings) {
        req(s.retentionDays in 0..MAX_RETENTION_DAYS, "settings.retentionDays")
        req(s.theme.length <= MAX_THEME && s.theme.none(::isUnsafeText), "settings.theme")
        req(s.locale.length <= MAX_LOCALE && s.locale.none(::isUnsafeText), "settings.locale")
    }

    private fun req(condition: Boolean, field: String) {
        if (!condition) throw LinkBundleException("link bundle field out of bounds: $field")
    }

    private fun bound(value: Long, max: Long, field: String) {
        if (value > max) throw LinkBundleException("link bundle field over bound ($max): $field")
    }

    // --- JSON writer (canonical, independent of org.json's map order) ----------------------

    private fun StringBuilder.appendIdentity(id: LinkIdentity) {
        append("{\"nostrSecretHex\":").append(quote(id.nostrSecretHex))
        append(",\"nostrPubkeyHex\":").append(quote(id.nostrPubkeyHex))
        append(",\"deviceId\":").append(quote(id.deviceId))
        append(",\"deviceLabel\":").append(quote(id.deviceLabel))
        append(",\"hpkeKeysetBase64\":").append(quote(id.hpkeKeysetBase64))
        id.hpkePreviousKeysetBase64?.let { append(",\"hpkePreviousKeysetBase64\":").append(quote(it)) }
        append(",\"hpkeKeysetExpiresAt\":").append(id.hpkeKeysetExpiresAt)
        append('}')
    }

    private fun StringBuilder.appendContact(c: LinkContact) {
        append("{\"name\":").append(quote(c.name))
        append(",\"phone\":").append(quote(c.phone))
        append(",\"hasRelay\":").append(c.hasRelay)
        append(",\"trustLevel\":").append(quote(c.trustLevel))
        c.publicKey?.let { append(",\"publicKey\":").append(quote(it)) }
        c.pendingPublicKey?.let { append(",\"pendingPublicKey\":").append(quote(it)) }
        c.signingPublicKey?.let { append(",\"signingPublicKey\":").append(quote(it)) }
        c.nostrPubkey?.let { append(",\"nostrPubkey\":").append(quote(it)) }
        append(",\"relayHints\":[")
        c.relayHints.forEachIndexed { i, h -> if (i > 0) append(','); append(quote(h)) }
        append("],\"qrVerified\":").append(c.qrVerified)
        c.deletedAt?.let { append(",\"deletedAt\":").append(it) }
        c.originalName?.let { append(",\"originalName\":").append(quote(it)) }
        append('}')
    }

    private fun StringBuilder.appendGroup(g: LinkGroup) {
        append("{\"id\":").append(quote(g.id))
        append(",\"name\":").append(quote(g.name))
        g.sharedOwner?.let { append(",\"sharedOwner\":").append(quote(it)) }
        append(",\"members\":[")
        g.members.forEachIndexed { i, m -> if (i > 0) append(','); append(quote(m)) }
        append("]}")
    }

    private fun StringBuilder.appendSettings(s: LinkSettings) {
        append("{\"typingIndicators\":").append(s.typingIndicators)
        append(",\"notificationPreview\":").append(s.notificationPreview)
        append(",\"retentionDays\":").append(s.retentionDays)
        append(",\"readReceipts\":").append(s.readReceipts)
        append(",\"mediaAutoDownload\":").append(s.mediaAutoDownload)
        append(",\"theme\":").append(quote(s.theme))
        append(",\"locale\":").append(quote(s.locale))
        append('}')
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20 || c.code == 0x7f) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    // --- JSON reader ------------------------------------------------------------------------

    private class DecodeFailure(val error: LinkError, val detail: String? = null) : Exception(detail)

    private fun JSONObject.obj(key: String): JSONObject {
        val v = opt(key) ?: throw DecodeFailure(LinkError.MALFORMED, "missing $key")
        return v as? JSONObject ?: throw DecodeFailure(LinkError.MALFORMED, "$key not object")
    }

    private fun rejectUnknown(o: JSONObject, allowed: Set<String>, where: String) {
        val keys = o.keys().asSequence().toSet()
        val history = keys.firstOrNull { it in HISTORY_KEYS }
        if (history != null) throw DecodeFailure(LinkError.HISTORY_FORBIDDEN, "$where.$history")
        val unknown = keys.firstOrNull { it !in allowed }
        if (unknown != null) throw DecodeFailure(LinkError.MALFORMED, "$where.$unknown")
    }

    private fun text(o: JSONObject, key: String, min: Int, max: Int, path: String, required: Boolean = true): String? {
        val v = o.opt(key)
        if (v == null || v == JSONObject.NULL) {
            if (required) throw DecodeFailure(LinkError.MALFORMED, "missing $path")
            return null
        }
        if (v !is String) throw DecodeFailure(LinkError.MALFORMED, "$path not string")
        if (v.length > max) throw DecodeFailure(LinkError.FIELD_TOO_LARGE, "$path ${v.length} > $max")
        if (v.length < min) throw DecodeFailure(LinkError.MALFORMED, "$path too short")
        return v
    }

    private fun bool(o: JSONObject, key: String, path: String): Boolean {
        val v = o.opt(key) ?: throw DecodeFailure(LinkError.MALFORMED, "missing $path")
        return v as? Boolean ?: throw DecodeFailure(LinkError.MALFORMED, "$path not bool")
    }

    private fun number(o: JSONObject, key: String, min: Long, max: Long): Long {
        val v = o.opt(key) ?: throw DecodeFailure(LinkError.MALFORMED, "missing $key")
        val n = when (v) {
            is Int -> v.toLong()
            is Long -> v
            else -> throw DecodeFailure(LinkError.MALFORMED, "$key not integer")
        }
        if (n < min || n > max) throw DecodeFailure(LinkError.MALFORMED, "$key out of range")
        return n
    }

    private fun array(o: JSONObject, key: String, max: Int): List<JSONObject> {
        val v = o.opt(key) ?: throw DecodeFailure(LinkError.MALFORMED, "missing $key")
        val a = v as? JSONArray ?: throw DecodeFailure(LinkError.MALFORMED, "$key not array")
        if (a.length() > max) throw DecodeFailure(LinkError.FIELD_TOO_LARGE, "$key ${a.length()} > $max")
        return (0 until a.length()).map {
            a.opt(it) as? JSONObject ?: throw DecodeFailure(LinkError.MALFORMED, "$key[$it] not object")
        }
    }

    private fun identity(o: JSONObject): LinkIdentity {
        rejectUnknown(o, IDENTITY_KEYS, "identity")
        val secret = text(o, "nostrSecretHex", 64, 64, "identity.nostrSecretHex")!!
        val pub = text(o, "nostrPubkeyHex", 64, 64, "identity.nostrPubkeyHex")!!
        if (!HEX64.matches(secret) || !HEX64.matches(pub)) throw DecodeFailure(LinkError.MALFORMED, "identity.hex")
        val deviceId = text(o, "deviceId", 1, MAX_DEVICE_ID, "identity.deviceId")!!
        if (!DEVICE_ID.matches(deviceId)) throw DecodeFailure(LinkError.MALFORMED, "identity.deviceId")
        val label = text(o, "deviceLabel", 0, MAX_DEVICE_LABEL, "identity.deviceLabel")!!
        if (label.any(::isUnsafeText)) throw DecodeFailure(LinkError.MALFORMED, "identity.deviceLabel")
        val keyset = text(o, "hpkeKeysetBase64", 1, MAX_KEYSET_BASE64, "identity.hpkeKeysetBase64")!!
        if (!isBase64(keyset)) throw DecodeFailure(LinkError.MALFORMED, "identity.hpkeKeysetBase64")
        val previous = text(o, "hpkePreviousKeysetBase64", 1, MAX_KEYSET_BASE64,
            "identity.hpkePreviousKeysetBase64", required = false)
        if (previous != null && !isBase64(previous)) throw DecodeFailure(LinkError.MALFORMED, "identity.hpkePreviousKeysetBase64")
        val expires = number(o, "hpkeKeysetExpiresAt", 0, Long.MAX_VALUE)
        return LinkIdentity(secret, pub, deviceId, label, keyset, previous, expires)
    }

    private fun contact(o: JSONObject): LinkContact {
        rejectUnknown(o, CONTACT_KEYS, "contact")
        val name = text(o, "name", 1, MAX_NAME, "contact.name")!!
        if (name.any(::isUnsafeText)) throw DecodeFailure(LinkError.MALFORMED, "contact.name")
        val phone = text(o, "phone", 0, MAX_PHONE, "contact.phone")!!
        if (phone.any(::isUnsafeText)) throw DecodeFailure(LinkError.MALFORMED, "contact.phone")
        val trust = text(o, "trustLevel", 0, 16, "contact.trustLevel")!!
        if (trust !in TRUST) throw DecodeFailure(LinkError.MALFORMED, "contact.trustLevel")
        fun optKey(key: String) = text(o, key, 1, MAX_PUBLIC_KEY_BASE64, "contact.$key", required = false)?.also {
            if (!isBase64(it)) throw DecodeFailure(LinkError.MALFORMED, "contact.$key")
        }
        val signing = text(o, "signingPublicKey", 1, MAX_SIGNING_KEY_BASE64,
            "contact.signingPublicKey", required = false)?.also {
            if (!isBase64(it)) throw DecodeFailure(LinkError.MALFORMED, "contact.signingPublicKey")
        }
        val nostr = text(o, "nostrPubkey", 64, 64, "contact.nostrPubkey", required = false)?.also {
            if (!HEX64.matches(it)) throw DecodeFailure(LinkError.MALFORMED, "contact.nostrPubkey")
        }
        val hints = o.opt("relayHints") ?: throw DecodeFailure(LinkError.MALFORMED, "missing relayHints")
        val hintArray = hints as? JSONArray ?: throw DecodeFailure(LinkError.MALFORMED, "relayHints not array")
        if (hintArray.length() > MAX_RELAY_HINTS) throw DecodeFailure(LinkError.FIELD_TOO_LARGE, "relayHints")
        val relayHints = (0 until hintArray.length()).map { i ->
            val h = hintArray.opt(i) as? String ?: throw DecodeFailure(LinkError.MALFORMED, "relayHints[$i]")
            if (h.length > MAX_RELAY_HINT) throw DecodeFailure(LinkError.FIELD_TOO_LARGE, "relayHints[$i]")
            if (!InboxRelays.isAcceptableRelay(h)) throw DecodeFailure(LinkError.MALFORMED, "relayHints[$i]")
            h
        }
        val deleted = o.opt("deletedAt")?.let { number(o, "deletedAt", 0, Long.MAX_VALUE) }
        val original = text(o, "originalName", 0, MAX_ORIGINAL_NAME, "contact.originalName", required = false)?.also {
            if (it.any(::isUnsafeText)) throw DecodeFailure(LinkError.MALFORMED, "contact.originalName")
        }
        return LinkContact(
            name = name,
            phone = phone,
            hasRelay = bool(o, "hasRelay", "contact.hasRelay"),
            trustLevel = trust,
            publicKey = optKey("publicKey"),
            pendingPublicKey = optKey("pendingPublicKey"),
            signingPublicKey = signing,
            nostrPubkey = nostr,
            relayHints = relayHints,
            qrVerified = bool(o, "qrVerified", "contact.qrVerified"),
            deletedAt = deleted,
            originalName = original,
        )
    }

    private fun group(o: JSONObject): LinkGroup {
        rejectUnknown(o, GROUP_KEYS, "group")
        val id = text(o, "id", 1, MAX_GROUP_ID, "group.id")!!
        if (uuidOrNull(id) == null) throw DecodeFailure(LinkError.MALFORMED, "group.id")
        val name = text(o, "name", 1, MAX_GROUP_NAME, "group.name")!!
        if (name.any(::isUnsafeText)) throw DecodeFailure(LinkError.MALFORMED, "group.name")
        val owner = text(o, "sharedOwner", 64, 64, "group.sharedOwner", required = false)?.also {
            if (!HEX64.matches(it)) throw DecodeFailure(LinkError.MALFORMED, "group.sharedOwner")
        }
        val members = o.opt("members") ?: throw DecodeFailure(LinkError.MALFORMED, "missing members")
        val a = members as? JSONArray ?: throw DecodeFailure(LinkError.MALFORMED, "members not array")
        if (a.length() > MAX_GROUP_MEMBERS) throw DecodeFailure(LinkError.FIELD_TOO_LARGE, "members")
        val list = (0 until a.length()).map { i ->
            val m = a.opt(i) as? String ?: throw DecodeFailure(LinkError.MALFORMED, "members[$i]")
            if (!HEX64.matches(m)) throw DecodeFailure(LinkError.MALFORMED, "members[$i]")
            m
        }
        if (list.distinct().size != list.size) throw DecodeFailure(LinkError.MALFORMED, "members duplicate")
        return LinkGroup(id, name, owner, list)
    }

    private fun settings(o: JSONObject): LinkSettings {
        rejectUnknown(o, SETTINGS_KEYS, "settings")
        val retention = number(o, "retentionDays", 0, MAX_RETENTION_DAYS.toLong()).toInt()
        val theme = text(o, "theme", 0, MAX_THEME, "settings.theme")!!
        val locale = text(o, "locale", 0, MAX_LOCALE, "settings.locale")!!
        if (theme.any(::isUnsafeText) || locale.any(::isUnsafeText)) {
            throw DecodeFailure(LinkError.MALFORMED, "settings text")
        }
        return LinkSettings(
            typingIndicators = bool(o, "typingIndicators", "settings.typingIndicators"),
            notificationPreview = bool(o, "notificationPreview", "settings.notificationPreview"),
            retentionDays = retention,
            readReceipts = bool(o, "readReceipts", "settings.readReceipts"),
            mediaAutoDownload = bool(o, "mediaAutoDownload", "settings.mediaAutoDownload"),
            theme = theme,
            locale = locale,
        )
    }

    private fun isBase64(value: String): Boolean =
        value.length % 4 == 0 && BASE64.matches(value) && runCatching { java.util.Base64.getDecoder().decode(value) }.isSuccess

    /** Bracket depth outside JSON strings, without allocating or recursing. */
    private fun depthWithin(json: String, max: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> if (++depth > max) return false
                    '}', ']' -> depth--
                }
            }
            if (depth < 0) return false
        }
        return depth == 0
    }

    private fun isUnsafeText(c: Char): Boolean =
        Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt()

    private fun uuidOrNull(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value }
}
