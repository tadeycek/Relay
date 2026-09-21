package com.relay.app.media

/**
 * Starter Blossom media servers. Only their existence as public Blossom servers was seen in
 * research; their upload limits, authentication or payment requirements and retention are
 * **unverified** and must be checked before relying on them (see rebuild/08-risks-and-open-questions.md).
 * Uploads are always of end-to-end encrypted blobs, so a server learns only size and timing.
 */
object DefaultBlossomServers {
    val LIST = listOf(
        "https://blossom.nostr.build",
        "https://blossom.band",
    )
}
