package com.relay.app.linking

import com.relay.app.transport.PayloadCodec
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Random
import java.util.UUID

/** Encode-side rejection of a sync body that is outside the schema or the envelope bound. */
class SyncFormatException(message: String) : IllegalArgumentException(message)

enum class SyncError {
    BAD_PREFIX,
    TOO_LARGE,
    UNSUPPORTED_VERSION,
    UNKNOWN_KIND,
    MALFORMED,
    FIELD_TOO_LARGE,
}

sealed interface SyncResult {
    data class Ok(val body: SyncBody) : SyncResult
    data class Err(val error: SyncError, val detail: String? = null) : SyncResult
}

/**
 * Bounded `TYPE:SYNC` codec (05 §3, G3).
 *
 * ```
 * TYPE:SYNC|<KIND>|V:1|SID:<sync id>|R:<revision>|O:<origin device>|<kind-specific fields>
 * ```
 *
 * Kind fields (fixed order on encode, any order accepted on decode, duplicates/unknowns rejected):
 *
 * | kind | fields |
 * |---|---|
 * | `SENT` | `TO:<64hex or group:<64hex>:<uuid>>` `P:<base64 RelayPayload JSON>` |
 * | `READ` | `C:<conversation>` `T:<ts>` |
 * | `KEYS` | `K:<base64 sealed keysets>` |
 * | `CONTACT`, `CONTACT_DEL`, `GROUP`, `SETTINGS` | `J:<base64 JSON>` |
 * | `DELETE` | `C:<conversation>` `ID:<payload uuid>` |
 * | any kind, oversized | `PART:1` `P:<i>/<n>` `T:<total bytes>` `H:<sha256 hex>` `D:<base64url chunk>` |
 *
 * Every single-part body must be ≤ [MAX_RAW_BYTES] (12 288) so that **base64 of the body is ≤ 16 KiB**,
 * the existing mailbox envelope bound. Larger logical bodies are split with [chunk] and reassembled
 * by [SyncReassembler]. Decoding never throws for hostile input.
 */
object SyncCodec {

    const val PREFIX = "TYPE:SYNC|"
    const val VERSION = 1

    /** 12 288 bytes → exactly 16 384 bytes of standard base64 (the mailbox envelope limit). */
    const val MAX_RAW_BYTES = 12 * 1024
    const val MAX_ENCODED_BYTES = 16 * 1024

    /** Bound on a reassembled logical body, checked before parsing or allocating. */
    const val MAX_LOGICAL_BYTES = 256 * 1024

    const val MAX_SYNC_ID = 64
    const val MAX_ORIGIN = 64
    const val MAX_CONVERSATION = 150
    const val MAX_TARGET = 160
    const val MAX_J_BYTES = 64 * 1024
    const val MAX_PAYLOAD_BYTES = 32 * 1024
    const val MAX_PARTS = 64
    const val MAX_PART_DATA = 8 * 1024

    const val KIND_SENT = "SENT"
    const val KIND_READ = "READ"
    const val KIND_KEYS = "KEYS"
    const val KIND_CONTACT = "CONTACT"
    const val KIND_CONTACT_DEL = "CONTACT_DEL"
    const val KIND_GROUP = "GROUP"
    const val KIND_SETTINGS = "SETTINGS"
    const val KIND_DELETE = "DELETE"

    val KINDS = listOf(KIND_SENT, KIND_READ, KIND_KEYS, KIND_CONTACT, KIND_CONTACT_DEL, KIND_GROUP, KIND_SETTINGS, KIND_DELETE)

