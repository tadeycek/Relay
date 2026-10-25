package com.relay.app.desktop

import com.relay.app.media.MediaBody
import com.relay.app.media.MediaContainer
import com.relay.app.media.MediaCrypto
import com.relay.app.media.MediaRef
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.SendResult
import com.relay.app.transport.mailbox.MailboxMedia
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable, encrypted-database journal row for one photo/video bubble. The media key lives in [body]. */
data class DesktopMediaRecord(
    val id: String, val peer: String, val sender: String, val ts: Long, val outgoing: Boolean,
    val body: String, val state: String,
    val localSource: Boolean = false, val uploaded: Boolean = false, val sent: Boolean = false,
    val syncQueued: Boolean = false, val syncPublished: Boolean = false, val confirmed: Boolean = false,
    /** UNKNOWN until the plaintext was authenticated and classified; then IMAGE, VIDEO or ATTACHMENT. */
    val verdict: String = "UNKNOWN", val width: Long = 0, val height: Long = 0, val mime: String = "",
    val note: String? = null, val error: String? = null, val permanent: Boolean = false,
    val missingSince: Long = 0,
) {
    fun encode(): String = JSONObject().put("id", id).put("peer", peer).put("sender", sender).put("ts", ts)
        .put("outgoing", outgoing).put("body", body).put("state", state).put("localSource", localSource)
        .put("uploaded", uploaded).put("sent", sent).put("syncQueued", syncQueued).put("syncPublished", syncPublished)
        .put("confirmed", confirmed).put("verdict", verdict).put("width", width).put("height", height).put("mime", mime)
        .put("note", note ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL).put("permanent", permanent)
        .put("missingSince", missingSince).toString()

    companion object {
        fun decode(s: String): DesktopMediaRecord {
            val j = JSONObject(s)
            fun opt(k: String) = if (j.isNull(k)) null else j.getString(k)
            return DesktopMediaRecord(
                j.getString("id"), j.getString("peer"), j.getString("sender"), j.getLong("ts"), j.getBoolean("outgoing"),
                j.getString("body"), j.getString("state"), j.optBoolean("localSource"), j.optBoolean("uploaded"),
                j.optBoolean("sent"), j.optBoolean("syncQueued"), j.optBoolean("syncPublished"), j.optBoolean("confirmed"),
                j.optString("verdict", "UNKNOWN"), j.optLong("width"), j.optLong("height"), j.optString("mime", ""),
                if (j.has("note")) opt("note") else null, if (j.has("error")) opt("error") else null, j.optBoolean("permanent"), j.optLong("missingSince"),
            )
        }
    }
}

/** One-blob transport. The production implementation is [DesktopMediaClient]; tests substitute a fake. */
interface DesktopMediaTransport {
    /** `https://host/v1/media/` of the configured mailbox. Only URLs under it are ever fetched. */
    val urlBase: String
    fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation, onBytes: (Long) -> Unit)
    fun download(file: File, hash: String, maxBytes: Long, cancellation: MailboxMedia.Cancellation)
    fun confirm(hash: String, cancellation: MailboxMedia.Cancellation): Boolean
    fun confirmOutcome(hash: String, cancellation: MailboxMedia.Cancellation): MediaConfirmation =
        if (confirm(hash, cancellation)) MediaConfirmation.Success else MediaConfirmation.Retry
}

sealed interface MediaConfirmation {
    data object Success : MediaConfirmation
    data object Retry : MediaConfirmation
    data object Refused : MediaConfirmation
    data object Permanent : MediaConfirmation
}

