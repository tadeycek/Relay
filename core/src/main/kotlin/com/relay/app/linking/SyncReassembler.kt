package com.relay.app.linking

import java.security.MessageDigest
import java.util.Base64

enum class ReassemblyError {
    TOO_MANY_PARTS,
    OVERSIZE_TOTAL,
    BAD_PART,
    BAD_HASH,
    EXPIRED,
    CONFLICTING_REVISION,
    CONFLICTING_PART,
    TOO_MANY_ASSEMBLIES,
}

sealed interface ReassemblyResult {
    /** Part stored; [received] unique parts of [count] are present. */
    data class Pending(val received: Int, val count: Int) : ReassemblyResult

    /** A byte-identical duplicate of an already stored part; it did not change the assembly. */
    data class Duplicate(val received: Int, val count: Int) : ReassemblyResult

    /** All parts arrived, in any order, and the whole-body hash and length verified. */
    data class Complete(val body: String) : ReassemblyResult

    data class Rejected(val reason: ReassemblyError) : ReassemblyResult
}

/**
 * Bounded, in-memory reassembly of [SyncBody.Part]s (G3).
 *
 * - Keyed by `syncId`; a different `revision` for the same id is rejected ([ReassemblyError.CONFLICTING_REVISION]).
 * - Parts may arrive in any order. A byte-identical duplicate is tolerated ([ReassemblyResult.Duplicate]);
 *   different bytes for the same index are a conflict.
 * - At most [MAX_PARTS] parts, [MAX_TOTAL_BYTES] logical bytes, [MAX_PART_BYTES] per part and
 *   [maxConcurrent] assemblies at once. Assemblies expire after [ttlMillis].
 * - Completion verifies both the declared total length and the SHA-256 of the concatenation; nothing
 *   is published as complete unless both match.
 *
 * Not thread-safe: one instance per receiving device, confined to its own dispatcher.
 */
class SyncReassembler(
    private val maxConcurrent: Int = MAX_CONCURRENT,
    private val ttlMillis: Long = TTL_MILLIS,
    private val maxParts: Int = MAX_PARTS,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val maxPartBytes: Int = MAX_PART_BYTES,
) {

    private class Assembly(
        val createdAt: Long,
        val revision: Long,
        val count: Int,
        val totalBytes: Long,
        val sha256Hex: String,
        val parts: Array<ByteArray?>,
        var received: Int,
    )

    private val assemblies = LinkedHashMap<String, Assembly>()

    val activeCount: Int get() = assemblies.size

    fun offer(part: SyncBody.Part, now: Long = System.currentTimeMillis()): ReassemblyResult {
        // Structural bounds first: nothing is stored for a part that can never participate.
        if (part.count !in 1..maxParts || part.index !in 1..part.count) {
            return ReassemblyResult.Rejected(ReassemblyError.TOO_MANY_PARTS)
        }
        if (part.totalBytes !in 1..maxTotalBytes) {
            return ReassemblyResult.Rejected(ReassemblyError.OVERSIZE_TOTAL)
        }
        val bytes = runCatching { Base64.getUrlDecoder().decode(part.dataBase64Url) }.getOrNull()
            ?: return ReassemblyResult.Rejected(ReassemblyError.BAD_PART)
        if (bytes.size > maxPartBytes) return ReassemblyResult.Rejected(ReassemblyError.BAD_PART)

        val existing = assemblies[part.syncId]
        if (existing != null && now - existing.createdAt > ttlMillis) {
            assemblies.remove(part.syncId)
            return ReassemblyResult.Rejected(ReassemblyError.EXPIRED)
        }
        if (existing != null) {
            if (existing.revision != part.revision) {
                return ReassemblyResult.Rejected(ReassemblyError.CONFLICTING_REVISION)
            }
            if (existing.count != part.count || existing.totalBytes != part.totalBytes ||
                existing.sha256Hex != part.sha256Hex
            ) {
                return ReassemblyResult.Rejected(ReassemblyError.CONFLICTING_PART)
            }
            val previous = existing.parts[part.index - 1]
            if (previous != null) {
                return if (previous.contentEquals(bytes)) {
                    ReassemblyResult.Duplicate(existing.received, existing.count)
                } else {
                    ReassemblyResult.Rejected(ReassemblyError.CONFLICTING_PART)
                }
            }
            existing.parts[part.index - 1] = bytes
            existing.received += 1
            return completeIfReady(part.syncId, existing)
        }

        if (assemblies.size >= maxConcurrent) {
            return ReassemblyResult.Rejected(ReassemblyError.TOO_MANY_ASSEMBLIES)
        }
        val assembly = Assembly(
            createdAt = now,
            revision = part.revision,
            count = part.count,
            totalBytes = part.totalBytes,
            sha256Hex = part.sha256Hex,
            parts = arrayOfNulls(part.count),
            received = 0,
        )
        assembly.parts[part.index - 1] = bytes
        assembly.received = 1
        assemblies[part.syncId] = assembly
        return completeIfReady(part.syncId, assembly)
    }

    /** Drops one in-flight assembly (e.g. the conversation was deleted). */
    fun discard(syncId: String) {
        assemblies.remove(syncId)
    }

    fun clear() = assemblies.clear()

    private fun completeIfReady(syncId: String, assembly: Assembly): ReassemblyResult {
        if (assembly.received < assembly.count) {
            return ReassemblyResult.Pending(assembly.received, assembly.count)
        }
        val total = assembly.parts.fold(0) { n, bytes -> n + (bytes?.size ?: 0) }
        val joined = ByteArray(total)
        var offset = 0
        for (bytes in assembly.parts) {
            val b = bytes ?: return ReassemblyResult.Pending(assembly.received, assembly.count)
            b.copyInto(joined, offset)
            offset += b.size
        }
        assemblies.remove(syncId)
        if (joined.size.toLong() != assembly.totalBytes) {
            return ReassemblyResult.Rejected(ReassemblyError.BAD_HASH)
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(joined).joinToString("") { "%02x".format(it) }
        if (hash != assembly.sha256Hex) {
            return ReassemblyResult.Rejected(ReassemblyError.BAD_HASH)
        }
        return ReassemblyResult.Complete(String(joined, Charsets.UTF_8))
    }

    companion object {
        const val MAX_PARTS = SyncCodec.MAX_PARTS
        const val MAX_TOTAL_BYTES = 256L * 1024
        const val MAX_PART_BYTES = SyncCodec.MAX_PART_DATA
        const val MAX_CONCURRENT = 8
        const val TTL_MILLIS = 10L * 60 * 1000
    }
}
