package com.relay.app.linking

import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's rows must never make the bundle unencodable: every bound is applied here, locally. */
class LinkBundleMappingTest {

    private val pubkey = "ab".repeat(32)
    private val validKey = "A".repeat(120)

    private fun contact(
        name: String = "Alice",
        phone: String = "",
        trust: ContactTrustLevel = ContactTrustLevel.TRUSTED,
        publicKey: String? = null,
        relays: List<String> = emptyList(),
        nostr: String? = pubkey,
    ) = Contact(
        name = name,
        phone = phone,
        hasRelay = true,
        trustLevel = trust,
        publicKey = publicKey,
        relayHints = relays,
        nostrPubkey = nostr,
        qrVerified = true,
    )

    @Test
    fun placeholderPhoneIsNotCarriedAsAMessageAddress() {
        val mapped = LinkBundleMapping.contacts(listOf(contact(phone = Contact.placeholderPhoneFor(pubkey)))).single()
        assertEquals("", mapped.phone)
    }

    @Test
    fun softDeletedContactKeepsItsMarker() {
        val deleted = contact().copy(deletedAt = 1_700_000_000_000, originalName = "Al")
        val mapped = LinkBundleMapping.contacts(listOf(deleted)).single()
        assertEquals(1_700_000_000_000, mapped.deletedAt)
        assertEquals("Al", mapped.originalName)
    }

    @Test
    fun overlongNamesAreTrimmedAndControlCharactersRemoved() {
        val mapped = LinkBundleMapping.contacts(listOf(contact(name = "A\u0007" + "b".repeat(200)))).single()
        assertEquals(LinkBundleCodec.MAX_NAME, mapped.name.length)
        assertTrue(mapped.name.all { it.code >= 0x20 })
    }

    @Test
    fun blankNameGetsAPlaceholderSoTheBundleStaysEncodable() {
        assertEquals("Unknown", LinkBundleMapping.contacts(listOf(contact(name = "  "))).single().name)
    }

    @Test
    fun oversizeOrUnusableKeysAreDroppedWhole() {
        val huge = "A".repeat(LinkBundleCodec.MAX_PUBLIC_KEY_BASE64 + 1)
        val mapped = LinkBundleMapping.contacts(
            listOf(contact(publicKey = huge), contact(name = "Bob", publicKey = validKey)),
        )
        assertNull(mapped[0].publicKey)
        assertEquals(validKey, mapped[1].publicKey)
    }

    @Test
    fun relayHintsAreValidatedDedupedAndBounded() {
        val hints = listOf(
            "wss://inbox.example.com",
            "wss://inbox.example.com",
            "http://cleartext.example.com",
            "wss://second.example.com",
            "wss://third.example.com",
            "wss://fourth.example.com",
            "wss://fifth.example.com",
            "wss://sixth.example.com",
        )
        val mapped = LinkBundleMapping.contacts(listOf(contact(relays = hints))).single()
        assertEquals(LinkBundleCodec.MAX_RELAY_HINTS, mapped.relayHints.size)
        assertEquals("wss://inbox.example.com", mapped.relayHints.first())
        assertTrue(mapped.relayHints.none { it.startsWith("http://") })
        assertEquals(mapped.relayHints.distinct(), mapped.relayHints)
    }

    @Test
    fun contactListIsCappedAtTheCodecBound() {
        val rows = (1..LinkBundleCodec.MAX_CONTACTS + 50).map { contact(name = "c$it") }
        assertEquals(LinkBundleCodec.MAX_CONTACTS, LinkBundleMapping.contacts(rows).size)
    }

    @Test
    fun groupWithoutAProtocolUuidIsNotLinkable() {
        assertNull(LinkBundleMapping.group("Walkers", protocolId = null, owner = pubkey, members = listOf(pubkey)))
        assertNull(LinkBundleMapping.group("Walkers", protocolId = "42", owner = pubkey, members = listOf(pubkey)))
    }

    @Test
    fun groupMembersAreFilteredToPubkeysAndDeduped() {
        val group = LinkBundleMapping.group(
            name = "Walkers",
            protocolId = "8b6b6f5e-1111-4222-8333-444455556666",
            owner = pubkey,
            members = listOf(pubkey, pubkey, "not-a-key", "cd".repeat(32)),
        )
        assertNotNull(group)
        assertEquals(listOf(pubkey, "cd".repeat(32)), group!!.members)
        assertEquals(pubkey, group.sharedOwner)
    }

    @Test
    fun settingsAreCoercedIntoTheSchema() {
        val s = LinkBundleMapping.settings(
            typingIndicators = true,
            notificationPreview = false,
            retentionDays = 10_000,
            readReceipts = false,
            mediaAutoDownload = false,
            theme = "t".repeat(100),
            locale = "sl-SI",
        )
        assertEquals(LinkBundleCodec.MAX_RETENTION_DAYS, s.retentionDays)
        assertEquals(LinkBundleCodec.MAX_THEME, s.theme.length)
        assertEquals("sl-SI", s.locale)
    }

    @Test
    fun aFullyMappedBundleEncodes() {
        val bundle = LinkBundle(
            createdAt = 1_700_000_000_000,
            identity = LinkIdentity(
                nostrSecretHex = "11".repeat(32),
                nostrPubkeyHex = "22".repeat(32),
                deviceId = "primary",
                deviceLabel = "phone",
                hpkeKeysetBase64 = "Q".repeat(120),
            ),
            contacts = LinkBundleMapping.contacts(listOf(contact(publicKey = validKey, relays = listOf("wss://inbox.example.com")))),
            groups = listOfNotNull(
                LinkBundleMapping.group("Walkers", "8b6b6f5e-1111-4222-8333-444455556666", pubkey, listOf(pubkey)),
            ),
            settings = LinkBundleMapping.settings(true, true, 7, true, true, "dark", "en"),
        )
        val json = LinkBundleCodec.encode(bundle)
        assertTrue(LinkBundleCodec.decode(json) is LinkResult.Ok)
    }
}
