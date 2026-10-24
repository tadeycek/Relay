package com.relay.app.messaging

import com.relay.app.media.MediaBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notice path exists only for bodies this build cannot render. The regression it was written to
 * catch: every incoming reply (`TYPE:REPLY|{json}`) was stored as "[Unsupported message type]".
 */
class UnsupportedBodyTest {

    @Test
    fun aRealReplyIsNeverReplaced() {
        val reply = ReplyBody.format(ReplyBody.Reply(name = "Ana", quote = "see you at 6", reference = "abc123", text = "Thanks, got it"))
        assertNull(UnsupportedBody.notice(reply))
        assertNull(UnsupportedBody.notice("""TYPE:REPLY|{"v":1,"name":"Ana","quote":"q","ref":"1","text":"hi"}"""))
        assertTrue("the fixture must really be a reply body", reply.startsWith("TYPE:REPLY|"))
    }

    @Test
    fun everyKnownPrefixIsLeftAlone() {
        for (prefix in UnsupportedBody.KNOWN) {
            assertNull("known prefix $prefix must be handled, not noticed", UnsupportedBody.notice("TYPE:$prefix|x:1"))
            assertNull("bare known prefix $prefix", UnsupportedBody.notice("TYPE:$prefix"))
        }
    }

    @Test
    fun anUnknownControlTypeBecomesTheNotice() {
        assertEquals(UnsupportedBody.NOTICE, UnsupportedBody.notice("TYPE:FOO|a:b"))
        assertEquals(UnsupportedBody.NOTICE, UnsupportedBody.notice("TYPE:NOT_A_THING"))
        assertEquals(UnsupportedBody.NOTICE, UnsupportedBody.notice("TYPE:FROM_THE_FUTURE|payload"))
    }

    @Test
    fun proseMentioningTypeIsUntouched() {
        for (text in listOf(
            "TYPE: this is what I meant",
            "the type: note is fine",
            "I sent a TYPE to them",
            "type",
            "",
            " ",
        )) {
            assertNull(text, UnsupportedBody.notice(text))
        }
    }

    @Test
    fun plainTextAndLongBodiesAreUntouched() {
        assertNull(UnsupportedBody.notice("hello"))
        assertNull(UnsupportedBody.notice("x".repeat(20_000)))
        // A very long body that begins with prose, not a TYPE token.
        assertNull(UnsupportedBody.notice("TYPE: lowercase start " + "y".repeat(10_000)))
    }

    @Test
    fun tokenMustBeFollowedByASeparator() {
        // Not a control body: the token continues, so it is prose or a truncated word.
        assertNull(UnsupportedBody.notice("TYPE:REPLYING to you"))
        // A real unknown control body uses the separator.
        assertEquals(UnsupportedBody.NOTICE, UnsupportedBody.notice("TYPE:REPLYING|x"))
    }

    @Test
    fun knownTypesStayRecognisedByTheClassifier() {
        // Guard against the notice list drifting away from what the classifier actually handles:
        // every prefix the app renders must be listed as known here.
        assertNull(UnsupportedBody.notice(MediaBody.format(sampleRef())))
    }

    private fun sampleRef() = com.relay.app.media.MediaRef(
        url = "https://relay.example/v1/media/" + "a".repeat(64),
        sha256Hex = "b".repeat(64),
        keyBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        mime = "image/jpeg",
        size = 1024,
    )
}
