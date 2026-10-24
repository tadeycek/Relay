package com.relay.app.files

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.security.*

/** Conscrypt is supplied by the provider verification init script, not a production dependency. */
class FileProviderRegressionTest {
    @Test fun fullPartWorksWithBufferingProvider() {
        val cls = try { Class.forName("org.conscrypt.Conscrypt") } catch (e: ClassNotFoundException) { null }
        org.junit.Assume.assumeNotNull(cls)
        val provider = cls!!.getMethod("newProvider").invoke(null) as Provider
        Security.insertProviderAt(provider, 1)
        try {
            val n = FilePartPlan.PART_PLAIN_BYTES
            val root = File(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
            val blob = File.createTempFile("wp09-provider-", ".blob", root)
            try {
                val source = object : InputStream() {
                    var left = n
                    override fun read(): Int = if (left-- > 0) 90 else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (left <= 0) return -1
                        val count = minOf(left, len); java.util.Arrays.fill(b, off, off + count, 90.toByte()); left -= count; return count
                    }
                }
                val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
                val directPool = java.lang.management.ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean::class.java).first { it.name == "direct" }
                val directBefore = directPool.memoryUsed
                var start = bean.getThreadAllocatedBytes(Thread.currentThread().id)
                val part = blob.outputStream().use { FilePartEncryptor(ByteArray(32)).encryptPart(source, n, it) }
                val encryptAllocated = bean.getThreadAllocatedBytes(Thread.currentThread().id) - start
                println("PROVIDER Conscrypt encrypt threadAllocated=$encryptAllocated")
                assertTrue("provider allocation regression: $encryptAllocated", encryptAllocated < 3L * n + 2 * 1024 * 1024)
                var count = 0L
                val sink = object : OutputStream() {
                    override fun write(b: Int) { assertEquals(90, b and 255); count++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) assertEquals(90.toByte(), b[i]); count += len }
                }
                start = bean.getThreadAllocatedBytes(Thread.currentThread().id)
                val result = blob.inputStream().use { FilePartDecryptor(ByteArray(32)).decryptPart(it, FilePart(part.sha256Hex, part.storedBytes), n, sink) }
                val decryptAllocated = bean.getThreadAllocatedBytes(Thread.currentThread().id) - start
                println("PROVIDER Conscrypt decrypt threadAllocated=$decryptAllocated; directPool before=$directBefore after=${directPool.memoryUsed}; neither is peak live/native")
                assertTrue("provider allocation regression: $decryptAllocated", decryptAllocated < 3L * n + 2 * 1024 * 1024)
                assertEquals(PartDecryptResult.Ok(n), result); assertEquals(n.toLong(), count)
                // A genuine tag failure, with the manifest hash recomputed so the hash guard cannot mask it.
                RandomAccessFile(blob, "rw").use { f -> f.seek(f.length() - 1); val tag = f.read(); f.seek(f.length() - 1); f.write(tag xor 1) }
                val md = MessageDigest.getInstance("SHA-256")
                blob.inputStream().use { input -> val buf = ByteArray(65536); while (true) { val got = input.read(buf); if (got < 0) break; md.update(buf, 0, got) } }
                var published = 0L
                val rejectSink = object : OutputStream() {
                    override fun write(b: Int) { published++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { published += len }
                }
                val bad = blob.inputStream().use { FilePartDecryptor(ByteArray(32)).decryptPart(it, FilePart(fileHex(md.digest()), part.storedBytes), n, rejectSink) }
                assertEquals(PartDecryptResult.Failed(PartFailure.AUTH_FAILED), bad); assertEquals(0L, published)
                RandomAccessFile(blob, "rw").use { it.setLength(it.length() - 1) }
                val short = blob.inputStream().use { FilePartDecryptor(ByteArray(32)).decryptPart(it, FilePart(part.sha256Hex, part.storedBytes), n, rejectSink) }
                assertEquals(PartDecryptResult.Failed(PartFailure.BAD_SIZE), short); assertEquals(0L, published)
            } finally { check(blob.delete()) }
        } finally { Security.removeProvider(provider.name) }
    }
}