    private val ID = Regex("[A-Za-z0-9_-]{1,$MAX_SYNC_ID}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val CONVERSATION = Regex("[A-Za-z0-9:_.-]{1,$MAX_CONVERSATION}")
    private val BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")
    private val BASE64URL = Regex("[A-Za-z0-9_-]*")
    private val HEX = Regex("[0-9a-f]{64}")

    fun newSyncId(random: Random = SecureRandom()): String {
        // Lazy so the object stays a pure codec; 128-bit ids make collisions negligible.
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // --- encode -----------------------------------------------------------------------------

    /** Encodes a single-part body, refusing anything that would not fit 16 KiB after base64. */
    fun encode(body: SyncBody): String {
        val wire = encodeLogical(body)
        val raw = wire.toByteArray(Charsets.UTF_8).size
        if (raw > MAX_RAW_BYTES) {
            throw SyncFormatException("sync body is $raw bytes; must be <= $MAX_RAW_BYTES so its base64 stays within 16 KiB")
        }
        val encoded = Base64.getEncoder().encodeToString(wire.toByteArray(Charsets.UTF_8)).length
        if (encoded > MAX_ENCODED_BYTES) {
            throw SyncFormatException("sync body base64 is $encoded bytes; over the 16 KiB envelope")
        }
        return wire
    }

    /**
     * Encodes without the 16 KiB single-body check (still bounded by [MAX_LOGICAL_BYTES]). Used by
     * [chunk] and by reassembly tests; a body produced this way must be chunked before sending.
     */
    fun encodeLogical(body: SyncBody): String {
        validate(body)
        val wire = buildString {
            append(PREFIX)
            when (body) {
                is SyncBody.Sent -> {
                    append(KIND_SENT).append(header(body))
                    append("|TO:").append(body.target)
                    append("|P:").append(body.payloadBase64)
                }
                is SyncBody.Read -> {
                    append(KIND_READ).append(header(body))
                    append("|C:").append(body.conversation)
                    append("|T:").append(body.ts)
                }
                is SyncBody.Keys -> {
                    append(KIND_KEYS).append(header(body))
                    append("|K:").append(body.keysetsBase64)
                }
                is SyncBody.Contact -> {
                    append(KIND_CONTACT).append(header(body))
                    append("|J:").append(body.contactJsonBase64)
                }
                is SyncBody.ContactDelete -> {
                    append(KIND_CONTACT_DEL).append(header(body))
                    append("|J:").append(body.contactJsonBase64)
                }
                is SyncBody.Group -> {
                    append(KIND_GROUP).append(header(body))
                    append("|J:").append(body.rosterJsonBase64)
                }
                is SyncBody.Settings -> {
                    append(KIND_SETTINGS).append(header(body))
                    append("|J:").append(body.settingsJsonBase64)
                }
                is SyncBody.Delete -> {
                    append(KIND_DELETE).append(header(body))
                    append("|C:").append(body.conversation)
                    append("|ID:").append(body.payloadId)
                }
                is SyncBody.Part -> {
                    append(body.kind).append(header(body))
                    append("|PART:1")
                    append("|P:").append(body.index).append('/').append(body.count)
                    append("|T:").append(body.totalBytes)
                    append("|H:").append(body.sha256Hex)
                    append("|D:").append(body.dataBase64Url)
                }
            }
        }
        val size = wire.toByteArray(Charsets.UTF_8).size
        if (size > MAX_LOGICAL_BYTES) throw SyncFormatException("sync body is $size bytes; over the logical bound")
        return wire
    }

    private fun header(body: SyncBody): String = buildString {
        append("|V:").append(VERSION)
        append("|SID:").append(body.syncId)
        append("|R:").append(body.revision)
        append("|O:").append(body.origin)
    }

    private fun validate(body: SyncBody) {
        requireId(body.syncId, "syncId")
        requireId(body.origin, "origin")
        if (body.revision < 0) throw SyncFormatException("sync revision must be >= 0")
        when (body) {
            is SyncBody.Sent -> {
                if (!validTarget(body.target)) throw SyncFormatException("sync SENT target is not a contact pubkey or group ref")
                if (body.payloadBase64.length > MAX_PAYLOAD_BYTES || !isBase64(body.payloadBase64)) {
                    throw SyncFormatException("sync SENT payload is not bounded base64")
                }
                if (!isRelayPayload(body.payloadBase64)) throw SyncFormatException("sync SENT payload is not a valid RelayPayload")
            }
            is SyncBody.Read -> {
                if (!CONVERSATION.matches(body.conversation)) throw SyncFormatException("sync READ conversation out of bounds")
            }
            is SyncBody.Keys -> requireJ(body.keysetsBase64, "KEYS")
            is SyncBody.Contact -> requireJ(body.contactJsonBase64, "CONTACT")
            is SyncBody.ContactDelete -> requireJ(body.contactJsonBase64, "CONTACT_DEL")
            is SyncBody.Group -> requireJ(body.rosterJsonBase64, "GROUP")
            is SyncBody.Settings -> requireJ(body.settingsJsonBase64, "SETTINGS")
            is SyncBody.Delete -> {
                if (!CONVERSATION.matches(body.conversation)) throw SyncFormatException("sync DELETE conversation out of bounds")
                if (uuidOrNull(body.payloadId) == null) throw SyncFormatException("sync DELETE id is not a uuid")
            }
            is SyncBody.Part -> validatePart(body)
        }
    }

    private fun validatePart(p: SyncBody.Part) {
        if (p.kind !in KINDS) throw SyncFormatException("sync part kind is unknown")
        requireId(p.syncId, "syncId")
        requireId(p.origin, "origin")
        if (p.revision < 0) throw SyncFormatException("sync revision must be >= 0")
        if (p.count !in 1..MAX_PARTS) throw SyncFormatException("sync part count out of bounds")
        if (p.index !in 1..p.count) throw SyncFormatException("sync part index out of bounds")
        if (p.totalBytes !in 1..MAX_LOGICAL_BYTES.toLong()) throw SyncFormatException("sync part total out of bounds")
        if (!HEX.matches(p.sha256Hex)) throw SyncFormatException("sync part hash is not sha256 hex")
        if (!BASE64URL.matches(p.dataBase64Url)) throw SyncFormatException("sync part data is not base64url")
        val bytes = runCatching { Base64.getUrlDecoder().decode(p.dataBase64Url) }.getOrNull()
            ?: throw SyncFormatException("sync part data is not decodable")
        if (bytes.size > MAX_PART_DATA) throw SyncFormatException("sync part data is over the part bound")
    }

    private fun requireId(value: String, field: String) {
        if (!ID.matches(value)) throw SyncFormatException("sync $field out of bounds")
    }

    private fun requireJ(value: String, kind: String) {
        if (value.isEmpty() || value.length > MAX_J_BYTES || !isBase64(value)) {
            throw SyncFormatException("sync $kind JSON is not bounded base64")
        }
    }

    private fun validTarget(target: String): Boolean {
        if (target.length > MAX_TARGET) return false
        if (HEX64.matches(target)) return true
        if (!target.startsWith("group:")) return false
        val parts = target.removePrefix("group:").split(':')
        if (parts.size != 2) return false
        return HEX64.matches(parts[0]) && uuidOrNull(parts[1]) != null
    }

    private fun isBase64(value: String): Boolean =
        value.isNotEmpty() && value.length % 4 == 0 && BASE64.matches(value) &&
            runCatching { Base64.getDecoder().decode(value) }.isSuccess

    private fun isRelayPayload(value: String): Boolean {
        val bytes = runCatching { Base64.getDecoder().decode(value) }.getOrNull() ?: return false
        return PayloadCodec.decode(String(bytes, Charsets.UTF_8)) != null
    }

    // --- chunking ---------------------------------------------------------------------------

    /**
     * Returns one wire body if it fits, otherwise `PART` bodies for [body]. Each part's `D` holds a
     * slice of the *logical* UTF-8 body (the whole `TYPE:SYNC|KIND|…` string), and every part carries
     * the hash and total length of that whole body.
     */
    fun chunk(body: SyncBody): List<String> {
        val logical = encodeLogical(body)
        val bytes = logical.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_RAW_BYTES) return listOf(logical)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val count = (bytes.size + MAX_PART_DATA - 1) / MAX_PART_DATA
        if (count > MAX_PARTS) throw SyncFormatException("sync body needs $count parts; over $MAX_PARTS")
        val kind = when (body) {
            is SyncBody.Sent -> KIND_SENT
            is SyncBody.Read -> KIND_READ
            is SyncBody.Keys -> KIND_KEYS
            is SyncBody.Contact -> KIND_CONTACT
            is SyncBody.ContactDelete -> KIND_CONTACT_DEL
            is SyncBody.Group -> KIND_GROUP
            is SyncBody.Settings -> KIND_SETTINGS
            is SyncBody.Delete -> KIND_DELETE
            is SyncBody.Part -> throw SyncFormatException("cannot chunk a part")
        }
        return (0 until count).map { i ->
            val slice = bytes.copyOfRange(i * MAX_PART_DATA, minOf(bytes.size, (i + 1) * MAX_PART_DATA))
            encodeLogical(
                SyncBody.Part(
                    origin = body.origin,
                    syncId = body.syncId,
                    revision = body.revision,
                    kind = kind,
                    index = i + 1,
                    count = count,
                    totalBytes = bytes.size.toLong(),
                    sha256Hex = hash,
                    dataBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(slice),
                ),
            )
        }
    }

    // --- decode -----------------------------------------------------------------------------

    /** Parses a wire body. Never throws; every failure is [SyncResult.Err]. */
    fun decode(wire: String): SyncResult {
        if (wire.length > MAX_LOGICAL_BYTES) return SyncResult.Err(SyncError.TOO_LARGE, "chars ${wire.length}")
        if (wire.toByteArray(Charsets.UTF_8).size > MAX_LOGICAL_BYTES) return SyncResult.Err(SyncError.TOO_LARGE, "bytes")
        if (!wire.startsWith(PREFIX)) return SyncResult.Err(SyncError.BAD_PREFIX)
        val tokens = wire.substring(PREFIX.length).split('|')
        if (tokens.isEmpty() || tokens[0].isEmpty()) return SyncResult.Err(SyncError.BAD_PREFIX)
        val kind = tokens[0]
        if (kind !in KINDS) return SyncResult.Err(SyncError.UNKNOWN_KIND, kind)

        val fields = LinkedHashMap<String, String>()
        for (token in tokens.drop(1)) {
            val i = token.indexOf(':')
            if (i <= 0) return SyncResult.Err(SyncError.MALFORMED, "field $token")
            val key = token.substring(0, i)
            if (key !in FIELD_KEYS) return SyncResult.Err(SyncError.MALFORMED, "unknown $key")
            if (fields.put(key, token.substring(i + 1)) != null) return SyncResult.Err(SyncError.MALFORMED, "duplicate $key")
        }

        val version = fields["V"] ?: return SyncResult.Err(SyncError.MALFORMED, "missing V")
        if (version.toIntOrNull() != VERSION) return SyncResult.Err(SyncError.UNSUPPORTED_VERSION, version)
        val syncId = fields["SID"] ?: return SyncResult.Err(SyncError.MALFORMED, "missing SID")
        if (!ID.matches(syncId)) return SyncResult.Err(SyncError.MALFORMED, "SID")
        val origin = fields["O"] ?: return SyncResult.Err(SyncError.MALFORMED, "missing O")
        if (!ID.matches(origin)) return SyncResult.Err(SyncError.MALFORMED, "O")
        val revisionText = fields["R"] ?: return SyncResult.Err(SyncError.MALFORMED, "missing R")
        val revision = revisionText.toLongOrNull() ?: return SyncResult.Err(SyncError.MALFORMED, "R")
        if (revision < 0) return SyncResult.Err(SyncError.FIELD_TOO_LARGE, "R")

        if (fields.containsKey("PART")) {
            if (fields["PART"] != "1") return SyncResult.Err(SyncError.MALFORMED, "PART")
            return decodePart(kind, syncId, origin, revision, fields)
        }
        return try {
            when (kind) {
                KIND_SENT -> {
                    exact(fields, setOf("V", "SID", "R", "O", "TO", "P"))
                    val target = fields["TO"]!!
                    if (!validTarget(target)) return SyncResult.Err(SyncError.MALFORMED, "TO")
                    val payload = fields["P"]!!
                    if (payload.length > MAX_PAYLOAD_BYTES) return SyncResult.Err(SyncError.FIELD_TOO_LARGE, "P")
                    if (!isRelayPayload(payload)) return SyncResult.Err(SyncError.MALFORMED, "P")
                    SyncResult.Ok(SyncBody.Sent(origin, syncId, revision, target, payload))
                }
                KIND_READ -> {
                    exact(fields, setOf("V", "SID", "R", "O", "C", "T"))
                    val conversation = conversation(fields["C"])
                        ?: return SyncResult.Err(SyncError.MALFORMED, "C")
                    val ts = fields["T"]!!.toLongOrNull() ?: return SyncResult.Err(SyncError.MALFORMED, "T")
                    SyncResult.Ok(SyncBody.Read(origin, syncId, revision, conversation, ts))
                }
                KIND_KEYS -> {
                    exact(fields, setOf("V", "SID", "R", "O", "K"))
                    jsonField(fields["K"])?.let { return it }
                    SyncResult.Ok(SyncBody.Keys(origin, syncId, revision, fields["K"]!!))
                }
                KIND_CONTACT -> {
                    exact(fields, setOf("V", "SID", "R", "O", "J"))
                    jsonField(fields["J"])?.let { return it }
                    SyncResult.Ok(SyncBody.Contact(origin, syncId, revision, fields["J"]!!))
                }
                KIND_CONTACT_DEL -> {
                    exact(fields, setOf("V", "SID", "R", "O", "J"))
                    jsonField(fields["J"])?.let { return it }
                    SyncResult.Ok(SyncBody.ContactDelete(origin, syncId, revision, fields["J"]!!))
                }
                KIND_GROUP -> {
                    exact(fields, setOf("V", "SID", "R", "O", "J"))
                    jsonField(fields["J"])?.let { return it }
                    SyncResult.Ok(SyncBody.Group(origin, syncId, revision, fields["J"]!!))
                }
                KIND_SETTINGS -> {
                    exact(fields, setOf("V", "SID", "R", "O", "J"))
                    jsonField(fields["J"])?.let { return it }
                    SyncResult.Ok(SyncBody.Settings(origin, syncId, revision, fields["J"]!!))
                }
                KIND_DELETE -> {
                    exact(fields, setOf("V", "SID", "R", "O", "C", "ID"))
                    val conversation = conversation(fields["C"])
                        ?: return SyncResult.Err(SyncError.MALFORMED, "C")
                    val id = fields["ID"]!!
                    if (uuidOrNull(id) == null) return SyncResult.Err(SyncError.MALFORMED, "ID")
                    SyncResult.Ok(SyncBody.Delete(origin, syncId, revision, conversation, id))
                }
                else -> SyncResult.Err(SyncError.UNKNOWN_KIND, kind)
            }
        } catch (e: IllegalArgumentException) {
            SyncResult.Err(SyncError.MALFORMED)
        }
    }

    private fun decodePart(kind: String, syncId: String, origin: String, revision: Long,
                           fields: Map<String, String>): SyncResult {
        if (fields.keys != setOf("V", "SID", "R", "O", "PART", "P", "T", "H", "D")) {
            return SyncResult.Err(SyncError.MALFORMED, "part fields")
        }
        val indexCount = fields["P"]!!.split('/')
        if (indexCount.size != 2) return SyncResult.Err(SyncError.MALFORMED, "P")
        val index = indexCount[0].toIntOrNull() ?: return SyncResult.Err(SyncError.MALFORMED, "P index")
        val count = indexCount[1].toIntOrNull() ?: return SyncResult.Err(SyncError.MALFORMED, "P count")
        if (count !in 1..MAX_PARTS) return SyncResult.Err(SyncError.FIELD_TOO_LARGE, "count")
        if (index !in 1..count) return SyncResult.Err(SyncError.FIELD_TOO_LARGE, "index")
        val total = fields["T"]!!.toLongOrNull() ?: return SyncResult.Err(SyncError.MALFORMED, "T")
        if (total !in 1..MAX_LOGICAL_BYTES.toLong()) return SyncResult.Err(SyncError.FIELD_TOO_LARGE, "T")
        val hash = fields["H"]!!
        if (!HEX.matches(hash)) return SyncResult.Err(SyncError.MALFORMED, "H")
        val data = fields["D"]!!
        if (!BASE64URL.matches(data)) return SyncResult.Err(SyncError.MALFORMED, "D")
        val bytes = runCatching { Base64.getUrlDecoder().decode(data) }.getOrNull()
            ?: return SyncResult.Err(SyncError.MALFORMED, "D")
        if (bytes.size > MAX_PART_DATA) return SyncResult.Err(SyncError.FIELD_TOO_LARGE, "D")
        return SyncResult.Ok(SyncBody.Part(origin, syncId, revision, kind, index, count, total, hash, data))
    }

    private fun exact(fields: Map<String, String>, expected: Set<String>) {
        if (fields.keys != expected) throw IllegalArgumentException("fields")
    }

    private fun conversation(value: String?): String? =
        if (value != null && CONVERSATION.matches(value)) value else null


    private fun jsonField(value: String?): SyncResult? {
        if (value == null) return SyncResult.Err(SyncError.MALFORMED, "J")
        if (value.length > MAX_J_BYTES) return SyncResult.Err(SyncError.FIELD_TOO_LARGE, "J")
        if (!isBase64(value)) return SyncResult.Err(SyncError.MALFORMED, "J")
        return null
    }

    // Every key a wire body may use; kinds then require an exact subset.
    private val FIELD_KEYS = setOf("V", "SID", "R", "O", "TO", "P", "C", "T", "K", "J", "ID", "PART", "H", "D")

    private fun uuidOrNull(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()?.takeIf { it.toString() == value }
}
