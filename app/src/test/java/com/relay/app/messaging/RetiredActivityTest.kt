package com.relay.app.messaging

import org.junit.Assert.*
import org.junit.Test

class RetiredActivityTest {
    @Test fun oldActivityControlsAreIgnoredEvenIfMalformed() {
        for (suffix in listOf("", "{}", "{\"v\":1,\"time\":1,\"active\":true}", "broken")) {
            assertSame(Classified.Ignore, IncomingClassifier.classify(RetiredActivity.PREFIX + suffix))
        }
    }

    @Test fun ordinaryTextsAndOtherControlsAreUnaffected() {
        assertFalse(RetiredActivity.isControl("Hello"))
        assertSame(Classified.Text, IncomingClassifier.classify("Hello"))
        assertFalse(RetiredActivity.isControl(TypingBody.PREFIX + "{}"))
    }
}
