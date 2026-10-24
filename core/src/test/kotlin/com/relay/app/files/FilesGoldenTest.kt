package com.relay.app.files

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Pins the TYPE:FILE wire format, the part plan, name sanitising, and the stored-blob bytes (fixed key, fixed IV)
 * in `src/test/resources/golden/files-codecs.golden`. Record with `RELAY_GOLDEN_RECORD=1`; otherwise only compared.
 * A desktop or phone implementation can check itself against these lines.
 */
class FilesGoldenTest {
    private val lines = ArrayList<String>()
    private fun esc(s: String) = buildString { for (c in s) if (c.code in 0x20..0x7e && c != '\\') append(c) else append("\\u%04x".format(c.code)) }
    private fun rec(name: String, v: Any?) { lines += "$name\t${esc(v.toString())}" }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val base = "https://relay.example/v1/media/"
    private val ours: (String) -> Boolean = { it == base }
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 1).toByte() })
    private fun sha(i: Int) = ("%02x".format(i * 17 + 1)).repeat(32)
    private fun parts(size: Long) = FilePartPlan.plan(size)!!.map { FilePart(sha(it.index), it.storedBytes) }
    private fun fb(size: Long, name: String = "report.pdf", caption: String = "") = FileBody(name, "application/pdf", size, key, parts(size), base, "ab".repeat(32), "test-file-id", caption)

    @Test fun golden() {
        rec("const.PART_PLAIN_BYTES", FilePartPlan.PART_PLAIN_BYTES); rec("const.MAX_PARTS", FilePartPlan.MAX_PARTS)
        rec("const.MAX_SIZE", FilePartPlan.MAX_SIZE); rec("const.SERVER_BLOB_CAP", FilePartPlan.SERVER_BLOB_CAP)

        for (size in listOf(1L, 2L, FilePartPlan.PART_PLAIN_BYTES - 1L, FilePartPlan.PART_PLAIN_BYTES.toLong(), FilePartPlan.PART_PLAIN_BYTES + 1L,
            60L * 1024 * 1024, 4L * FilePartPlan.PART_PLAIN_BYTES, 4L * FilePartPlan.PART_PLAIN_BYTES + 1, FilePartPlan.MAX_SIZE, FilePartPlan.MAX_SIZE + 1, 0L))
            rec("plan.$size", FilePartPlan.plan(size)?.joinToString(";") { "${it.offset}+${it.length}=>${it.storedBytes}" })
        for (n in listOf(1, 65_504, 65_505, 65_533)) rec("storedSize.$n", FilePartPlan.storedSize(n))

        rec("encode.empty", FileBody.encode(fb(0).copy(sha256Hex = fileHex(MessageDigest.getInstance("SHA-256").digest(ByteArray(0))))))
        rec("encode.one", FileBody.encode(fb(1)))
        rec("encode.two", FileBody.encode(fb(30_000_000, caption = "hello ✓")))
        rec("encode.max", FileBody.encode(fb(FilePartPlan.MAX_SIZE, name = "Ünï 文件.tar.gz")))
        rec("encode.sanitised", FileBody.encode(fb(5, name = "../../.ssh/id_rsa\u202E.sh").copy(mime = "Text/HTML; x")))

        val good = FileBody.encode(fb(30_000_000))
        fun hostile(name: String, wire: String) = rec("parse.$name", FileBody.parse(wire, ours).let { if (it is FileBodyResult.Rejected) "Rejected(${it.reason})" else "Ok" })
        hostile("good", good)
        hostile("empty", ""); hostile("text", "hello")
        hostile("foreignUrl", good.replace(base, "https://evil.example/v1/media/"))
        hostile("trailingPipe", "$good|"); hostile("trailingGarbage", "$good|x")
        hostile("unknownKey", "$good|Z:1"); hostile("dupKey", "$good|SIZE:1")
        hostile("upperHex", good.replace(sha(0), sha(0).replace("01", "0A")))
        hostile("v2", good.replace("V:1", "V:2"))
        hostile("size0", good.replace("SIZE:30000000", "SIZE:0"))
        hostile("sizeOver", good.replace("SIZE:30000000", "SIZE:104857601"))
        hostile("parts6", good.replace("PARTS:2", "PARTS:6"))
        hostile("parts1", good.replace("PARTS:2", "PARTS:1"))
        hostile("wrongBlobSize", good.replace(Regex("P1:([0-9a-f]{64}),\\d+"), "P1:\$1,26214465"))
        hostile("dupPart", good.replace(sha(1), sha(0)))
        hostile("name256", good.replace("NAME64:cmVwb3J0LnBkZg==", "NAME64:" + Base64.getEncoder().encodeToString(ByteArray(256) { 'a'.code.toByte() })))
        hostile("shortKey", good.replace(key, Base64.getEncoder().encodeToString(ByteArray(16))))
        hostile("tooLong", good + "|CAP64:" + "A".repeat(20000))
        val parsed = (FileBody.parse(FileBody.encode(fb(5, name = "../../.ssh/id_rsa\u202E.sh").copy(mime = "Text/HTML; x")), ours) as FileBodyResult.Ok).body
        rec("parse.sanitised.name", parsed.name); rec("parse.sanitised.mime", parsed.mime)

        for ((i, n) in listOf("a/b.txt", ".bashrc", "..", "", "CON", "a\u202Eb.exe", "x".repeat(300) + ".pdf", "😀".repeat(100) + ".png", "tab\tname").withIndex())
            rec("safeName.$i", FileSafety.safeName(n).let { if (it.length > 40) "len=${it.toByteArray().size};tail=${it.takeLast(8)}" else it })
        for (m in listOf("image/png", "IMAGE/PNG", "x", "a/b;c", "")) rec("safeMime.[$m]", FileSafety.safeMime(m))
        for (n in listOf("a.sh", "a.AppImage", "a.txt", "x.exe.txt")) rec("exec.$n", FileSafety.isExecutableLike(n))

        // Stored-blob bytes with a fixed key and IV: the interop vector for other implementations.
        val k = ByteArray(32) { (it * 7 + 1).toByte() }
        for (n in listOf(0, 1, 100, 70_000)) {
            val plain = ByteArray(n) { (it * 31 + 7).toByte() }
            val enc = FilePartEncryptor(k, ivSource = { iv -> for (j in iv.indices) iv[j] = (0xA0 + j).toByte() })
            val out = ByteArrayOutputStream()
            val sp = enc.encryptPart(ByteArrayInputStream(plain), n, out)
            val blob = out.toByteArray()
            rec("blob.$n.plainSha256", hex(MessageDigest.getInstance("SHA-256").digest(plain)))
            rec("blob.$n.stored", "${sp.storedBytes},${sp.sha256Hex}")
            rec("blob.$n.head", hex(blob.copyOfRange(0, 64.coerceAtMost(blob.size))))
            rec("blob.$n.tail", hex(blob.copyOfRange(blob.size - 20, blob.size)))
            val back = ByteArrayOutputStream()
            assertEquals(PartDecryptResult.Ok(n), FilePartDecryptor(k).decryptPart(ByteArrayInputStream(blob), FilePart(sp.sha256Hex, sp.storedBytes), n, back))
            assertEquals(hex(plain), hex(back.toByteArray()))
        }

        val text = lines.joinToString("\n") + "\n"
        val golden = File("src/test/resources/golden/files-codecs.golden")
        if (System.getenv("RELAY_GOLDEN_RECORD") == "1") { golden.writeText(text); println("GOLDEN RECORDED: ${lines.size} entries -> ${golden.absolutePath}") }
        assertEquals(golden.readText(), text)
    }
}
