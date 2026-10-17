package com.relay.app.ui.screens.chat

import org.junit.Assert.*
import org.junit.Test

class AcceptedDraftTest {
    private val sent=AcceptedDraft("composer",3,"attachment","first","reply")
    @Test fun unchangedAcceptedTextClears() { assertEquals("",sent.remainingText("first",true,true)) }
    @Test fun typingWhilePendingKeepsOnlyTheNewSuffix() { assertEquals(" second",sent.remainingText("first second",false,true)) }
    @Test fun replacementIsANewDraftEvenWhenItSharesAPrefix() { assertEquals("first replacement",sent.remainingText("first replacement",false,false)) }
    @Test fun inPlaceEditAndDeletedPrefixRemainRecoverable() {
        assertEquals("firXst",sent.remainingText("firXst",false,false))
        assertEquals("new",sent.remainingText("new",false,false))
    }
    @Test fun emptyAttachmentCaptionNeverConsumesNewText() { assertEquals("new",sent.copy(text="").remainingText("new",false,true)) }
    @Test fun changedReplyWithUnchangedTextStillClearsAcceptedText() { assertEquals("",sent.remainingText("first",false,true)) }
    @Test fun duplicateGuardIdentityIncludesComposerAndRevision() { assertNotEquals(sent,sent.copy(composerId="other")); assertNotEquals(sent,sent.copy(revision=4)) }
    @Test fun staleKeyboardCallbackCannotRestoreTheAcceptedPrefix() {
        assertEquals(" next",sent.rebaseKeyboardEdit("first","","first next"))
        assertEquals(" next!",sent.rebaseKeyboardEdit("first next"," next","first next!"))
        assertEquals("first new message",sent.rebaseKeyboardEdit("first new","first new","first new message"))
        assertEquals("replacement",sent.rebaseKeyboardEdit("first","","replacement"))
    }
}
