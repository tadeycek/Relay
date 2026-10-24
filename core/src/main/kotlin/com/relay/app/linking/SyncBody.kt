package com.relay.app.linking

/**
 * `TYPE:SYNC` bodies (05 §3). They are only ever sent to our own pubkey; a receiver drops them from
 * any other sender. Every body carries a protocol [version], a unique [syncId], a monotonic
 * [revision] per sync id and the [origin] device, so the phone's own sent-copies can be ignored and
 * events can be ordered durably rather than by arrival.
 *
 * [SyncBody.Part] is the bounded chunk envelope used when the logical body would exceed the 16 KiB
 * mailbox limit after base64 (G3: there is no pre-existing generic chunking rule).
 */
sealed interface SyncBody {
    val origin: String
    val syncId: String
    val revision: Long

    /** A message this device sent, mirrored to the other devices (same payload id for receipts). */
    data class Sent(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        /** Contact pubkey (64 hex) or `group:<owner pubkey>:<group uuid>`. */
        val target: String,
        /** Base64 of a `RelayPayload` JSON (id, ts, body) — the doc's `P:` field. */
        val payloadBase64: String,
    ) : SyncBody

    /** Read up to [ts] in [conversation]. */
    data class Read(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        val conversation: String,
        val ts: Long,
    ) : SyncBody

    /** Key rotation (primary only): base64 of the sealed current+previous keysets and expiry. */
    data class Keys(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        val keysetsBase64: String,
    ) : SyncBody

    /** Contact added/changed/verified/blocked. Base64 JSON; see [SyncJson]. */
    data class Contact(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        val contactJsonBase64: String,
    ) : SyncBody

    /** Contact deleted. Base64 JSON identifying the contact (its Nostr pubkey). */
    data class ContactDelete(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        val contactJsonBase64: String,
    ) : SyncBody

    /** Our view of a group roster changed. Base64 JSON roster+meta. */
    data class Group(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        val rosterJsonBase64: String,
    ) : SyncBody

    /** Typing on/off, notification preview, retention. Base64 JSON settings. */
    data class Settings(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        val settingsJsonBase64: String,
    ) : SyncBody

    /** Delete-for-me mirrored to our other devices. */
    data class Delete(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        val conversation: String,
        val payloadId: String,
    ) : SyncBody

    /**
     * One part of a logical [kind] body. [kind] is the original kind (`KEYS`, `GROUP`, …);
     * [sha256Hex] is the hash of the whole logical wire body, [totalBytes] its byte length.
     */
    data class Part(
        override val origin: String,
        override val syncId: String,
        override val revision: Long,
        val kind: String,
        val index: Int,
        val count: Int,
        val totalBytes: Long,
        val sha256Hex: String,
        val dataBase64Url: String,
    ) : SyncBody
}
