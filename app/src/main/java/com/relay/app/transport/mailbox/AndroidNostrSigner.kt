package com.relay.app.transport.mailbox

import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag

/**
 * Signs with the rust-nostr [Keys] of this install. [keys] is resolved at signing time (not at
 * construction), exactly when the old `MailboxAuth.header(keys, ...)` call sites resolved it, so a
 * failure to load the identity surfaces at the same point as before.
 */
class AndroidNostrSigner(private val keys: () -> Keys) : NostrSigner {
    override fun signEventJson(kind: Int, content: String, tags: List<List<String>>): String =
        EventBuilder(Kind(kind.toUShort()), content).tags(tags.map { Tag.parse(it) }).signWithKeys(keys()).asJson()
}
