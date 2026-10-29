package com.relay.app.desktop

import com.relay.app.files.*
import com.relay.app.transport.*
import com.relay.app.transport.mailbox.MailboxMedia
import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All sensitive metadata lives in the encrypted database; disk contains opaque parts and verified plaintext only. */
data class DesktopFileRecord(
    val id: String, val peer: String, val sender: String, val ts: Long, val outgoing: Boolean,
    val body: String, val state: String, val parts: List<Boolean>, val confirmed: List<Boolean>,
    val sent: Boolean = false, val syncQueued: Boolean = false, val error: String? = null,
    val localSource: Boolean = false, val syncPublished: Boolean = false,
) {
    fun encode(): String = JSONObject().put("id", id).put("peer", peer).put("sender", sender).put("ts", ts)
        .put("outgoing", outgoing).put("body", body).put("state", state).put("parts", JSONArray(parts))
        .put("confirmed", JSONArray(confirmed)).put("sent", sent).put("syncQueued", syncQueued)
        .put("localSource", localSource).put("syncPublished", syncPublished).put("error", error ?: JSONObject.NULL).toString()
    companion object {
        fun decode(s: String): DesktopFileRecord { val j = JSONObject(s)
            fun flags(k: String) = j.getJSONArray(k).let { a -> List(a.length()) { a.getBoolean(it) } }
            return DesktopFileRecord(j.getString("id"), j.getString("peer"), j.getString("sender"), j.getLong("ts"),
                j.getBoolean("outgoing"), j.getString("body"), j.getString("state"), flags("parts"), flags("confirmed"),
                j.optBoolean("sent"), j.optBoolean("syncQueued"), if (j.isNull("error")) null else j.getString("error"), j.optBoolean("localSource"), j.optBoolean("syncPublished"))
        }
    }
}

interface DesktopFileMedia {
    val urlBase: String
    fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation)
    fun download(file: File, hash: String, size: Long, cancellation: MailboxMedia.Cancellation)
    fun confirm(hash: String, cancellation: MailboxMedia.Cancellation): Boolean
}
class DesktopFileMediaClient(private val client: MailboxMedia.Client) : DesktopFileMedia {
    override val urlBase = client.urlFor("0".repeat(64)).removeSuffix("0".repeat(64))
    override fun upload(file: File, hash: String, recipients: List<String>, cancellation: MailboxMedia.Cancellation) { client.upload(file, hash, recipients, cancellation) }
    override fun download(file: File, hash: String, size: Long, cancellation: MailboxMedia.Cancellation) { client.downloadTo(client.urlFor(hash), hash, file, size, cancellation) }
    override fun confirm(hash: String, cancellation: MailboxMedia.Cancellation) = client.confirm(client.urlFor(hash), cancellation) == MailboxMedia.Confirmation.Confirmed
}

