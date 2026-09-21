package com.relay.app.transport

import com.relay.app.transport.nostr.InboxRelays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxRelaysTest {

    @Test
    fun parsesOnlyRelayTagsWithValidUrls() {
        val tags = listOf(
            listOf("relay", "wss://a.example"),
            listOf("relay", "wss://a.example"), // duplicate
            listOf("p", "wss://not-a-relay-tag.example"),
            listOf("relay"), // too short
            listOf("relay", "ws://insecure.example"),
            listOf("relay", "https://web.example"),
            listOf("relay", "wss://user:pw@host.example"),
            listOf("relay", "wss://has space.example"),
            listOf("relay", "  wss://trimmed.example  "),
        )
        assertEquals(listOf("wss://a.example", "wss://trimmed.example"), InboxRelays.parseRelayTags(tags))
    }

    @Test
    fun parseIsBoundedToFiveRelays() {
        val tags = (1..10).map { listOf("relay", "wss://r$it.example") }
        assertEquals(5, InboxRelays.parseRelayTags(tags).size)
    }

    @Test
    fun toTagsRoundTripsThroughParse() {
        val relays = listOf("wss://one.example", "wss://two.example")
        assertEquals(relays, InboxRelays.parseRelayTags(InboxRelays.toTags(relays)))
    }

    @Test
    fun toTagsDropsInvalidEntries() {
        assertEquals(listOf(listOf("relay", "wss://ok.example")), InboxRelays.toTags(listOf("nope", "wss://ok.example", "ws://x")))
    }

    @Test
    fun publishedListWinsOverEverythingElse() {
        val targets = InboxRelays.resolveTargets(
            published = listOf("wss://theirs.example"),
            hints = listOf("wss://hint.example"),
            ourRelays = listOf("wss://ours.example"),
        )
        assertEquals(listOf("wss://theirs.example"), targets)
    }

    @Test
    fun fallsBackToHintsThenOurRelaysWhenNothingPublished() {
        val expected = listOf("wss://hint.example", "wss://ours.example")
        assertEquals(expected, InboxRelays.resolveTargets(null, listOf("wss://hint.example"), listOf("wss://ours.example")))
        assertEquals(expected, InboxRelays.resolveTargets(emptyList(), listOf("wss://hint.example"), listOf("wss://ours.example")))
    }

    @Test
    fun fallbackFiltersUnacceptableAndDedupes() {
        val targets = InboxRelays.resolveTargets(null, listOf("ws://bad", "wss://same.example"), listOf("wss://same.example"))
        assertEquals(listOf("wss://same.example"), targets)
    }

    @Test
    fun acceptableRelayRules() {
        assertTrue(InboxRelays.isAcceptableRelay("wss://relay.example"))
        assertTrue(InboxRelays.isAcceptableRelay("wss://relay.example/path"))
        assertFalse(InboxRelays.isAcceptableRelay("wss://"))
        assertFalse(InboxRelays.isAcceptableRelay("wss://" + "a".repeat(120)))
        assertFalse(InboxRelays.isAcceptableRelay("wss://a,b.example"))
        assertFalse(InboxRelays.isAcceptableRelay("wss://user@host.example/x"))
    }
}
