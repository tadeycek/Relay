package com.relay.app.transport.nostr

/**
 * NIP-17 says a client must publish a private message only to the relays listed in the
 * *recipient's* kind 10050 event, and each user publishes such a list of their inbox relays. These
 * helpers are pure (tags as plain string lists, no SDK types) so the logic is unit-testable.
 */
object InboxRelays {

    const val KIND = 10050
    private const val MAX_RELAYS = 5
    private const val MAX_URL_LENGTH = 100

    /** Extracts the `["relay", url]` tags of a kind 10050 event: valid wss URLs only, deduped, bounded. */
    fun parseRelayTags(tags: List<List<String>>): List<String> = tags
        .filter { it.size >= 2 && it[0] == "relay" }
        .map { it[1].trim() }
        .filter { isAcceptableRelay(it) }
        .distinct()
        .take(MAX_RELAYS)

    /** The tag list to publish for our own inbox relays. */
    fun toTags(relays: List<String>): List<List<String>> =
        relays.filter { isAcceptableRelay(it) }.distinct().take(MAX_RELAYS).map { listOf("relay", it) }

    /**
     * Where to send a message for a recipient: their published inbox relays if known (NIP-17); else
     * the relay hints from their QR code plus our own configured relays, so a first message to
     * someone who has not (yet) published a list still has somewhere to go.
     */
    fun resolveTargets(published: List<String>?, hints: List<String>, ourRelays: List<String>): List<String> {
        if (!published.isNullOrEmpty()) return published
        return (hints + ourRelays).filter { isAcceptableRelay(it) }.distinct()
    }

    /** Relay URLs come from other people (QR codes, published lists): only well-formed wss:// are used. */
    fun isAcceptableRelay(url: String): Boolean =
        url.startsWith("wss://") && url.length in 8..MAX_URL_LENGTH &&
            url.none { it.isWhitespace() || it == ',' || it.isISOControl() } &&
            !url.substring("wss://".length).substringBefore('/').contains('@')
}