class DesktopMediaClient(private val client: MailboxMedia.Client) : DesktopMediaTransport {
    override val urlBase = client.urlFor("0".repeat(64)).removeSuffix("0".repeat(64))
    override fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation, onBytes: (Long) -> Unit) {
        client.upload(MailboxMedia.RawSource(file.length()) { object : java.io.FilterInputStream(file.inputStream()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) onBytes(it.toLong()) }
        } }, hash, recipients, cancellation)
    }
    override fun download(file: File, hash: String, maxBytes: Long, cancellation: MailboxMedia.Cancellation) {
        client.downloadTo(client.urlFor(hash), hash, file, maxBytes, cancellation)
    }
    override fun confirm(hash: String, cancellation: MailboxMedia.Cancellation) =
        client.confirm(client.urlFor(hash), cancellation) == MailboxMedia.Confirmation.Confirmed
    override fun confirmOutcome(hash: String, cancellation: MailboxMedia.Cancellation): MediaConfirmation =
        when (val result = client.confirm(client.urlFor(hash), cancellation)) {
            MailboxMedia.Confirmation.Confirmed -> MediaConfirmation.Success
            is MailboxMedia.Confirmation.Failed -> when {
                result.status == 401 || result.status == 403 -> MediaConfirmation.Refused
                result.retryable -> MediaConfirmation.Retry
                else -> MediaConfirmation.Permanent
            }
        }
}

/**
 * Serialized, durable photo/video work for direct chats.
 *
 * On disk: one private folder per message holding the verified ciphertext exactly as the server stores
 * it (`blob`). Plaintext never rests on disk except in an explicit user export/save. The journal row
 * (including the media key) lives only in the encrypted database. Receiving is always on; sending
 * defaults OFF like WP09 files.
 */
