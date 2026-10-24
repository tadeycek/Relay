package com.relay.app.linking

import com.relay.app.data.model.Contact
import com.relay.app.transport.nostr.InboxRelays

/**
 * App models → link-bundle models, with every bound applied locally so a strange contact can never
 * make the whole bundle unlinkable.
 *
 * Pure (no Android, no database): the phone's repository just feeds it rows. Contacts that would
 * break a bound are trimmed rather than dropped — a link that silently lost a person would be worse
 * than one with a shortened name — except keys, which are dropped whole because a truncated key is
 * useless and would fail the desktop's authentication anyway.
 */
object LinkBundleMapping {

    /** The conversation still exists, but the contact was soft-deleted; the desktop should know. */
    fun contacts(rows: List<Contact>): List<LinkContact> =
        rows.take(LinkBundleCodec.MAX_CONTACTS).map { contact(it) }

    private fun contact(c: Contact): LinkContact = LinkContact(
        name = text(c.name, LinkBundleCodec.MAX_NAME, fallback = "Unknown"),
        phone = if (c.hasPhone) text(c.phone, LinkBundleCodec.MAX_PHONE) else "",
        hasRelay = c.hasRelay,
        trustLevel = c.trustLevel.dbValue,
        publicKey = key(c.publicKey, LinkBundleCodec.MAX_PUBLIC_KEY_BASE64),
        pendingPublicKey = key(c.pendingPublicKey, LinkBundleCodec.MAX_PUBLIC_KEY_BASE64),
        signingPublicKey = key(c.signingPublicKey, LinkBundleCodec.MAX_SIGNING_KEY_BASE64),
        nostrPubkey = c.nostrPubkey?.takeIf { HEX64.matches(it) },
        relayHints = c.relayHints
            .filter { it.length <= LinkBundleCodec.MAX_RELAY_HINT && InboxRelays.isAcceptableRelay(it) }
            .distinct()
            .take(LinkBundleCodec.MAX_RELAY_HINTS),
        qrVerified = c.qrVerified,
        deletedAt = c.deletedAt?.takeIf { it >= 0 },
        originalName = c.originalName?.let { text(it, LinkBundleCodec.MAX_ORIGINAL_NAME) },
    )

    /**
     * One group for the bundle, or null when it has no protocol identity to link (a local-only group
     * has a SQLite row id, not the UUID the other devices address it by).
     */
    fun group(name: String, protocolId: String?, owner: String?, members: List<String>): LinkGroup? {
        if (protocolId == null || !UUID.matches(protocolId)) return null
        val keys = members.filter { HEX64.matches(it) }.distinct().take(LinkBundleCodec.MAX_GROUP_MEMBERS)
        return LinkGroup(
            id = protocolId,
            name = text(name, LinkBundleCodec.MAX_GROUP_NAME, fallback = "Group"),
            sharedOwner = owner?.takeIf { HEX64.matches(it) },
            members = keys,
        )
    }

    fun settings(
        typingIndicators: Boolean,
        notificationPreview: Boolean,
        retentionDays: Int,
        readReceipts: Boolean,
        mediaAutoDownload: Boolean,
        theme: String,
        locale: String,
    ): LinkSettings = LinkSettings(
        typingIndicators = typingIndicators,
        notificationPreview = notificationPreview,
        retentionDays = retentionDays.coerceIn(0, LinkBundleCodec.MAX_RETENTION_DAYS),
        readReceipts = readReceipts,
        mediaAutoDownload = mediaAutoDownload,
        theme = text(theme, LinkBundleCodec.MAX_THEME),
        locale = text(locale, LinkBundleCodec.MAX_LOCALE),
    )

    /** Drops control characters, trims to [max] chars and substitutes [fallback] when empty. */
    private fun text(value: String, max: Int, fallback: String = ""): String {
        val cleaned = buildString {
            for (c in value) {
                if (c.code < 0x20 || c.code == 0x7f) continue
                if (Character.getType(c) == Character.FORMAT.toInt()) continue
                append(c)
            }
        }.trim().take(max)
        return cleaned.ifEmpty { fallback }
    }

    private fun key(value: String?, max: Int): String? =
        value?.takeIf { it.isNotEmpty() && it.length <= max && BASE64.matches(it) }

    private val HEX64 = Regex("[0-9a-f]{64}")
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")
}