/** Serialized durable direct-chat file work. Send is deliberately default OFF; receive is independent. */
class DesktopFiles(
    private val root: Path, private val store: DesktopMessageStore, private val media: DesktopFileMedia,
    private val ownIdentity: String, val sendEnabled: Boolean = false,
    private val quotaBytes: Long = 1024L * 1024 * 1024,
    private val notify: () -> Unit = {},
) {
    private val work = Mutex()
    private val fence = Any()
    private val active = ConcurrentHashMap<String, MailboxMedia.Cancellation>()
    init { privateDirectory(root) }
    private val staging = FileStaging {
        privateDirectory(root)
        val file = Files.createTempFile(root, ".stage-", "", modeFile)
        object : FileStage {
            var removed = false
            override fun output(): OutputStream = Files.newOutputStream(file)
            override fun input(): InputStream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)
            override fun releaseForRead(): InputStream { val reader = input(); try { close() } catch (e: Exception) { reader.close(); throw e }; return reader }
            override fun close() { if (!removed) { Files.delete(file); removed = true } }
        }
    }
    private fun hashName(id: String): String = hex(MessageDigest.getInstance("SHA-256").digest(id.toByteArray()))
    private fun folder(id: String): Path = root.resolve(hashName(id)).also(::privateDirectory)
    private fun part(id: String, index: Int) = folder(id).resolve("part-$index")
    private fun check(id: String) { if (store.fileRecord(id)?.state in listOf("CANCELLED", "DELETED")) throw InterruptedIOException("File cancelled") }
    private fun update(r: DesktopFileRecord) = synchronized(fence) { check(r.id); store.saveFile(r); notify() }
    private fun reserve(size: Long) {
        privateDirectory(root)
        val used = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum() }
        val need = Math.addExact(Math.multiplyExact(size, 4), 65536L)
        require(need <= quotaBytes - used) { "Private file quota exceeded" }
        require(Files.getFileStore(root).usableSpace >= need) { "Insufficient disk space" }
    }
    fun prepare(peer: String, source: Path, sender: String = ownIdentity): DesktopFileRecord = synchronized(fence) {
        check(sendEnabled) { "File sending is disabled" }
        require(Regex("[0-9a-f]{64}").matches(peer)) { "Direct chat files only; groups are not enabled" }
        noSymlinks(source)
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "Not a regular file" }
        val size = Files.size(source); reserve(size)
        val id = PayloadCodec.newId(); val ts = System.currentTimeMillis()
        try {
            val body = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
                FileSender(staging).prepare(input, size, id, source.fileName.toString(), Files.probeContentType(source) ?: "application/octet-stream", media.urlBase) { index, _, bytes ->
                    durableCopy(bytes, part(id, index))
                }
            }
            val r = DesktopFileRecord(id, peer, sender, ts, true, FileBody.encode(body), "QUEUED", List(body.parts.size) { false }, List(body.parts.size) { false }, localSource = true)
            store.saveFile(r)
            store.insertMessage(DesktopMessage(id, peer, sender, r.body, ts, true))
            notify(); r
        } catch (e: Exception) { deleteFolder(id); throw e }
    }
    fun accept(message: DesktopMessage): Boolean {
        if (FileManifest.fromAuthenticatedPayload(RelayPayload(message.id, message.ts, message.body)) { it == media.urlBase } == null) return false
        require(!message.conversationId.startsWith("group:")) { "Group file receive deferred" }
        store.insertMessage(message); notify(); return true
    }
    /**
     * Cancel is a persistent tombstone, fenced against every publish/state update and active network I/O.
     *
     * The tombstone alone was not enough: the folder deletion only ran inside `run()`, and the engine
     * worker skips COMPLETE/CANCELLED/DELETED records, so deleting a finished transfer left its
     * decrypted `complete` file and every `part-*` ciphertext on disk (still counted against the quota).
     * The fence is released before the delete so a large transfer cannot hold the UI thread here.
     */
    fun cancel(id: String, delete: Boolean = false) {
        synchronized(fence) {
            val r = store.fileRecord(id) ?: return
            store.saveFile(r.copy(state = if (delete) "DELETED" else "CANCELLED", error = null))
            active[id]?.cancel()
        }
        deleteFolderQuietly(id)
        notify()
    }

    /**
     * Removes folders that no live record owns (tombstoned or unknown ids), plus stale `.stage-*` files a
     * crash left in the root. Called at startup; never touches a live record's folder.
     */
    fun sweep() {
        privateDirectory(root)
        val live = store.fileRecords().filter { it.state !in listOf("CANCELLED", "DELETED") }
            .map { hashName(it.id) }.toSet()
        Files.newDirectoryStream(root).use { entries ->
            for (p in entries) {
                val name = p.fileName.toString()
                val isStage = name.startsWith(".stage-")
                val orphanFolder = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && name !in live
                if (!isStage && !orphanFolder) continue
                if (Files.isSymbolicLink(p)) { Files.deleteIfExists(p); continue }
                if (isStage) Files.deleteIfExists(p) else deleteFolderQuietlyName(name)
            }
        }
    }
    suspend fun run(id: String, send: suspend (DesktopFileRecord, List<String>) -> SendResult,
                    sentCopy: suspend (DesktopFileRecord) -> Unit = {}) = work.withLock {
        var r = store.fileRecord(id) ?: return@withLock
        if (r.state in listOf("CANCELLED", "DELETED")) { deleteFolder(id); return@withLock }
        if (r.localSource && !sendEnabled) { update(r.copy(state = "SEND_DISABLED", error = "File sending is disabled")); return@withLock }
        val m = FileManifest.fromAuthenticatedPayload(RelayPayload(r.id, r.ts, r.body)) { it == media.urlBase }
        if (m == null || r.peer.startsWith("group:")) {
            update(r.copy(state = "UNSUPPORTED", error = "Unsupported file reference")); return@withLock
        }
        val token = MailboxMedia.Cancellation(); active[id] = token
        try {
            reserve(m.body.size)
            update(r.copy(state = "TRANSFERRING", error = null)); r = store.fileRecord(id)!!
            for ((index, p) in m.body.parts.withIndex()) {
                check(id)
                if (!r.parts[index]) {
                    val path = part(id, index)
                    if (r.localSource) {
                        verifyPart(path, m, index)
                        media.upload(path.toFile(), p.sha256Hex, listOf(r.peer, ownIdentity).distinct(), token)
                    } else {
                        Files.deleteIfExists(path) // uncommitted crash residue, never a verified part
                        media.download(path.toFile(), p.sha256Hex, p.storedBytes.toLong(), token)
                        try { verifyPart(path, m, index); syncFile(path); syncDirectory(path.parent) } catch (e: Exception) { Files.deleteIfExists(path); throw e }
                    }
                    r = r.copy(parts = r.parts.toMutableList().also { it[index] = true }); update(r)
                } else verifyPart(part(id, index), m, index) // detect local loss/corruption before publication
            }
            check(id)
            val complete = folder(id).resolve("complete")
            if (!Files.exists(complete, LinkOption.NOFOLLOW_LINKS)) {
                val temp = Files.createTempFile(folder(id), ".assemble-", "", modeFile)
                try {
                    Files.newOutputStream(temp).use { out ->
                        if (!FileAssembler(staging).assemble(m, id, { i, _ -> Files.newInputStream(part(id, i), LinkOption.NOFOLLOW_LINKS) }, out)) throw IOException("File integrity verification failed")
                    }
                    syncFile(temp)
                    synchronized(fence) { check(id); publish(temp, complete) }
                } finally { Files.deleteIfExists(temp) }
            }
            verifyPlain(complete, m)
            update(r.copy(state = "STORED")); r = store.fileRecord(id)!!
            if (r.localSource && !r.sent) {
                check(id)
                when (val result = send(r, m.body.parts.map { it.sha256Hex })) {
                    is SendResult.Sent -> { r = r.copy(sent = true); update(r) }
                    is SendResult.Failed -> throw IOException(result.reason)
                }
            }
            if (r.localSource && r.sent) { sentCopy(r); r = store.fileRecord(id)!! }
            if (r.localSource && !r.syncPublished) throw IOException("SENT publication pending")
            // Sender confirms itself only after complete verified private storage; peers/other devices likewise.
            for ((i, p) in m.body.parts.withIndex()) if (!r.confirmed[i]) {
                check(id)
                if (media.confirm(p.sha256Hex, token)) { r = r.copy(confirmed = r.confirmed.toMutableList().also { it[i] = true }); update(r) }
            }
            update(r.copy(state = if (r.confirmed.all { it }) "COMPLETE" else "CONFIRM_PENDING", error = if (r.confirmed.all { it }) null else "Confirmation pending; retry"))
        } catch (e: kotlinx.coroutines.CancellationException) { token.cancel(); throw e }
        catch (e: Exception) {
            synchronized(fence) { if (store.fileRecord(id)?.state !in listOf("CANCELLED", "DELETED")) store.saveFile((store.fileRecord(id) ?: r).copy(state = "FAILED", error = "File transfer failed; retry")) }
            notify()
        } finally { active.remove(id); if (store.fileRecord(id)?.state in listOf("CANCELLED", "DELETED")) deleteFolder(id) }
    }
    private fun verifyPart(path: Path, manifest: FileManifest, index: Int) {
        noSymlinks(path)
        val key = manifest.body.keyBytes()
        try {
            val result = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { FilePartDecryptor(key, staging).decryptPart(it, manifest.body.parts[index], FilePartPlan.plan(manifest.body.size)!![index].length, OutputStream.nullOutputStream()) }
            if (result !is PartDecryptResult.Ok) throw IOException("Invalid file part")
        } finally { key.fill(0) }
    }
    private fun verifyPlain(path: Path, m: FileManifest) {
        noSymlinks(path)
        require(Files.size(path) == m.body.size && digest(path) == m.body.sha256Hex) { "Stored plaintext integrity failed" }
    }
    fun verifySaved(id: String, path: Path) = synchronized(fence) {
        check(id)
        val r = store.fileRecord(id) ?: error("Missing file")
        val m = FileManifest.fromAuthenticatedPayload(RelayPayload(id, r.ts, r.body)) { it == media.urlBase } ?: error("Bad manifest")
        verifyPlain(path, m)
    }
    fun saveAs(id: String, target: Path): Path = synchronized(fence) {
        check(id)
        val r = store.fileRecord(id) ?: error("Missing file")
        val m = FileManifest.fromAuthenticatedPayload(RelayPayload(id, r.ts, r.body)) { it == media.urlBase } ?: error("Bad manifest")
        val complete = folder(id).resolve("complete"); verifyPlain(complete, m)
        noSymlinks(target.toAbsolutePath().parent)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Destination already exists" }
        val temp = Files.createTempFile(target.toAbsolutePath().parent, ".relay-save-", "", modeFile)
        try { Files.newInputStream(complete, LinkOption.NOFOLLOW_LINKS).use { input -> Files.newOutputStream(temp).use { input.copyTo(it, 65536) } }; syncFile(temp); verifyPlain(temp, m); publish(temp, target); target }
        finally { Files.deleteIfExists(temp) }
    }
    private fun deleteFolder(id: String) {
        val dir = folder(id)
        Files.newDirectoryStream(dir).use { entries -> for (p in entries) Files.delete(p) }; Files.delete(dir)
    }
    /** Idempotent delete for paths that may already be gone (a run's finally also deletes). */
    private fun deleteFolderQuietly(id: String) = deleteFolderQuietlyName(hashName(id))
    private fun deleteFolderQuietlyName(name: String) {
        val dir = root.resolve(name)
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
        runCatching { deleteFolderContents(dir) }
    }
    private fun deleteFolderContents(dir: Path) {
        Files.newDirectoryStream(dir).use { entries -> for (p in entries) Files.deleteIfExists(p) }
        Files.deleteIfExists(dir)
    }
    companion object {
        private val modeFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        fun noSymlinks(path: Path) {
            // A link owned by the OS (uid 0) is a system alias such as macOS /var -> /private/var or
            // /tmp -> /private/tmp; failing on it would refuse an honest path on every Mac. Any other
            // link in the chain is app-controlled and still refused.
            var p: Path? = path.toAbsolutePath().normalize()
            while (p != null) {
                if (Files.isSymbolicLink(p)) {
                    val uid = runCatching { Files.getAttribute(p, "unix:uid") as Int }.getOrNull()
                    require(uid == 0) { "Symlink path refused" }
                }
                p = p.parent
            }
        }
        private fun privateDirectory(path: Path) { noSymlinks(path); if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)); Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")) }
        private fun durableCopy(input: InputStream, path: Path) { Files.createFile(path, modeFile); try { Files.newOutputStream(path).use { input.copyTo(it, 65536) }; syncFile(path); syncDirectory(path.parent) } catch (e: Exception) { Files.deleteIfExists(path); throw e } }
        private fun syncFile(path: Path) { java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) } }
        private fun syncDirectory(path: Path) { java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }
        /** Atomic no-replace publication. Unlike ATOMIC_MOVE this never replaces an existing target. */
        private fun publish(temp: Path, target: Path) { noSymlinks(target.toAbsolutePath().parent); Files.createLink(target, temp); syncDirectory(target.toAbsolutePath().parent); Files.delete(temp) }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun digest(path: Path): String { val d = MessageDigest.getInstance("SHA-256"); Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; d.update(b, 0, n) } }; return hex(d.digest()) }
        /** Why the UI/drop path must refuse an attachment, or null when sending is permitted. */
        fun attachBlockReason(peer: String?, sendEnabled: Boolean): String? = when {
            peer == null -> "Select a conversation before attaching a file"
            !sendEnabled -> "File sending is disabled (receiving remains available)"
            peer.startsWith("group:") -> "File sending to groups is disabled; use a direct chat"
            else -> null
        }
        /** Plain text bubble content: unknown TYPE: wire payloads are never shown raw. */
        fun displayText(body: String): String = if (body.startsWith("TYPE:")) "Unsupported message type" else body
        fun needsOpenWarning(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("exe", "com", "bat", "cmd", "sh", "bash", "run", "appimage", "desktop", "jar", "msi", "ps1", "py", "js")
    }
}
