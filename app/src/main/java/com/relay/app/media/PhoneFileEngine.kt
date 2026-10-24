package com.relay.app.media

import com.relay.app.files.*
import com.relay.app.transport.RelayPayload
import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID

/** Pure, blocking transfer policy. Android supplies SQLCipher, authenticated network and UI adapters. */
data class PhoneFileJob(
    val token: String, val contact: Long, val id: String, val timestamp: Long,
    val wire: String, val outgoing: Boolean, val recipients: List<String> = emptyList(),
    val transferred: Int = 0, val confirmed: Int = 0, val ready: Boolean = false,
    val queued: Boolean = false, val cancelled: Boolean = false, val error: String? = null,
)
interface PhoneFileStore {
    fun all(): List<PhoneFileJob>
    fun get(token: String): PhoneFileJob?
    /** Initial manifest and bubble must commit atomically; existing IDs may never change manifest. */
    fun insert(job: PhoneFileJob): Boolean
    /** Must not replace a cancelled/deleted job. */
    fun update(job: PhoneFileJob)
    /** Persist outbox + media references + queued flag atomically, guarded by source bubble existence. */
    fun enqueue(job: PhoneFileJob)
    /** Sender must retain its recipient until SENT grants are durably published. */
    fun confirmationAllowed(job: PhoneFileJob): Boolean
}
interface PhoneFileNetwork {
    fun upload(file: File, part: FilePart, recipients: List<String>)
    fun download(url: String, part: FilePart, target: File)
    fun confirm(url: String): Boolean
    fun cancel() {}
}

class PrivateFileSpace(val root: File, private val quota: Long = 512L * 1024 * 1024) : FileStaging {
    companion object {
        private val FILE_MODE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        private val DIR_MODE = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        const val RESERVE = 157283294L
    }
    init {
        if (!root.exists()) Files.createDirectory(root.toPath(), DIR_MODE)
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(root.toPath())) { "Unsafe private directory" }
        Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------"))
    }
    fun file(token: String, suffix: String): File {
        require(Regex("[0-9a-f-]{36}").matches(token))
        require(Regex("source|verified|part[0-4]|download[0-4]|publish").matches(suffix))
        return File(root, "$token.$suffix").also { require(!Files.isSymbolicLink(it.toPath())) { "Unsafe transfer path" } }
    }
    fun reserve(bytes: Long) {
        require(bytes >= 0)
        val used = root.listFiles()?.sumOf {
            require(Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)) { "Unsafe private storage entry" }
            it.length()
        } ?: throw IOException("Cannot inspect private storage")
        if (used > quota - bytes || root.usableSpace < bytes) throw IOException("Not enough private file storage")
    }
    fun output(file: File): OutputStream = Files.newOutputStream(
        Files.createFile(file.toPath(), FILE_MODE), StandardOpenOption.WRITE,
    )
    fun sync(file: File) { RandomAccessFile(file, "rw").use { it.fd.sync() } }
    fun syncDirectory() { java.nio.channels.FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    fun delete(file: File) { Files.deleteIfExists(file.toPath()) }
    fun remove(token: String) {
        listOf("source", "verified", "publish").plus((0..4).flatMap { listOf("part$it", "download$it") }).forEach { delete(file(token, it)) }
    }
    fun cleanup(live: Set<String>) {
        root.listFiles()?.forEach { f ->
            require(!Files.isSymbolicLink(f.toPath())) { "Unsafe orphan storage entry" }
            if (f.name.startsWith("stage-") || f.name.substringBefore('.') !in live) delete(f)
        }
    }
    override fun create(): FileStage {
        val f = Files.createTempFile(root.toPath(), "stage-", ".tmp", FILE_MODE).toFile()
        return object : FileStage {
            var removed = false
            override fun output(): OutputStream = f.outputStream()
            override fun input(): InputStream = f.inputStream()
            override fun releaseForRead(): InputStream {
                val input = input()
                try { close() } catch (e: Exception) { input.close(); throw e }
                return input
            }
            override fun close() { if (!removed) { Files.delete(f.toPath()); removed = true } }
        }
    }
}

