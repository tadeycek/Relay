package com.relay.app.transport.nostr

/**
 * Starter relay list. These are well-known public relays chosen only as defaults for development;
 * they are not endorsements and their retention/rate-limit policies are unverified (see
 * rebuild/08-risks-and-open-questions.md). Users can replace the list in Settings.
 */
object DefaultRelays {
    val CLEARNET = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
    )
}
