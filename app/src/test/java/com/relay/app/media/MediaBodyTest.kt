package com.relay.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MediaBodyTest {

    private val sha = "ab".repeat(32)
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val ref = MediaRef(
        url = "https://blossom.example/$sha",
        sha256Hex = sha,
        keyBase64 = key,
        mime = "image/jpeg",
        size = 123_456,
        caption = "Sunset at the lake",
    )

    @Test
    fun roundTrips() {
        assertEquals(ref, MediaBody.parse(MediaBody.format(ref)))
    }

    @Test
    fun captionIsOptional() {
        val noCaption = ref.copy(caption = "")
        val body = MediaBody.format(noCaption)
        assertFalse(body.contains("CAP:"))
        assertEquals(noCaption, MediaBody.parse(body))
    }

    @Test
    fun detectsMediaBodies() {
        assertTrue(MediaBody.isMedia(MediaBody.format(ref)))
        assertTrue(MediaBody.isMedia("  type:media|URL:x"))
        assertFalse(MediaBody.isMedia("hello"))
        assertFalse(MediaBody.isMedia("TYPE:LOCATION|LAT:1.0|LNG:2.0"))
    }

    @Test
    fun videoFlagFollowsMime() {
        assertFalse(ref.isVideo)
        assertTrue(ref.copy(mime = "video/mp4").isVideo)
    }

    @Test
    fun onlyHttpsUrlsAreAccepted() {
        for (bad in listOf(
            "http://blossom.example/x", "file:///etc/passwd", "content://media/1", "javascript:alert(1)",
            "ftp://x/y", "//blossom.example/x", "https://", "https:///path", "https://user:pw@host/x",
            "https://host/with space", "https://host/a|b",
        )) {
            assertFalse(bad, MediaBody.isAcceptableUrl(bad))
        }
        assertTrue(MediaBody.isAcceptableUrl("https://blossom.example/abc.jpg"))
        assertTrue(MediaBody.isAcceptableUrl("HTTPS://Blossom.Example/abc"))
    }

    @Test
    fun rejectsOverlongUrl() {
        val longUrl = "https://h.example/" + "a".repeat(MediaBody.MAX_URL_LENGTH)
        assertFalse(MediaBody.isAcceptableUrl(longUrl))
    }

    @Test
    fun rejectsBadShaKeyMimeAndSize() {
        fun body(url: String = ref.url, s: String = sha, k: String = key, m: String = "image/jpeg", sz: String = "10") =
            "TYPE:MEDIA|URL:$url|SHA:$s|KEY:$k|MIME:$m|SIZE:$sz"
        assertNotNull(MediaBody.parse(body()))
        assertNull("short sha", MediaBody.parse(body(s = "ab".repeat(31))))
        assertNull("non-hex sha", MediaBody.parse(body(s = "zz".repeat(32))))
        assertNull("short key", MediaBody.parse(body(k = Base64.getEncoder().encodeToString(ByteArray(16)))))
        assertNull("invalid base64 key", MediaBody.parse(body(k = "@@@@")))
        assertNull("executable mime", MediaBody.parse(body(m = "application/x-msdownload")))
        assertNull("svg can carry script", MediaBody.parse(body(m = "image/svg+xml")))
        assertNull("zero size", MediaBody.parse(body(sz = "0")))
        assertNull("negative size", MediaBody.parse(body(sz = "-5")))
        assertNull("not a number", MediaBody.parse(body(sz = "big")))
        assertNull("over cap", MediaBody.parse(body(sz = (MediaBody.MAX_MEDIA_BYTES + 1).toString())))
        assertNotNull("exactly at cap", MediaBody.parse(body(sz = MediaBody.MAX_MEDIA_BYTES.toString())))
        assertNull("http url", MediaBody.parse(body(url = "http://blossom.example/x")))
    }

    @Test
    fun rejectsMissingFieldsAndGarbage() {
        assertNull(MediaBody.parse(""))
        assertNull(MediaBody.parse("hello"))
        assertNull(MediaBody.parse("TYPE:MEDIA|URL:https://h.example/x"))
        assertNull(MediaBody.parse("TYPE:MEDIA|broken"))
    }

    @Test
    fun uppercaseShaIsNormalised() {
        val body = MediaBody.format(ref).replace(sha, sha.uppercase())
        assertEquals(sha, MediaBody.parse(body)!!.sha256Hex)
    }

    @Test
    fun captionIsSanitisedAndCapped() {
        val evil = "hi‮ there\nnewline|pipe" + "x".repeat(500)
        val parsed = MediaBody.parse(MediaBody.format(ref.copy(caption = evil)))!!
        assertFalse(parsed.caption.contains('|'))
        assertFalse(parsed.caption.contains('\n'))
        assertFalse(parsed.caption.contains('‮'))
        assertTrue(parsed.caption.length <= MediaBody.MAX_CAPTION_LENGTH)
    }
}