class PhoneFileEngine(
    private val space: PrivateFileSpace, private val store: PhoneFileStore,
    private val network: PhoneFileNetwork, private val ownsBase: (String) -> Boolean,
    private val changed: () -> Unit = {},
) {
    companion object { const val MAX_BYTES = 104857600L }
    @Volatile private var activeToken: String? = null
    private fun current(job: PhoneFileJob): PhoneFileJob {
        // Every state change and publication re-reads the saved row: a cancelled or deleted job must never advance.
        val saved = store.get(job.token)
        if (saved == null || saved.cancelled) throw java.io.InterruptedIOException("File transfer cancelled")
        return saved
    }
    private fun copy(input: InputStream, output: OutputStream, limit: Long, check: () -> Unit = {}): Long {
        val buffer = ByteArray(65536)
        var size = 0L
        while (true) {
            check()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - size + 1).toInt())
            if (n < 0) break
            if (n == 0) continue
            size += n
            if (size > limit) throw IOException("File exceeds 100 MiB")
            output.write(buffer, 0, n)
        }
        return size
    }
    /** Provider size is only a hint; SAF streams need not be seekable and are read exactly once. */
    @Synchronized fun prepare(source: InputStream, contact: Long, id: String, timestamp: Long,
        name: String, mime: String, base: String, recipients: List<String>, caption: String = ""): PhoneFileJob = source.use { ownedSource ->
        require(ownsBase(base))
        require(recipients.isNotEmpty() && recipients.all { Regex("[0-9a-f]{64}").matches(it) })
        space.reserve(MAX_BYTES * 2 + PrivateFileSpace.RESERVE)
        val token = UUID.randomUUID().toString()
        val original = space.file(token, "source")
        try {
            val size = space.output(original).use { copy(ownedSource, it, MAX_BYTES) }
            space.sync(original)
            val body = original.inputStream().use { input ->
                FileSender(space).prepare(input, size, id, name, mime, base) { index, _, encrypted ->
                    val f = space.file(token, "part$index")
                    space.output(f).use { copy(encrypted, it, 26214400) }
                    space.sync(f)
                }
            }.copy(caption = caption)
            space.syncDirectory()
            val wire = FileBody.encode(body)
            val job = PhoneFileJob(token, contact, id, timestamp, wire, true, recipients.distinct())
            check(store.insert(job)) { "File id already exists" }
            changed()
            return@use job
        } catch (e: Exception) { try { space.remove(token) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }; throw e }
    }
    fun receive(payload: RelayPayload, contact: Long, outgoing: Boolean = false): PhoneFileJob? {
        val manifest = FileManifest.fromAuthenticatedPayload(payload, ownsBase) ?: return null
        val existing = store.all().firstOrNull { it.contact == contact && it.id == payload.id }
        if (existing != null) { require(existing.wire == payload.body) { "File manifest changed" }; return existing }
        space.reserve(manifest.body.size * 2 + PrivateFileSpace.RESERVE)
        val job = PhoneFileJob(UUID.randomUUID().toString(), contact, payload.id, payload.ts, payload.body, outgoing, queued = outgoing)
        check(store.insert(job))
        changed()
        return job
    }
    @Synchronized fun cleanup() {
        space.cleanup(store.all().filterNot { it.cancelled }.map { it.token }.toSet())
    }
    @Synchronized fun tick() {
        cleanup()
        for (saved in store.all()) {
            if (saved.cancelled || saved.error != null) continue
            var job = saved
            activeToken = job.token
            try {
                val manifest = requireNotNull(FileManifest.fromAuthenticatedPayload(RelayPayload(id = job.id, ts = job.timestamp, body = job.wire), ownsBase))
                val body = manifest.body
                // Prepared locally has recipient grants; a SENT copy is outgoing but is still a download.
                val sending = job.recipients.isNotEmpty()
                for ((index, part) in body.parts.withIndex()) {
                    current(job)
                    val f = space.file(job.token, "part$index")
                    if (job.transferred and (1 shl index) != 0) {
                        if (sending || job.ready || (f.exists() && f.length() == part.storedBytes.toLong() && hash(f) == part.sha256Hex)) continue
                        job = job.copy(transferred = job.transferred and (1 shl index).inv())
                        store.update(job)
                    }
                    if (sending) {
                        require(hash(f) == part.sha256Hex && f.length() == part.storedBytes.toLong()) { "Stored part changed" }
                        network.upload(f, part, job.recipients)
                    } else {
                        // Crash after fsync but before SQL commit: verify/reuse the completed ciphertext.
                        if (!f.exists() || hash(f) != part.sha256Hex || f.length() != part.storedBytes.toLong()) {
                            space.delete(f)
                            val temp = space.file(job.token, "download$index")
                            space.delete(temp)
                            space.reserve(part.storedBytes.toLong() + PrivateFileSpace.RESERVE)
                            network.download(FileBody.partUrl(body.urlBase, part.sha256Hex), part, temp)
                            current(job)
                            require(temp.length() == part.storedBytes.toLong() && hash(temp) == part.sha256Hex)
                            space.sync(temp)
                            Files.move(temp.toPath(), f.toPath())
                            space.syncDirectory()
                        }
                    }
                    current(job)
                    job = job.copy(transferred = job.transferred or (1 shl index))
                    store.update(job); changed()
                }
                if (!job.ready) {
                    current(job)
                    val final = space.file(job.token, if (sending) "source" else "verified")
                    if (sending) {
                        require(final.length() == body.size && hash(final) == body.sha256Hex) { "Original file changed" }
                    } else {
                        space.reserve(body.size + PrivateFileSpace.RESERVE)
                        val pending = space.file(job.token, "publish")
                        space.delete(pending)
                        val ok = space.output(pending).use { sink -> FileAssembler(space).assemble(manifest, job.id,
                            { index, _ -> current(job); space.file(job.token, "part$index").inputStream() }, sink) }
                        require(ok) { "File verification failed" }
                        current(job); space.sync(pending)
                        space.delete(final); Files.move(pending.toPath(), final.toPath())
                        space.syncDirectory()
                    }
                    current(job)
                    job = job.copy(ready = true)
                    store.update(job); changed()
                }
                if (sending && !job.queued) { current(job); store.enqueue(job); job = current(job) }
                if (!store.confirmationAllowed(job)) continue
                for ((index, part) in body.parts.withIndex()) {
                    current(job)
                    if (job.confirmed and (1 shl index) == 0 && network.confirm(FileBody.partUrl(body.urlBase, part.sha256Hex))) {
                        current(job)
                        job = job.copy(confirmed = job.confirmed or (1 shl index))
                        store.update(job)
                    }
                }
            } catch (e: Exception) {
                val latest = store.get(job.token)
                if (latest != null && !latest.cancelled) store.update(latest.copy(error = "Transfer failed; retry"))
                changed()
            } finally { activeToken = null }
        }
    }
    fun cancel(token: String) {
        store.get(token)?.let { store.update(it.copy(cancelled = true, error = null)) }
        if (activeToken == token) network.cancel()
        // A running network operation may finish, but current() fences every later state/publication.
        synchronized(this) { space.remove(token) }
        changed()
    }
    @Synchronized fun retry(token: String) {
        store.get(token)?.takeUnless { it.cancelled }?.let { store.update(it.copy(error = null)) }
        changed()
    }
    @Synchronized fun verifiedSource(token: String): File {
        val job = requireNotNull(store.get(token)).also { require(it.ready && !it.cancelled) }
        val body = requireNotNull(FileManifest.fromAuthenticatedPayload(RelayPayload(id = job.id, ts = job.timestamp, body = job.wire), ownsBase)).body
        val f = space.file(token, if (job.recipients.isNotEmpty()) "source" else "verified")
        require(f.length() == body.size && hash(f) == body.sha256Hex) { "Private file verification failed" }
        return f
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if(n < 0) break; digest.update(b,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
