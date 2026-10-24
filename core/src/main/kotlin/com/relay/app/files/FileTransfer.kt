package com.relay.app.files

import com.relay.app.transport.RelayPayload
import java.io.*
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Construct only from a payload whose E2E authentication the caller has already verified.
 * ID64 repeats RelayPayload.id to prevent accidental reassociation during resume/retry/SENT replay.
 * The authenticated body binds version, total size, count and ordered part hashes/lengths; no extra AAD is needed.
 */
class FileManifest private constructor(val fileId: String, val body: FileBody) {
    companion object {
        fun fromAuthenticatedPayload(payload: RelayPayload, isOurUrlBase: (String) -> Boolean): FileManifest? {
            val parsed = FileBody.parse(payload.body, isOurUrlBase) as? FileBodyResult.Ok ?: return null
            if (payload.v != 1 || payload.id != parsed.body.fileId) return null
            return FileManifest(payload.id, parsed.body.copy(parts = java.util.Collections.unmodifiableList(parsed.body.parts.toList())))
        }
    }
}

/** Creates a new key per file. Callback receives only COMPLETE ciphertext; durably retain it for retries.
 * Callback must consume/persist the stream before returning. Never store key/plaintext in a public staging path.
 */
class FileSender(private val staging: FileStaging = FileStaging.DEFAULT) {
    fun prepare(
        source: InputStream, size: Long, fileId: String, name: String, mime: String, urlBase: String,
        completedPart: (Int, StoredPart, InputStream) -> Unit,
    ): FileBody {
        val plan = requireNotNull(FilePartPlan.plan(size)) { "File size out of range" }
        require(fileId.isNotEmpty() && fileId.length <= 64)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = DigestInputStream(source, digest)
            val encryptor = FilePartEncryptor(key)
            val parts = plan.map { part ->
                staging.create().use { stage ->
                    val stored = stage.output().use { encryptor.encryptPart(input, part.length, it) }
                    stage.releaseForRead().use { completedPart(part.index, stored, it) }
                    FilePart(stored.sha256Hex, stored.storedBytes)
                }
            }
            if (input.read() != -1) throw IOException("File source longer than declared")
            val body = FileBody(FileSafety.safeName(name), FileSafety.safeMime(mime), size, Base64.getEncoder().encodeToString(key), parts, urlBase, fileHex(digest.digest()), fileId)
            FileBody.encode(body) // never emit an invalid manifest
            return body
        } finally { key.fill(0) }
    }
}

/** Final sink is untouched until every part, full size/digest and staging cleanup have succeeded.
 * Sink I/O can still fail partially: platform publish must itself use a private stage + atomic final rename.
 * Whole-file staging uses disk, bounded by MAX_SIZE; platform must enforce its account quota before starting.
 */
class FileAssembler(private val staging: FileStaging = FileStaging.DEFAULT) {
    fun assemble(
        manifest: FileManifest, expectedFileId: String,
        fetch: (Int, FilePart) -> InputStream, sink: OutputStream,
    ): Boolean {
        if (manifest.fileId != expectedFileId) return false
        val body = manifest.body
        val plan = FilePartPlan.plan(body.size) ?: return false
        val key = body.keyBytes()
        try {
            staging.create().use { stage ->
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                stage.output().use { file ->
                    val out = DigestOutputStream(file, digest)
                    val decryptor = FilePartDecryptor(key, staging)
                    for ((index, part) in body.parts.withIndex()) {
                        val result = fetch(index, part).use { decryptor.decryptPart(it, part, plan[index].length, out) }
                        if (result !is PartDecryptResult.Ok) return false
                        total += result.plainBytes
                    }
                    out.flush()
                }
                if (total != body.size || fileHex(digest.digest()) != body.sha256Hex) return false
                stage.releaseForRead().use { copyFileBytes(it, sink) }
                sink.flush()
                return true
            }
        } finally { key.fill(0) }
    }
}
