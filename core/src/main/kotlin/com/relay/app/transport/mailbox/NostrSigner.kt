package com.relay.app.transport.mailbox

/**
 * Signs Nostr events with this install's identity key (WP03 seam). Android implements it over the
 * rust-nostr `Keys` held in EncryptedSharedPreferences; the desktop over its own secret store.
 * `:core` therefore needs no Nostr SDK, and never sees a secret key.
 */
interface NostrSigner {
    /** Builds, signs and returns the event as Nostr JSON (`id`, `pubkey`, `created_at`, `kind`, `tags`, `content`, `sig`). */
    fun signEventJson(kind: Int, content: String, tags: List<List<String>>): String
}
