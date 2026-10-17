package com.relay.app.messaging

import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.conversation.ConversationFormat
import com.relay.app.media.MediaBody
import com.relay.app.media.MediaRef
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ConversationExtrasTest {
    @Test fun replyQuotesFlattenEarlierRepliesAndPreserveText() {
        val first = ReplyBody.Reply("Alice", "original", "reference", "reply 😄")
        val draft = ReplyBody.draft(Message(id = 2, contactId = 1, body = ReplyBody.format(first)), "Bob")
        val sent = ReplyBody.withText(draft, "next reply")
        assertEquals("reply 😄", ReplyBody.parse(sent)?.quote)
        assertEquals("next reply", ReplyBody.preview(sent))
        assertEquals("You: next reply", ConversationFormat.preview(MessageType.TEXT, sent, true))
    }
    @Test fun malformedReplyAndNormalTextRemainReadableAndBounded() {
        assertNull(ReplyBody.parse("TYPE:REPLY|{}"))
        assertEquals("hello", ReplyBody.preview("hello"))
        assertTrue(runCatching { ReplyBody.format(ReplyBody.Reply("A", "x".repeat(121), "ref", "text")) }.isFailure)
        assertEquals("Photo", ReplyBody.summary(Message(contactId = 1, body = "", type = MessageType.IMAGE)))
        assertEquals("Video", ReplyBody.summary(Message(contactId = 1, body = "", type = MessageType.VIDEO)))
    }
    @Test fun quotedMediaCaptionRoundTripsDelimitersWithoutBreakingMediaReference() {
        val reply = ReplyBody.format(ReplyBody.Reply("Alice", "quoted | text 😄", "ref", "caption | with delimiter"))
        val ref = MediaRef("https://example.com/blob", "a".repeat(64), Base64.getEncoder().encodeToString(ByteArray(32)), "image/jpeg", 100, reply)
        val wire = MediaBody.format(ref)
        assertEquals(ref, MediaBody.parse(wire))
        assertNull(MediaBody.parse(wire.substringBefore("|CAP64:") + "|CAP64:invalid"))
        val long = ReplyBody.format(ReplyBody.Reply("Alice", "quote", "ref", "x".repeat(300)))
        assertEquals(200, ReplyBody.parse(MediaBody.normalizeCaption(long))?.text?.length)
    }
    @Test fun typingStopsExpiresRejectsReorderedEventsAndStaysInsideItsConversation() {
        val presence = TypingPresence()
        assertTrue(presence.update("contact:1", "Alice", TypingBody.Signal(true, 100000), 1000))
        assertEquals("Alice", TypingPresence.names(presence.entries.value, 1, null, 1001))
        assertNull(TypingPresence.names(presence.entries.value, 2, null, 1001))
        assertNull(TypingPresence.names(presence.entries.value, 1, null, 9000))
        assertTrue(presence.update("contact:1", "Alice", TypingBody.Signal(false, 100100), 2000))
        assertNull(TypingPresence.names(presence.entries.value, 1, null, 2000))
        assertFalse(presence.update("contact:1", "Alice", TypingBody.Signal(true, 100000), 2001))
        assertTrue(presence.update("contact:2", "Bob", TypingBody.Signal(true, 100101), 2002))
        assertNull(TypingPresence.names(presence.entries.value, 1, null, 2003))
        assertEquals("Bob", TypingPresence.names(presence.entries.value, 2, null, 2003))
    }
    @Test fun typingAcceptsOnlyFreshSignalsAndSupportedFormat() {
        val now = 100000L
        val signal = TypingBody.Signal(true, now)
        assertEquals(signal, TypingBody.parse(TypingBody.format(signal), now))
        assertNull(TypingBody.parse(TypingBody.format(signal.copy(time = now - TypingBody.TTL - 1)), now))
        assertNull(TypingBody.parse(TypingBody.format(signal.copy(time = now + 2001)), now))
        assertNull(TypingBody.parse(TypingBody.PREFIX + "{}", now))
        assertNull(TypingBody.parse(TypingBody.format(signal.copy(group = "bad")), now))
        assertEquals(false, TypingBody.parse(TypingBody.format(signal.copy(typing = false)), now)?.typing)
    }
}
