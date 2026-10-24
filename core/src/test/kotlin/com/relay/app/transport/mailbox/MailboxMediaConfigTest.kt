package com.relay.app.transport.mailbox

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP03: the injected server URL (no BuildConfig in :core) and the URL rules built on it. */
class MailboxMediaConfigTest {
    private val hash = "a".repeat(64)

    @After fun reset() = MailboxConfig.install("")

    @Test fun uninstalledConfigIsUnavailableLikeABuildWithoutMailboxProperties() {
        MailboxConfig.install("")
        assertEquals("", MailboxConfig.url)
        assertFalse(MailboxConfig.available)
        assertFalse(MailboxMedia.isOurs("https://m.example/v1/media/$hash"))
    }

    @Test fun installTrimsWhitespaceAndTrailingSlashesAndRequiresHttps() {
        MailboxConfig.install("  https://m.example///\n")
        assertEquals("https://m.example", MailboxConfig.url)
        assertTrue(MailboxConfig.available)
        MailboxConfig.install("http://m.example")
        assertFalse(MailboxConfig.available)
    }

    @Test fun onlyFilesOnThisServerWithAHexHashAreOurs() {
        MailboxConfig.install("https://m.example/")
        assertEquals("https://m.example/v1/media/$hash", MailboxMedia.urlFor(hash))
        assertTrue(MailboxMedia.isOurs("https://m.example/v1/media/$hash"))
        assertFalse(MailboxMedia.isOurs("https://other.example/v1/media/$hash"))
        assertFalse(MailboxMedia.isOurs("https://m.example/v1/media/${hash.uppercase()}"))
        assertFalse(MailboxMedia.isOurs("https://m.example/v1/media/${hash}0"))
        assertFalse(MailboxMedia.isOurs("https://m.example/v1/media/$hash/../x"))
    }
}
