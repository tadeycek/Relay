package com.relay.app.files

import java.io.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Platform must supply an app-private directory with quota/cancellation policy; never a public Downloads path.
 * Stages are unlinked BEFORE final publication. Android/Linux/macOS permit reading an already-open unlinked file.
 * Cleanup failures are exceptions, never successful publication. An injected implementation must preserve this contract.
 */
fun interface FileStaging {
    fun create(): FileStage

    companion object {
        val DEFAULT = FileStaging {
            val root = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
            val path = Files.createTempFile(root.toPath(), "relay-file-", ".stage", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            DiskFileStage(path.toFile())
        }
    }
}

interface FileStage : Closeable {
    fun output(): OutputStream
    fun input(): InputStream
    /** Return an open reader after removing the directory entry; failure must return no reader. */
    fun releaseForRead(): InputStream
}

private class DiskFileStage(private val file: File) : FileStage {
    private var removed = false
    override fun output(): OutputStream = file.outputStream()
    override fun input(): InputStream = file.inputStream()
    override fun releaseForRead(): InputStream {
        val reader = input()
        try { close() } catch (e: Exception) { reader.close(); throw e }
        return reader
    }
    override fun close() {
        if (!removed) {
            if (!file.delete()) throw IOException("Private file staging cleanup failed")
            removed = true
        }
    }
}

internal fun fileHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
internal fun copyFileBytes(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(64 * 1024)
    try {
        while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) output.write(buffer, 0, n) }
    } finally { buffer.fill(0) }
}
