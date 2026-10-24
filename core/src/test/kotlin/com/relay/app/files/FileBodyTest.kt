package com.relay.app.files

import com.relay.app.messaging.ReplyBody
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class FileBodyTest {
    private val B64 = Base64.getEncoder()
    private val BASE = "https://relay.example/v1/media/"
    private val ours: (String) -> Boolean = { it == BASE }
    private val key = B64.encodeToString(ByteArray(32) { (it * 7 + 1).toByte() })
    private fun sha(i: Int) = ("%02x".format(i + 1)).repeat(32)

    private fun goodParts(size: Long) = FilePartPlan.plan(size)!!.map { FilePart(sha(it.index), it.storedBytes) }
    private fun body(size: Long = 30_000_000, name: String = "report.pdf", caption: String = "") =
        FileBody(name, "application/pdf", size, key, goodParts(size), BASE, "ab".repeat(32), "test-file-id", caption)

    /** Hand-assembles a wire body so hostile variants can be produced. */
    private fun raw(
        name: String = B64.encodeToString("report.pdf".toByteArray()), mime: String = "application/pdf", size: Long = 30_000_000,
        k: String = key, parts: List<String>? = null, partsN: Int? = null, base: String = BASE, extra: List<String> = emptyList(), v: String = "1",
    ): String {
        val ps = parts ?: goodParts(size).map { "${it.sha256Hex},${it.storedBytes}" }
        return (listOf("TYPE:FILE", "V:$v", "NAME64:$name", "MIME:$mime", "SIZE:$size", "SHA256:${"ab".repeat(32)}", "ID64:dGVzdC1maWxlLWlk", "KEY:$k", "PARTS:${partsN ?: ps.size}") +
            ps.mapIndexed { i, p -> "P${i + 1}:$p" } + listOf("URLBASE:$base") + extra).joinToString("|")
    }

    private fun parse(s: String) = FileBody.parse(s, ours)
    private fun rejected(s: String, why: FileBodyReject) {
        val r = parse(s); assertTrue("expected $why but got $r", r is FileBodyResult.Rejected)
        assertEquals(why, (r as FileBodyResult.Rejected).reason)
    }
    private fun ok(s: String) = (parse(s) as FileBodyResult.Ok).body

    @Test fun manifestWithoutFullPlaintextDigestIsRejected() {
        assertTrue("mandatory full plaintext digest missing", parse(raw().replace("|SHA256:" + "ab".repeat(32), "")) is FileBodyResult.Rejected)
    }

    @Test fun wireExampleIsExact() {
        val b = body()
        val wire = FileBody.encode(b)
        val p = FilePartPlan.plan(30_000_000)!!
        val expected = "TYPE:FILE|V:1|NAME64:cmVwb3J0LnBkZg==|MIME:application/pdf|SIZE:30000000|SHA256:${"ab".repeat(32)}|ID64:dGVzdC1maWxlLWlk|KEY:$key|PARTS:2|" +
            "P1:${sha(0)},${p[0].storedBytes}|P2:${sha(1)},${p[1].storedBytes}|URLBASE:$BASE"
        assertEquals(expected, wire)
        assertEquals(b, ok(wire))
    }

    @Test fun roundTripWithCaptionAndUnicodeName() {
        val b = body(name = "Ünïcode 文件.txt", caption = "see this ✓")
        assertEquals(b, ok(FileBody.encode(b)))
    }

    @Test fun roundTripWithReplyCaption() {
        val reply = ReplyBody.format(ReplyBody.Reply("Ann", "quoted", "ref1", "answer"))
        val b = body(caption = reply)
        assertEquals(reply, ok(FileBody.encode(b)).caption)
    }

    @Test fun roundTripEveryPartCountAndSize() {
        for (size in listOf(0L, 1L, FilePartPlan.PART_PLAIN_BYTES.toLong(), FilePartPlan.PART_PLAIN_BYTES + 1L, FilePartPlan.MAX_SIZE)) {
            val b = body(size); assertEquals(b, ok(FileBody.encode(b)))
        }
    }

    @Test fun encodeSanitisesNameAndMime() {
        val e = FileBody.encode(body(name = "../../x\u202E.sh").copy(mime = "TEXT/Evil;x"))
        val b = ok(e); assertEquals("_....x.sh", b.name); assertEquals("application/octet-stream", b.mime)
    }

    @Test fun encodeThrowsOnStructurallyInvalidBody() {
        assertThrows(IllegalArgumentException::class.java) { FileBody.encode(body().copy(size = 0)) }
        assertThrows(IllegalArgumentException::class.java) { FileBody.encode(body().copy(parts = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { FileBody.encode(body().copy(keyBase64 = "AAAA")) }
    }

    @Test fun wholeBodyStaysWithin16KiB() {
        val big = body(FilePartPlan.MAX_SIZE, name = "n".repeat(250), caption = "c".repeat(200))
        assertTrue(FileBody.encode(big).toByteArray().size < 16 * 1024)
        rejected(raw(extra = listOf("CAP64:" + "A".repeat(16 * 1024))), FileBodyReject.TOO_LONG)
    }

    @Test fun notAFileBody() {
        assertEquals(FileBodyResult.Rejected(FileBodyReject.NOT_FILE), parse("hello"))
        assertEquals(FileBodyResult.Rejected(FileBodyReject.NOT_FILE), parse(""))
        assertEquals(FileBodyResult.Rejected(FileBodyReject.NOT_FILE), parse("TYPE:MEDIA|URL:x"))
        assertTrue(FileBody.isFile("TYPE:FILE|V:1")); assertFalse(FileBody.isFile("hello"))
    }

    @Test fun nameRules() {
        fun n(s: String) = B64.encodeToString(s.toByteArray())
        assertEquals("_....etcpasswd", ok(raw(name = n("../../etc/passwd"))).name)
        assertEquals("_.bashrc", ok(raw(name = n(".bashrc"))).name)
        assertEquals("file", ok(raw(name = n(""))).name)
        assertEquals("file", ok(raw(name = n("///"))).name)
        assertEquals("ab", ok(raw(name = n("a\u0000b"))).name)
        assertEquals(255, ok(raw(name = n("x".repeat(255)))).name.toByteArray().size)
        rejected(raw(name = n("x".repeat(256))), FileBodyReject.BAD_NAME)
        rejected(raw(name = n("é".repeat(128))), FileBodyReject.BAD_NAME) // 256 bytes
        rejected(raw(name = B64.encodeToString(byteArrayOf(0xC3.toByte(), 0x28))), FileBodyReject.BAD_NAME) // malformed utf-8
        rejected(raw(name = "!!!!"), FileBodyReject.BAD_NAME)
        rejected(raw(name = "YQ"), FileBodyReject.BAD_NAME) // non-canonical (unpadded) base64
        rejected(raw(name = "YR=="), FileBodyReject.BAD_NAME) // non-canonical trailing bits
    }

    @Test fun mimeBecomesOctetStreamWhenBad() {
        assertEquals("application/octet-stream", ok(raw(mime = "")).mime)
        assertEquals("application/octet-stream", ok(raw(mime = "nonsense")).mime)
        assertEquals("application/octet-stream", ok(raw(mime = "a/" + "b".repeat(100))).mime)
        assertEquals("image/png", ok(raw(mime = "Image/PNG")).mime)
    }

    @Test fun sizeBounds() {
        assertEquals(0L, ok(raw(size = 0)).size)
        rejected(raw(size = -5, parts = listOf("${sha(0)},29")), FileBodyReject.BAD_SIZE)
        rejected(raw(size = FilePartPlan.MAX_SIZE + 1, parts = (0..4).map { "${sha(it)},${FilePartPlan.storedSize(FilePartPlan.PART_PLAIN_BYTES)}" }), FileBodyReject.BAD_SIZE)
        rejected(raw().replace("SIZE:30000000", "SIZE:3e7"), FileBodyReject.BAD_SIZE)
        rejected(raw().replace("SIZE:30000000", "SIZE:+30000000"), FileBodyReject.BAD_SIZE)
        rejected(raw().replace("SIZE:30000000", "SIZE:030000000"), FileBodyReject.BAD_SIZE)
        rejected(raw().replace("SIZE:30000000", "SIZE:99999999999999999999"), FileBodyReject.BAD_SIZE)
        assertEquals(1L, ok(raw(size = 1)).size)
        assertEquals(FilePartPlan.MAX_SIZE, ok(raw(size = FilePartPlan.MAX_SIZE)).size)
    }

    @Test fun partCounts() {
        val good = goodParts(30_000_000).map { "${it.sha256Hex},${it.storedBytes}" }
        rejected(raw(parts = good.take(1)), FileBodyReject.PART_COUNT) // too few for SIZE
        rejected(raw(parts = good + "${sha(2)},${good.size}"), FileBodyReject.PART_COUNT) // too many for SIZE
        rejected(raw(parts = good, partsN = 0), FileBodyReject.BAD_PARTS)
        rejected(raw(parts = good, partsN = 6), FileBodyReject.BAD_PARTS)
        rejected(raw(parts = good, partsN = 3), FileBodyReject.PART_COUNT)
        rejected(raw(parts = good, partsN = 1), FileBodyReject.PART_COUNT) // P2 present beyond PARTS
        rejected(raw(parts = good).replace("PARTS:2", "PARTS:02"), FileBodyReject.BAD_PARTS)
        rejected(raw(parts = good).replace("|P2:", "|P3:"), FileBodyReject.PART_COUNT)
        // five parts max, all valid
        val max = raw(size = FilePartPlan.MAX_SIZE)
        assertEquals(5, ok(max).parts.size)
    }

    @Test fun blobSizeRules() {
        val full = FilePartPlan.storedSize(FilePartPlan.PART_PLAIN_BYTES)
        val last = FilePartPlan.storedSize(30_000_000 - FilePartPlan.PART_PLAIN_BYTES)
        rejected(raw(parts = listOf("${sha(0)},${FilePartPlan.SERVER_BLOB_CAP + 1}", "${sha(1)},$last")), FileBodyReject.BLOB_TOO_LARGE)
        rejected(raw(parts = listOf("${sha(0)},$full", "${sha(1)},${last + 1}")), FileBodyReject.TOTAL_MISMATCH)
        rejected(raw(parts = listOf("${sha(0)},${full - 1}", "${sha(1)},$last")), FileBodyReject.BLOB_SIZE_INCONSISTENT)
        // each part individually valid, but the sum differs from SIZE
        rejected(raw(parts = listOf("${sha(0)},$full", "${sha(1)},${FilePartPlan.storedSize(5)}")), FileBodyReject.TOTAL_MISMATCH)
        // a non-final part that is short
        rejected(raw(parts = listOf("${sha(0)},${FilePartPlan.storedSize(100)}", "${sha(1)},$last")), FileBodyReject.BLOB_SIZE_INCONSISTENT)
        rejected(raw(parts = listOf("${sha(0)},0", "${sha(1)},$last")), FileBodyReject.BAD_PART)
        rejected(raw(parts = listOf("${sha(0)},-1", "${sha(1)},$last")), FileBodyReject.BAD_PART)
        rejected(raw(parts = listOf("${sha(0)},99999999999", "${sha(1)},$last")), FileBodyReject.BAD_PART)
    }

    @Test fun partHashRules() {
        val last = FilePartPlan.storedSize(30_000_000 - FilePartPlan.PART_PLAIN_BYTES)
        val full = FilePartPlan.storedSize(FilePartPlan.PART_PLAIN_BYTES)
        rejected(raw(parts = listOf("${sha(0)},$full", "${sha(0)},$last")), FileBodyReject.DUPLICATE_PART)
        rejected(raw(parts = listOf("${sha(10).uppercase()},$full", "${sha(1)},$last")), FileBodyReject.BAD_PART)
        rejected(raw(parts = listOf("${"A".repeat(64)},$full", "${sha(1)},$last")), FileBodyReject.BAD_PART)
        rejected(raw(parts = listOf("${sha(0).drop(1)},$full", "${sha(1)},$last")), FileBodyReject.BAD_PART)
        rejected(raw(parts = listOf("${"g".repeat(64)},$full", "${sha(1)},$last")), FileBodyReject.BAD_PART)
        rejected(raw(parts = listOf("${sha(0)}$full", "${sha(1)},$last")), FileBodyReject.BAD_PART)
        rejected(raw(parts = listOf("${sha(0)},$full,1", "${sha(1)},$last")), FileBodyReject.BAD_PART)
    }

    @Test fun urlBaseRules() {
        rejected(raw(base = "https://evil.example/v1/media/"), FileBodyReject.FOREIGN_URL)
        rejected(raw(base = "http://relay.example/v1/media/"), FileBodyReject.FOREIGN_URL)
        rejected(raw(base = "https://relay.example/v1/media/@x"), FileBodyReject.FOREIGN_URL)
        assertEquals(FileBodyReject.FOREIGN_URL, ((FileBody.parse(raw(), { false })) as FileBodyResult.Rejected).reason)
        // predicate sees only structurally acceptable https bases ending in '/'
        var seen = false
        FileBody.parse(raw(base = "ftp://x/"), { seen = true; true }); assertFalse(seen)
        rejected(raw(base = "https://relay.example/v1/media"), FileBodyReject.FOREIGN_URL) // no trailing slash (predicate also rejects)
        val always = FileBody.parse(raw(base = "https://relay.example/v1/media"), { true })
        assertEquals(FileBodyReject.FOREIGN_URL, (always as FileBodyResult.Rejected).reason)
    }

    @Test fun ourUrlPredicateAdaptsMailboxStyleCheck() {
        val isOurs: (String) -> Boolean = { it.startsWith(BASE) && Regex("[0-9a-f]{64}").matches(it.removePrefix(BASE)) }
        val p = FileBody.urlBasePredicate(isOurs)
        assertTrue(p(BASE)); assertFalse(p("https://evil.example/v1/media/")); assertFalse(p(BASE + "x")); assertFalse(p(""))
        assertEquals(BASE + sha(0), FileBody.partUrl(BASE, sha(0)))
    }

    @Test fun keyRules() {
        rejected(raw(k = B64.encodeToString(ByteArray(31))), FileBodyReject.BAD_KEY)
        rejected(raw(k = B64.encodeToString(ByteArray(33))), FileBodyReject.BAD_KEY)
        rejected(raw(k = "notbase64!!"), FileBodyReject.BAD_KEY)
        rejected(raw(k = ""), FileBodyReject.BAD_KEY)
        rejected(raw(k = key.trimEnd('=')), FileBodyReject.BAD_KEY)
    }

    @Test fun structureRules() {
        rejected(raw(v = "2"), FileBodyReject.UNSUPPORTED_VERSION)
        rejected(raw(v = "01"), FileBodyReject.UNSUPPORTED_VERSION)
        rejected(raw(extra = listOf("X:1")), FileBodyReject.UNKNOWN_KEY)
        rejected(raw(extra = listOf("size:1")), FileBodyReject.UNKNOWN_KEY)
        rejected(raw(extra = listOf("MIME:text/plain")), FileBodyReject.DUPLICATE_KEY)
        rejected(raw(extra = listOf("CAP64:QQ==", "CAP64:QQ==")), FileBodyReject.DUPLICATE_KEY)
        rejected(raw() + "|", FileBodyReject.MALFORMED)
        rejected(raw() + "garbage", FileBodyReject.FOREIGN_URL) // glued onto the URL base
        rejected(raw() + "|garbage", FileBodyReject.MALFORMED)
        rejected(raw() + "||", FileBodyReject.MALFORMED)
        rejected(raw() + "\n", FileBodyReject.BAD_CHARS)
        rejected(" " + raw(), FileBodyReject.NOT_FILE)
        rejected(raw().replace("|SIZE:30000000", ""), FileBodyReject.MISSING_KEY)
        rejected(raw().replace("|KEY:$key", ""), FileBodyReject.MISSING_KEY)
        rejected(raw().replace("|URLBASE:$BASE", ""), FileBodyReject.MISSING_KEY)
        rejected(raw().replace("|MIME:application/pdf", ""), FileBodyReject.MISSING_KEY)
        rejected(raw().replace("|NAME64:cmVwb3J0LnBkZg==", ""), FileBodyReject.MISSING_KEY)
        rejected(raw().replace("|PARTS:2", ""), FileBodyReject.MISSING_KEY)
        rejected(raw().replace("|V:1", ""), FileBodyReject.MISSING_KEY)
        rejected(raw().replace("SIZE", "SIZE:"), FileBodyReject.BAD_SIZE)
        rejected(raw().replace("|MIME:application/pdf", "|MIME"), FileBodyReject.MALFORMED)
        rejected(raw().replace("MIME:application/pdf", "é:1"), FileBodyReject.BAD_CHARS)
        // any key order is accepted
        val parts = raw().split('|')
        val shuffled = (listOf(parts[0]) + parts.drop(1).reversed()).joinToString("|")
        assertEquals(body(), ok(shuffled))
    }

    @Test fun captionRules() {
        fun cap(s: String) = "CAP64:" + B64.encodeToString(s.toByteArray())
        assertEquals("hi", ok(raw(extra = listOf(cap("hi")))).caption)
        assertEquals("hi", ok(raw(extra = listOf(cap("h\u0000i|")))).caption) // sanitised like MediaBody
        assertEquals("", ok(raw(extra = listOf(cap("")))).caption)
        assertEquals(200, ok(raw(extra = listOf(cap("c".repeat(500))))).caption.length)
        rejected(raw(extra = listOf("CAP64:!!!")), FileBodyReject.BAD_CAPTION)
        rejected(raw(extra = listOf("CAP64:" + B64.encodeToString(byteArrayOf(0xC3.toByte(), 0x28)))), FileBodyReject.BAD_CAPTION)
        rejected(raw(extra = listOf("CAP64:" + "A".repeat(5000))), FileBodyReject.BAD_CAPTION)
        rejected(raw(extra = listOf(cap("TYPE:REPLY|{bad"))).replace("TYPE:REPLY", "TYPE:REPLY"), FileBodyReject.BAD_CAPTION)
        val tooLongReply = ReplyBody.format(ReplyBody.Reply("A", "q", "r", "t".repeat(201)))
        rejected(raw(extra = listOf(cap(tooLongReply))), FileBodyReject.BAD_CAPTION)
    }

    @Test fun hostileInputNeverThrows() {
        val rnd = java.util.Random(7)
        val seeds = listOf(raw(), raw(extra = listOf("CAP64:QQ==")))
        repeat(3000) {
            val s = StringBuilder(seeds[rnd.nextInt(2)])
            repeat(1 + rnd.nextInt(4)) {
                val i = rnd.nextInt(s.length + 1)
                when (rnd.nextInt(3)) {
                    0 -> if (i < s.length) s.deleteCharAt(i)
                    1 -> s.insert(i, "|:=,\u0000é😀".random(rnd))
                    else -> if (i < s.length) s.setCharAt(i, (rnd.nextInt(0x7f - 0x20) + 0x20).toChar())
                }
            }
            FileBody.parse(s.toString(), ours) // must return, never throw
        }
        parse("TYPE:FILE|" + "|".repeat(100_000)); parse("TYPE:FILE|" + "A".repeat(10_000_000))
    }

    private fun String.random(r: java.util.Random) = this[r.nextInt(length)]
}
