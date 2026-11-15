package com.relay.app.media

import com.relay.app.transport.mailbox.MailboxMedia
import org.junit.Assert.*
import org.junit.Test

class MediaPartSizingTest {
    @Test fun countsStayCorrectAcrossSignedIntAndThreeGiBBoundaries() {
        assertEquals(1,MailboxMedia.partCount(1))
        assertEquals(1,MailboxMedia.partCount(MediaCrypto.PART_BYTES.toLong()))
        assertEquals(2,MailboxMedia.partCount(MediaCrypto.PART_BYTES+1L))
        assertEquals(2048,MailboxMedia.partCount(Int.MAX_VALUE.toLong()))
        assertEquals(2049,MailboxMedia.partCount(Int.MAX_VALUE.toLong()+2))
        assertEquals(3072,MailboxMedia.partCount(MediaBody.MAX_MEDIA_BYTES))
        assertEquals(3073,MailboxMedia.partCount(MediaCrypto.MAX_CIPHER_BYTES))
        assertThrows(IllegalArgumentException::class.java) { MailboxMedia.partCount(0) }
        assertThrows(IllegalArgumentException::class.java) { MailboxMedia.partCount(Long.MAX_VALUE) }
    }
}