class DesktopMedia(
    private val root: Path, private val store: DesktopMessageStore, private val media: DesktopMediaTransport,
    private val ownIdentity: String, val sendEnabled: Boolean = false,
    private val quotaBytes: Long = 1024L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notify: () -> Unit = {},
) {
    /** Extra change hook (the engine bumps its UI revision here). Called after every durable state change. */
    @Volatile var onChange: () -> Unit = {}
    private val work = Mutex()
    private val fence = Any()
    private val active = ConcurrentHashMap<String, MailboxMedia.Cancellation>()
    private class Progress(val total: Long, val done: () -> Long)
    private val progressById = ConcurrentHashMap<String, Progress>()
    private class IntegrityFailure(message: String) : IOException(message)
    init { privateDirectory(root) }

    private fun hashName(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun folder(id: String): Path = root.resolve(hashName(id)).also(::privateDirectory)
    private fun blobPath(id: String) = folder(id).resolve("blob")
    private fun gone(id: String) = store.mediaRecord(id)?.state in TOMBSTONES
    private fun check(id: String) { if (gone(id)) throw InterruptedIOException("Media cancelled") }
    private fun update(r: DesktopMediaRecord) = synchronized(fence) { check(r.id); store.saveMedia(r); changed() }
    private fun changed() { notify(); onChange() }

    private fun reserve(blobSize: Long) {
        privateDirectory(root)
        val used = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum() }
        val need = Math.addExact(Math.multiplyExact(blobSize, 2), 65536L)
        require(need <= quotaBytes - used) { "Private media quota exceeded" }
        require(Files.getFileStore(root).usableSpace >= need) { "Insufficient disk space" }
    }

    // ---- sending -------------------------------------------------------------------------------

    /**
     * Validates, compresses (phone rules), encrypts and stores the ciphertext, then commits the journal
     * row and the visible bubble in one transaction. Nothing touches the network.
     */
    fun prepare(peer: String, source: Path, caption: String = ""): DesktopMediaRecord = synchronized(fence) {
        check(sendEnabled) { "Photo and video sending is disabled" }
        require(Regex("[0-9a-f]{64}").matches(peer)) { "Photos and videos can only be sent in direct chats" }
        DesktopFiles.noSymlinks(source)
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "Not a regular file" }
        val size = Files.size(source)
        require(size in 1..MediaSafety.MAX_SOURCE_BYTES) { if (size == 0L) "The file is empty" else "The file is too large" }
        val prepared = MediaSafety.prepareOutgoing(Files.readAllBytes(source))
        val enc = MediaCrypto.encrypt(prepared.bytes)
        val sha = enc.sha256Hex
        reserve(enc.blob.size.toLong())
        val id = PayloadCodec.newId(); val ts = clock()
        val ref = MediaRef(media.urlBase + sha, sha, enc.keyBase64, prepared.kind.mime, prepared.bytes.size.toLong(), caption)
        val body = MediaBody.format(ref)
        try {
            val blob = blobPath(id)
            Files.createFile(blob, MODE_FILE)
            Files.newOutputStream(blob).use { it.write(enc.blob) }
            syncFile(blob); syncDirectory(blob.parent)
            val verdict = if (prepared.kind.isVideo) "VIDEO" else "IMAGE"
            val r = DesktopMediaRecord(id, peer, ownIdentity, ts, true, body, "QUEUED", localSource = true,
                verdict = verdict, width = prepared.width, height = prepared.height, mime = prepared.kind.mime)
            store.saveMediaWithMessage(r, DesktopMessage(id, peer, ownIdentity, body, ts, true))
            changed(); r
        } catch (e: Exception) { deleteFolderQuietly(id); throw e }
    }

    // ---- receiving -----------------------------------------------------------------------------

    /** True when [message] is a media reference this controller owns (so the mailbox may ack it). */
    fun accept(message: DesktopMessage): Boolean {
        val ref = MediaBody.parse(message.body) ?: return false
        require(!message.conversationId.startsWith("group:")) { "Group media receive deferred" }
        if (message.ts < clock() - RETENTION_MS) return true // older than the retention window: handled, not stored
        if (ref.size <= 0) return false
        store.insertMessage(message); changed(); return true
    }

    /** Cancel is a persistent tombstone fenced against every state update and active network I/O. */
    fun cancel(id: String, delete: Boolean = false) {
        synchronized(fence) {
            val r = store.mediaRecord(id) ?: return
            if (delete) {
                store.saveMedia(r.copy(state = "DELETED", body = DELETED_TEXT, error = null))
                store.tombstoneMessage(id, DELETED_TEXT)
            } else store.saveMedia(r.copy(state = "CANCELLED", error = null))
            active[id]?.cancel()
        }
        deleteFolderQuietly(id)
        changed()
    }

    /** Erases media older than the retention window: bytes, exports, key, journal row and bubble. */
    fun purgeExpired(now: Long = clock()): Int {
        var n = 0
        for (r in store.mediaRecords()) if (r.ts < now - RETENTION_MS) {
            synchronized(fence) { active[r.id]?.cancel(); store.deleteMessage(r.id) }
            deleteFolderQuietly(r.id); n++
        }
        if (n > 0) changed()
        return n
    }

    /** Startup: removes orphan folders, stale staging, partial downloads and plaintext exports. */
    fun sweep() {
        privateDirectory(root)
        val live = store.mediaRecords().filter { it.state !in TOMBSTONES }.map { hashName(it.id) }.toSet()
        Files.newDirectoryStream(root).use { entries ->
            for (p in entries) {
                val name = p.fileName.toString()
                if (Files.isSymbolicLink(p)) { Files.deleteIfExists(p); continue }
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    if (name !in live) deleteFolderQuietlyName(name)
                    else Files.newDirectoryStream(p).use { inner -> for (f in inner) if (f.fileName.toString() != "blob") runCatching { deleteTree(f) } }
                } else runCatching { Files.deleteIfExists(p) } // .stage-* and any stray file
            }
        }
    }

    // ---- the transfer state machine ------------------------------------------------------------

    suspend fun run(id: String, send: suspend (DesktopMediaRecord, List<String>) -> SendResult,
                    sentCopy: suspend (DesktopMediaRecord) -> Unit = {}) = work.withLock {
        var r = store.mediaRecord(id) ?: return@withLock
        if (r.state in TOMBSTONES) { deleteFolderQuietly(id); return@withLock }
        if (r.state == "COMPLETE" || r.state == "UNSUPPORTED" || (r.state == "FAILED" && r.permanent)) return@withLock
        if (r.localSource && !sendEnabled) { update(r.copy(state = "SEND_DISABLED", error = "Photo and video sending is disabled")); return@withLock }
        val ref = MediaBody.parse(r.body)
        if (ref == null || r.peer.startsWith("group:") || ref.url != media.urlBase + ref.sha256Hex ||
            ref.size > MediaSafety.MAX_PLAIN_BYTES) {
            update(r.copy(state = "UNSUPPORTED", error = "Unsupported media reference")); return@withLock
        }
        val token = MailboxMedia.Cancellation(); active[id] = token
        try {
            // The sync event can precede its grant; throttle retries across restarts without holding a worker.
            if (!r.localSource && r.missingSince > 0 && clock() - r.missingSince < 1_000L) return@withLock
            update(r.copy(state = "TRANSFERRING", error = null)); r = store.mediaRecord(id)!!
            val blob = blobPath(id)
            if (r.localSource) {
                if (!Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS) || digest(blob) != ref.sha256Hex) throw IntegrityFailure("Local ciphertext failed its integrity check")
                if (!r.uploaded) {
                    val total = Files.size(blob); val sent = AtomicLong()
                    progressById[id] = Progress(total) { sent.get() }
                    media.upload(blob.toFile(), ref.sha256Hex, listOf(r.peer, ownIdentity).distinct(), token) { sent.addAndGet(it) }
                    r = r.copy(uploaded = true); update(r)
                }
                if (!r.sent) {
                    check(id)
                    when (val result = send(r, listOf(ref.sha256Hex))) {
                        is SendResult.Sent -> { r = r.copy(sent = true); update(r) }
                        is SendResult.Failed -> {
                            update(r.copy(state = "FAILED", error = result.reason, permanent = !result.retryable)); return@withLock
                        }
                    }
                }
                sentCopy(r); r = store.mediaRecord(id)!!
                if (!r.syncPublished) {
                    // Queued but no longer in the outbox = the publisher dropped it (grant gone, rejected or expired).
                    // Same-id republication answers duplicate and cannot repair it, so stop here and never confirm.
                    if (r.syncQueued && store.outbox(1000).none { ref.sha256Hex in it.mediaDigests }) {
                        update(r.copy(state = "FAILED", permanent = true, error = "The copy for your other devices could not be published; send the file again"))
                        return@withLock
                    }
                    throw IOException("SENT copy for your other devices is still pending")
                }
            } else {
                if (!Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS)) {
                    reserve(ref.size + 28)
                    val partial = folder(id).resolve(".partial")
                    Files.deleteIfExists(partial)
                    val total = ref.size + 28
                    progressById[id] = Progress(total) { runCatching { Files.size(partial) }.getOrDefault(0L).coerceAtMost(total) }
                    val maxBytes = minOf(MailboxMedia.MAX_STORED_BYTES, MediaContainer.wrappedSize((ref.size + 28).toInt()).toLong())
                    media.download(partial.toFile(), ref.sha256Hex, maxBytes, token)
                    check(id)
                    if (digest(partial) != ref.sha256Hex) { Files.deleteIfExists(partial); throw IntegrityFailure("Downloaded file does not match its hash") }
                    syncFile(partial)
                    synchronized(fence) { check(id); Files.move(partial, blob, StandardCopyOption.ATOMIC_MOVE) }
                    syncDirectory(blob.parent)
                }
                // Re-open on EVERY attempt, even when the journal already says STORED/CONFIRM_PENDING.
                // The row's cached verdict is not proof that the ciphertext remains intact.
                val plain = try { open(ref, blob) } catch (e: IntegrityFailure) { Files.deleteIfExists(blob); throw e }
                val v = MediaSafety.verdictForIncoming(ref.mime, plain)
                if (r.verdict == "UNKNOWN" || r.verdict != when (v) {
                    is MediaVerdict.Image -> "IMAGE"
                    is MediaVerdict.Video -> "VIDEO"
                    is MediaVerdict.Attachment -> "ATTACHMENT"
                }) {
                    r = when (v) {
                        is MediaVerdict.Image -> r.copy(verdict = "IMAGE", width = v.width, height = v.height, mime = ref.mime, note = null)
                        is MediaVerdict.Video -> r.copy(verdict = "VIDEO", mime = ref.mime, note = null)
                        is MediaVerdict.Attachment -> r.copy(verdict = "ATTACHMENT", mime = ref.mime, note = v.reason)
                    }
                }
                update(r.copy(state = "STORED", error = null, missingSince = 0)); r = store.mediaRecord(id)!!
            }
            check(id)
            // Per-device confirmation only after durable, verified private storage (and SENT grants for the sender).
            val verified = open(ref, blob)
            val verdict = MediaSafety.verdictForIncoming(ref.mime, verified)
            val kind = when (verdict) {
                is MediaVerdict.Image -> "IMAGE"
                is MediaVerdict.Video -> "VIDEO"
                is MediaVerdict.Attachment -> "ATTACHMENT"
            }
            if (kind != r.verdict) throw IntegrityFailure("Stored media format verdict changed")
            when (media.confirmOutcome(ref.sha256Hex, token)) {
                MediaConfirmation.Success -> update(r.copy(state = "COMPLETE", confirmed = true, error = null))
                MediaConfirmation.Retry -> update(r.copy(state = "CONFIRM_PENDING", error = "Confirmation pending; will retry"))
                MediaConfirmation.Refused -> update(r.copy(state = "FAILED", error = "The server refused this device", permanent = true))
                MediaConfirmation.Permanent -> update(r.copy(state = "FAILED", error = "The server rejected confirmation; user action required", permanent = true))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { token.cancel(); throw e }
        catch (e: Exception) {
            val (message, permanent) = if (e is MailboxMedia.HttpFailure && e.status == 404 && !r.localSource) {
                val first = r.missingSince.takeIf { it > 0 } ?: clock()
                if (clock() - first < 60_000L && clock() - r.ts < RETENTION_MS)
                    "Media grant pending; will retry" to false
                else "This media is no longer available on the server" to true
            } else classify(e)
            synchronized(fence) {
                val cur = store.mediaRecord(id)
                if (cur != null && cur.state !in TOMBSTONES) store.saveMedia(cur.copy(state = "FAILED", error = message, permanent = permanent,
                    missingSince = if (e is MailboxMedia.HttpFailure && e.status == 404 && !permanent) cur.missingSince.takeIf { it > 0 } ?: clock() else cur.missingSince))
            }
            changed()
        } finally {
            active.remove(id); progressById.remove(id)
            if (gone(id)) deleteFolderQuietly(id)
        }
    }

    private fun classify(e: Exception): Pair<String, Boolean> = when {
        e is IntegrityFailure -> "Integrity check failed: the file was altered or is corrupt, so it was discarded" to true
        e is MailboxMedia.HttpFailure && e.status == 404 -> "This media is no longer available on the server" to true
        e is MailboxMedia.HttpFailure && (e.status == 401 || e.status == 403) -> "The server refused this device (not a recipient, or its authorization was revoked)" to true
        e is MailboxMedia.HttpFailure -> "Server error ${e.status}; will retry" to !e.retryable
        e is InterruptedIOException -> "Transfer cancelled" to false
        e is IOException && (e.message ?: "").contains("mismatch", ignoreCase = true) -> "Integrity check failed: the download does not match its hash" to true
        e is IllegalArgumentException -> (e.message ?: "Cannot store this media") to false
        e is IOException -> "Network problem: check your connection; will retry" to false
        else -> (e.message ?: "Transfer failed; retry") to false
    }

    // ---- reading stored media ------------------------------------------------------------------

    /** Authenticates and decrypts the stored ciphertext. Never returns bytes that failed any check. */
    private fun open(ref: MediaRef, blob: Path): ByteArray {
        DesktopFiles.noSymlinks(blob)
        if (!Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS)) throw IntegrityFailure("Stored media is missing")
        if (Files.size(blob) > MailboxMedia.MAX_STORED_BYTES) throw IntegrityFailure("Stored media is too large")
        val bytes = Files.readAllBytes(blob)
        if (MediaCrypto.sha256Hex(bytes) != ref.sha256Hex) throw IntegrityFailure("Stored media failed its hash check")
        // Older phones wrapped the ciphertext in a JPEG container; the unwrap is strict, so raw blobs fall through.
        val cipher = MediaContainer.unwrap(bytes) ?: bytes
        val plain = MediaCrypto.decrypt(cipher, ref.keyBase64) ?: throw IntegrityFailure("Stored media failed authentication")
        if (plain.size.toLong() != ref.size) throw IntegrityFailure("Stored media size disagrees with its reference")
        return plain
    }

    private fun usable(id: String): Pair<DesktopMediaRecord, MediaRef> {
        val r = store.mediaRecord(id)
        if (r == null || r.state in TOMBSTONES) throw IllegalStateException("No such media")
        val ref = MediaBody.parse(r.body) ?: throw IllegalStateException("Unsupported media reference")
        return r to ref
    }

    /** Verified plaintext. Throws [IllegalStateException] when anything is missing, altered or not yet stored. */
    fun plaintext(id: String): ByteArray {
        val (r, ref) = usable(id)
        if (r.state in setOf("QUEUED", "TRANSFERRING") && !r.localSource) throw IllegalStateException("Media has not been downloaded")
        return try { open(ref, blobPath(id)) } catch (e: IOException) { throw IllegalStateException(e.message ?: "Stored media failed verification") }
    }

    /** Bytes the UI may hand to the image decoder: only a verified, bounded, type-consistent image. */
    fun previewBytes(id: String): ByteArray? {
        val r = store.mediaRecord(id) ?: return null
        if (r.verdict != "IMAGE") return null
        val plain = runCatching { plaintext(id) }.getOrNull() ?: return null
        return plain.takeIf { MediaSafety.verdictForIncoming(r.mime, it) is MediaVerdict.Image }
    }

    fun saveAs(id: String, target: Path): Path = synchronized(fence) {
        val plain = plaintext(id)
        val parent = target.toAbsolutePath().parent
        DesktopFiles.noSymlinks(parent)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Destination already exists" }
        val temp = Files.createTempFile(parent, ".relay-save-", "", MODE_FILE)
        try {
            Files.write(temp, plain); syncFile(temp)
            Files.createLink(target, temp); syncDirectory(parent); target
        } finally { Files.deleteIfExists(temp) }
    }

    /** Explicit "open externally": a private 0600 copy inside the media folder, removed with the media. */
    fun exportForOpen(id: String): Path = synchronized(fence) {
        val (r, _) = usable(id)
        val plain = plaintext(id)
        val dir = folder(id)
        Files.newDirectoryStream(dir, "open-*").use { for (old in it) Files.deleteIfExists(old) }
        val ext = MediaKind.entries.firstOrNull { it.mime == r.mime }?.let { when (it) { MediaKind.JPEG -> "jpg"; MediaKind.PNG -> "png"; MediaKind.GIF -> "gif"; MediaKind.WEBP -> "webp"; MediaKind.MP4 -> "mp4" } } ?: "bin"
        val out = dir.resolve("open-" + java.util.UUID.randomUUID().toString().take(8) + "." + ext)
        Files.createFile(out, MODE_FILE); Files.write(out, plain); syncFile(out); out
    }

    /** (bytes so far, total) while a transfer is running; null otherwise. */
    fun progress(id: String): Pair<Long, Long>? = progressById[id]?.let { it.done() to it.total }

    // ---- file helpers --------------------------------------------------------------------------

    private fun deleteFolderQuietly(id: String) = deleteFolderQuietlyName(hashName(id))
    private fun deleteFolderQuietlyName(name: String) {
        val dir = root.resolve(name)
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        runCatching { deleteTree(dir) }
    }
    private fun deleteTree(p: Path) {
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) Files.newDirectoryStream(p).use { for (c in it) deleteTree(c) }
        Files.deleteIfExists(p)
    }
    private fun digest(path: Path): String {
        val d = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** The phone keeps media 7 days; so does the server. */
        const val RETENTION_MS = 7L * 24 * 3600 * 1000
        const val DELETED_TEXT = "Media deleted"
        private val TOMBSTONES = setOf("CANCELLED", "DELETED")
        private val MODE_FILE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        private fun privateDirectory(path: Path) {
            DesktopFiles.noSymlinks(path)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
        private fun syncFile(path: Path) { java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) } }
        private fun syncDirectory(path: Path) { java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }
    }
}

/** Used when no HTTPS mailbox is configured: stored media still opens; transfers fail honestly and retryably. */
object OfflineMediaTransport : DesktopMediaTransport {
    override val urlBase = "https://offline.invalid/v1/media/"
    private fun offline(): Nothing = throw IOException("No mailbox configured: connection unavailable")
    override fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation, onBytes: (Long) -> Unit) = offline()
    override fun download(file: File, hash: String, maxBytes: Long, cancellation: MailboxMedia.Cancellation) = offline()
    override fun confirm(hash: String, cancellation: MailboxMedia.Cancellation): Boolean = false
}
