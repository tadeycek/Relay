package com.relay.app.messaging

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ConversationEventBodyTest {
    @Test fun screenshotsAndReactionsAreSeparateTypedControls() {
        val shot=ConversationEventBody.Event("screenshot",UUID.randomUUID().toString())
        assertEquals(shot,ConversationEventBody.parse(ConversationEventBody.format(shot)))
        for(emoji in ConversationEventBody.EMOJI + "") {
            val reaction=ConversationEventBody.Event("reaction",UUID.randomUUID().toString(),UUID.randomUUID().toString(),emoji,12)
            assertEquals(reaction,ConversationEventBody.parse(ConversationEventBody.format(reaction)))
        }
        assertNull(ConversationEventBody.parse("Someone took a screenshot"))
    }
    @Test fun malformedTargetsRevisionsAndEmojiAreRejected() {
        val base=ConversationEventBody.Event("reaction",UUID.randomUUID().toString(),UUID.randomUUID().toString(),"👍",1)
        for(bad in listOf(base.copy(target="not-an-id"),base.copy(id="fake"),base.copy(emoji="Injected notification"),base.copy(revision=0),base.copy(kind="admin"))) {
            assertThrows(IllegalArgumentException::class.java) { ConversationEventBody.format(bad) }
        }
        assertNull(ConversationEventBody.parse(ConversationEventBody.PREFIX+"{}"))
    }
    @Test fun savedPhotoAndScreenshotTargetsRoundtripWithoutChangingIdentity() {
        val photo=UUID.randomUUID().toString()
        val saved=ConversationEventBody.Event("media_saved",UUID.randomUUID().toString(),target=photo)
        val shot=ConversationEventBody.Event("screenshot",UUID.randomUUID().toString(),targets=listOf(photo,UUID.randomUUID().toString()))
        assertEquals(saved,ConversationEventBody.parse(ConversationEventBody.format(saved)))
        assertEquals(shot,ConversationEventBody.parse(ConversationEventBody.format(shot)))
    }
    @Test fun rejectFakeUnboundedAndDuplicatePhotoReferences() {
        val shot=ConversationEventBody.Event("screenshot",UUID.randomUUID().toString())
        val id=UUID.randomUUID().toString()
        for(bad in listOf(shot.copy(targets=listOf("fake")),shot.copy(targets=List(9) { UUID.randomUUID().toString() }),
            shot.copy(targets=listOf(id,id)),shot.copy(kind="media_saved"),shot.copy(kind="media_saved",target=id,targets=listOf(id)))) {
            assertThrows(IllegalArgumentException::class.java) { ConversationEventBody.format(bad) }
        }
    }
}
