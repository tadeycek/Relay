package com.relay.app.linking

/**
 * The phone → desktop link payload (05 §4, LP-D06).
 *
 * It carries **identity keys, contacts, groups and settings only**. There is deliberately no
 * message, media or history field anywhere in this model: a linked desktop starts with empty
 * chats, which keeps the bundle inside the link slot's 128 KiB cap and avoids building a bulk
 * history-export path. [LinkBundleCodec] rejects any producer that tries to add one.
 *
 * Field bounds are constants on [LinkBundleCodec] so the encoder and decoder enforce exactly the
 * same limits (tested at and one over every bound).
 */
data class LinkBundle(
    /** Phone clock, ms since epoch. Receivers may use it for display only, never for trust. */
    val createdAt: Long,
    val identity: LinkIdentity,
    val contacts: List<LinkContact> = emptyList(),
    val groups: List<LinkGroup> = emptyList(),
    val settings: LinkSettings = LinkSettings(),
)

/**
 * The shared identity (LP-D02) plus the link/HPKE key material the desktop needs. `nostrSecretHex`
 * is an nsec-equivalent 32-byte scalar in hex; the keysets are opaque sealed Tink keyset bytes in
 * base64 — `:core` never parses or holds Tink types.
 */
data class LinkIdentity(
    /** 64 lowercase hex: the Nostr secret key. */
    val nostrSecretHex: String,
    /** 64 lowercase hex: the matching public key (checked by the receiver's NIP-98 identity). */
    val nostrPubkeyHex: String,
    /** Stable device id, `[A-Za-z0-9_-]{1,64}` (`primary` for the phone). */
    val deviceId: String,
    val deviceLabel: String = "",
    /** Sealed current HPKE/Tink keyset, base64. */
    val hpkeKeysetBase64: String,
    /** Sealed previous keyset during rotation grace, base64, or null. */
    val hpkePreviousKeysetBase64: String? = null,
    /** When the previous keyset stops decrypting old deliveries (ms), 0 if unused. */
    val hpkeKeysetExpiresAt: Long = 0,
)

/**
 * One contact, mirroring [com.relay.app.data.model.Contact] without its local row id. `trustLevel`
 * is the `dbValue` of `ContactTrustLevel` (`trusted` / `ask` / `blocked`).
 */
data class LinkContact(
    val name: String,
    val phone: String = "",
    val hasRelay: Boolean = false,
    val trustLevel: String = "ask",
    val publicKey: String? = null,
    val pendingPublicKey: String? = null,
    val signingPublicKey: String? = null,
    val nostrPubkey: String? = null,
    val relayHints: List<String> = emptyList(),
    val qrVerified: Boolean = false,
    val deletedAt: Long? = null,
    val originalName: String? = null,
)

/**
 * One group. Group ids are the protocol UUIDs used by `GroupProtocol.State`, not local SQLite row
 * ids, so they survive the move to a new database. Members are Nostr pubkeys already present in
 * [LinkBundle.contacts] or reachable through the roster.
 */
data class LinkGroup(
    val id: String,
    val name: String,
    val sharedOwner: String? = null,
    val members: List<String> = emptyList(),
)

/** Settings the desktop mirrors from the phone (05 §3 `SETTINGS`). */
data class LinkSettings(
    val typingIndicators: Boolean = true,
    val notificationPreview: Boolean = true,
    val retentionDays: Int = 7,
    val readReceipts: Boolean = true,
    val mediaAutoDownload: Boolean = true,
    val theme: String = "",
    val locale: String = "",
)
