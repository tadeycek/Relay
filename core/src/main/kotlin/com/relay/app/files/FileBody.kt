package com.relay.app.files

import com.relay.app.media.MediaBody
import com.relay.app.messaging.ReplyBody
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** One part as the body declares it: SHA-256 (lowercase hex) of the STORED blob, and the stored blob's byte length. */
data class FilePart(val sha256Hex: String, val storedBytes: Int)

/**
 * A file reference, `TYPE:FILE|V:1|NAME64:…|MIME:…|SIZE:…|SHA256:…|ID64:…|KEY:…|PARTS:n|P1:<sha>,<bytes>|…|URLBASE:…[|CAP64:…]`.
 * [name] and [mime] are already sanitised (see [FileSafety]); [keyBase64] is the standard padded Base64 of the 32-byte key.
 */
data class FileBody(
    val name: String,
    val mime: String,
    val size: Long,
    val keyBase64: String,
    val parts: List<FilePart>,
    val urlBase: String,
    val sha256Hex: String,
    val fileId: String,
    val caption: String = "",
) {
    fun keyBytes(): ByteArray = Base64.getDecoder().decode(keyBase64)

    companion object {
        const val PREFIX = "TYPE:FILE|"
        const val MAX_BODY_CHARS = 16 * 1024
        const val MAX_CAPTION_LENGTH = MediaBody.MAX_CAPTION_LENGTH
        private const val MAX_CAP64_CHARS = 4096
        private val SIZE = Regex("0|[1-9][0-9]{0,9}")
        private val PARTS = Regex("[1-9]")
        private val PART_KEY = Regex("P[1-9][0-9]?")
        private val PART_VALUE = Regex("([0-9a-f]{64}),([1-9][0-9]{0,8})")
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val SINGLE_KEYS = setOf("V", "NAME64", "MIME", "SIZE", "SHA256", "ID64", "KEY", "PARTS", "URLBASE", "CAP64")
        private val REQUIRED = listOf("V", "NAME64", "MIME", "SIZE", "SHA256", "ID64", "KEY", "PARTS", "URLBASE")

        fun isFile(body: String): Boolean = body.startsWith(PREFIX)

        /** The URL a part is fetched from: `urlBase + sha256Hex`. */
        fun partUrl(urlBase: String, sha256Hex: String): String = urlBase + sha256Hex

        /**
         * Adapts a full-URL check such as `MailboxMedia.isOurs` to a URLBASE check, by asking it about
         * `urlBase + 64 zeros` (the check only inspects the prefix and that the tail is a 64-hex hash).
         */
        fun urlBasePredicate(isOurs: (String) -> Boolean): (String) -> Boolean = { base -> isOurs(base + "0".repeat(64)) }

        /**
         * Builds the wire body. [FileBody.name] and [FileBody.mime] are sanitised first. Throws
         * [IllegalArgumentException] if the result would not pass [parse] (the sender must never emit what
         * receivers reject); URLBASE ownership is not checked here because only the receiver knows its server.
         */
        fun encode(body: FileBody): String {
            val sb = StringBuilder(PREFIX)
            sb.append("V:1|NAME64:").append(b64(FileSafety.safeName(body.name).toByteArray(Charsets.UTF_8)))
            sb.append("|MIME:").append(FileSafety.safeMime(body.mime))
            sb.append("|SIZE:").append(body.size)
            sb.append("|SHA256:").append(body.sha256Hex)
            sb.append("|ID64:").append(b64(body.fileId.toByteArray(Charsets.UTF_8)))
            sb.append("|KEY:").append(body.keyBase64)
            sb.append("|PARTS:").append(body.parts.size)
            body.parts.forEachIndexed { i, p -> sb.append("|P").append(i + 1).append(':').append(p.sha256Hex).append(',').append(p.storedBytes) }
            sb.append("|URLBASE:").append(body.urlBase)
            if (body.caption.isNotEmpty()) sb.append("|CAP64:").append(b64(body.caption.toByteArray(Charsets.UTF_8)))
            val wire = sb.toString()
            val check = parse(wire) { true }
            require(check is FileBodyResult.Ok) { "Invalid file body: ${(check as FileBodyResult.Rejected).reason}" }
            return wire
        }

        /**
         * Strict parse. Never throws on any input. [isOurUrlBase] decides whether URLBASE is this build's own media
         * endpoint (build it with [urlBasePredicate] around `MailboxMedia.isOurs`); it is only asked about bases that
         * are already structurally acceptable (https, no credentials/query/fragment, trailing `/`).
         */
        fun parse(text: String, isOurUrlBase: (String) -> Boolean): FileBodyResult {
            fun no(r: FileBodyReject) = FileBodyResult.Rejected(r)
            if (!text.startsWith(PREFIX)) return no(FileBodyReject.NOT_FILE)
            if (text.length > MAX_BODY_CHARS) return no(FileBodyReject.TOO_LONG)
            if (text.any { it.code !in 0x21..0x7e }) return no(FileBodyReject.BAD_CHARS)

            val single = HashMap<String, String>()
            val partFields = HashMap<String, String>()
            for (field in text.substring(PREFIX.length).split('|')) {
                val i = field.indexOf(':')
                if (i <= 0) return no(FileBodyReject.MALFORMED)
                val k = field.substring(0, i); val v = field.substring(i + 1)
                val target = when {
                    k in SINGLE_KEYS -> single
                    PART_KEY.matches(k) -> partFields
                    else -> return no(FileBodyReject.UNKNOWN_KEY)
                }
                if (target.put(k, v) != null) return no(FileBodyReject.DUPLICATE_KEY)
            }
            if (REQUIRED.any { it !in single }) return no(FileBodyReject.MISSING_KEY)
            if (single.getValue("V") != "1") return no(FileBodyReject.UNSUPPORTED_VERSION)

            val nameBytes = strictBase64(single.getValue("NAME64")) ?: return no(FileBodyReject.BAD_NAME)
            if (nameBytes.size > FileSafety.MAX_NAME_BYTES) return no(FileBodyReject.BAD_NAME)
            val rawName = strictUtf8(nameBytes) ?: return no(FileBodyReject.BAD_NAME)
            val name = FileSafety.safeName(rawName)
            val mime = FileSafety.safeMime(single.getValue("MIME"))

            val sizeText = single.getValue("SIZE")
            if (!SIZE.matches(sizeText)) return no(FileBodyReject.BAD_SIZE)
            val size = sizeText.toLong()
            if (size < 0 || size > FilePartPlan.MAX_SIZE) return no(FileBodyReject.BAD_SIZE)

            val digest = single.getValue("SHA256")
            if (!HEX64.matches(digest)) return no(FileBodyReject.BAD_DIGEST)
            val fileId = strictBase64(single.getValue("ID64"))?.let { strictUtf8(it) }
            if (fileId == null || fileId.isEmpty() || fileId.length > 64) return no(FileBodyReject.BAD_FILE_ID)

            val keyBytes = strictBase64(single.getValue("KEY"))
            if (keyBytes == null || keyBytes.size != 32) return no(FileBodyReject.BAD_KEY)

            val partsText = single.getValue("PARTS")
            if (!PARTS.matches(partsText)) return no(FileBodyReject.BAD_PARTS)
            val n = partsText.toInt()
            if (n > FilePartPlan.MAX_PARTS) return no(FileBodyReject.BAD_PARTS)
            val plan = FilePartPlan.plan(size)!!
            if (plan.size != n || partFields.size != n) return no(FileBodyReject.PART_COUNT)

            val parts = ArrayList<FilePart>(n)
            for (i in 1..n) {
                val v = partFields["P$i"] ?: return no(FileBodyReject.PART_COUNT)
                val m = PART_VALUE.matchEntire(v) ?: return no(FileBodyReject.BAD_PART)
                parts += FilePart(m.groupValues[1], m.groupValues[2].toInt())
            }
            if (parts.map { it.sha256Hex }.toSet().size != parts.size) return no(FileBodyReject.DUPLICATE_PART)

            parts.forEachIndexed { i, p ->
                if (p.storedBytes > FilePartPlan.SERVER_BLOB_CAP) return no(FileBodyReject.BLOB_TOO_LARGE)
            }
            parts.forEachIndexed { i, p ->
                if (p.storedBytes == plan[i].storedBytes) return@forEachIndexed
                if (FilePartPlan.plainSize(p.storedBytes) == null || i != parts.lastIndex) return no(FileBodyReject.BLOB_SIZE_INCONSISTENT)
                return no(FileBodyReject.TOTAL_MISMATCH)
            }

            val urlBase = single.getValue("URLBASE")
            if (!MediaBody.isAcceptableUrl(urlBase) || !urlBase.endsWith("/") || '?' in urlBase || '#' in urlBase || !isOurUrlBase(urlBase)) {
                return no(FileBodyReject.FOREIGN_URL)
            }

            var caption = ""
            single["CAP64"]?.let { cap ->
                if (cap.length > MAX_CAP64_CHARS) return no(FileBodyReject.BAD_CAPTION)
                val decoded = strictBase64(cap)?.let { strictUtf8(it) } ?: return no(FileBodyReject.BAD_CAPTION)
                caption = if (decoded.startsWith("TYPE:REPLY|")) {
                    val reply = ReplyBody.parse(decoded) ?: return no(FileBodyReject.BAD_CAPTION)
                    if (reply.text.length > MAX_CAPTION_LENGTH) return no(FileBodyReject.BAD_CAPTION)
                    decoded
                } else MediaBody.sanitizeCaption(decoded)
            }
            return FileBodyResult.Ok(FileBody(name, mime, size, single.getValue("KEY"), parts, urlBase, digest, fileId, caption))
        }

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

        /** Standard padded Base64 only, and only the canonical encoding of the bytes it yields. */
        private fun strictBase64(s: String): ByteArray? {
            val bytes = try { Base64.getDecoder().decode(s) } catch (e: IllegalArgumentException) { return null }
            return if (b64(bytes) == s) bytes else null
        }

        private fun strictUtf8(b: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) { null }
    }
}

sealed interface FileBodyResult {
    data class Ok(val body: FileBody) : FileBodyResult
    data class Rejected(val reason: FileBodyReject) : FileBodyResult
}

enum class FileBodyReject {
    NOT_FILE, TOO_LONG, BAD_CHARS, MALFORMED, UNKNOWN_KEY, DUPLICATE_KEY, MISSING_KEY, UNSUPPORTED_VERSION,
    BAD_NAME, BAD_SIZE, BAD_DIGEST, BAD_FILE_ID, BAD_KEY, BAD_PARTS, PART_COUNT, BAD_PART, DUPLICATE_PART,
    BLOB_TOO_LARGE, BLOB_SIZE_INCONSISTENT, TOTAL_MISMATCH, FOREIGN_URL, BAD_CAPTION,
}
