package com.relay.app.desktop

import com.relay.app.media.ProfileNameBody
import com.relay.app.media.ProfilePhotoBody
import com.relay.app.transport.SendResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.rules.TemporaryFolder

/**
 * Profile names and avatars: revision-guarded persistence, direct fan-out to contacts, and the
 * request/receive controls the phone already understands. Real codec, real encrypted store, fake sender.
 */
class DesktopProfilesTest {

    private fun jpeg(color: Int): ByteArray = ByteArrayOutputStream().use { out ->
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, color)
        ImageIO.write(image, "jpeg", out)
        out.toByteArray()
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val own = "a".repeat(64)
    private val peerA = "b".repeat(64)
    private val peerB = "c".repeat(64)

    private class FakeSender : GroupSender {
        data class Delivery(val peer: String, val inner: String?, val relays: List<String>, val body: String)
        val deliveries = mutableListOf<Delivery>()
        var failFor = mutableSetOf<String>()

        override suspend fun send(peer: String, inner: String?, relays: List<String>, body: String): SendResult {
            deliveries += Delivery(peer, inner, relays, body)
            return if (peer in failFor) SendResult.Failed("offline", retryable = true) else SendResult.Sent(1)
        }
    }

    private fun newStore(): DesktopMessageStore {
        val paths = DesktopPaths(tmp.newFolder().toPath(), tmp.newFolder().toPath())
        return DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile))
    }

    private fun seedContacts(store: DesktopMessageStore) {
        store.importLinkBundle(
            com.relay.app.linking.LinkBundle(
                createdAt = 1L,
                identity = com.relay.app.linking.LinkIdentity("00".repeat(32), own, "primary", "fixture", "fixture"),
                contacts = listOf(
                    com.relay.app.linking.LinkContact(
                        name = "Bea", hasRelay = true, trustLevel = "trusted", nostrPubkey = peerA,
                        publicKey = "PUBLICKEYBASE64AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", qrVerified = true,
                    ),
                    com.relay.app.linking.LinkContact(
                        name = "Cy", hasRelay = true, trustLevel = "trusted", nostrPubkey = peerB,
                        publicKey = "PUBLICKEYBASE64BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", qrVerified = true,
                    ),
                ),
                groups = emptyList(),
                settings = com.relay.app.linking.LinkSettings(),
            ),
            appliedAt = 1L,
        )
    }

    private fun profiles(store: DesktopMessageStore, sender: FakeSender = FakeSender()) = DesktopProfiles(
        store = store, ownPubkey = own, nameProvider = { "Alex" }, sender = sender,
        onReply = { _, _ -> },
    )

    @Test
    fun `stranger profile controls create no row and receive no reply`() {
        newStore().use { store ->
            val replies = mutableListOf<String>()
            val profiles = DesktopProfiles(store, own, { "Alex" }, FakeSender(), onReply = { _, body -> replies += body })
            profiles.handle(peerA, ProfileNameBody.format(ProfileNameBody.Update(1, "Stranger")))
            profiles.handle(peerA, ProfilePhotoBody.REQUEST)
            assertNull(store.profile(peerA))
            assertTrue(replies.isEmpty())
        }
    }

    @Test
    fun `requesting a stranger profile sends nothing`() = runBlocking<Unit> {
        newStore().use { store ->
            val sender = FakeSender()
            assertEquals(0, profiles(store, sender).request(peerA).sent)
            assertTrue(sender.deliveries.isEmpty())
        }
    }

    @Test
    fun `unverified and pending contacts do not receive profile fanout or updates`() = runBlocking<Unit> {
        newStore().use { store ->
            store.importLinkBundle(com.relay.app.linking.LinkBundle(
                createdAt = 1, identity = com.relay.app.linking.LinkIdentity("00".repeat(32), own, "primary", "fixture", "fixture"),
                contacts = listOf(
                    com.relay.app.linking.LinkContact(name = "Unverified", hasRelay = true, trustLevel = "trusted", nostrPubkey = peerA,
                        publicKey = "PUBLICKEYBASE64AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", qrVerified = false),
                    com.relay.app.linking.LinkContact(name = "Pending", hasRelay = true, trustLevel = "trusted", nostrPubkey = peerB,
                        publicKey = "PUBLICKEYBASE64BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", pendingPublicKey = "pending", qrVerified = true),
                ), groups = emptyList(), settings = com.relay.app.linking.LinkSettings()), appliedAt = 1)
            val sender = FakeSender()
            val profiles = profiles(store, sender)
            assertEquals(0, profiles.publishName("Alex").sent)
            assertTrue(sender.deliveries.isEmpty())
            profiles.handle(peerA, ProfileNameBody.format(ProfileNameBody.Update(1, "Unverified")))
            profiles.handle(peerB, ProfileNameBody.format(ProfileNameBody.Update(1, "Pending")))
            assertNull(store.profile(peerA))
            assertNull(store.profile(peerB))
            assertTrue(store.contacts().none { it.eligibleForGroups() })
        }
    }

    @Test
    fun `fake and huge dimension JPEG headers are rejected before persistence`() {
        newStore().use { store ->
            seedContacts(store)
            val profiles = profiles(store)
            val fake = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1)
            val huge = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 17, 8,
                0x7f, 0xff.toByte(), 0x7f, 0xff.toByte(), 3, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0,
                0xff.toByte(), 0xd9.toByte())
            for (jpeg in listOf(fake, huge)) {
                profiles.handle(peerA, ProfilePhotoBody.format(ProfilePhotoBody.Update(1, jpeg)))
                assertNull(store.profile(peerA))
            }
        }
    }

    @Test
    fun `setting a name persists it as this install's profile and fans out to contacts`() = runBlocking<Unit> {
        val store = newStore()
        seedContacts(store)
        val sender = FakeSender()
        val profiles = profiles(store, sender)
        val report = profiles.publishName("Alexandra")
        assertEquals(2, report.sent)
        assertEquals("Alexandra", store.profile(own)!!.name)
        assertEquals(0L, store.profile(own)!!.nameRevision)
        assertEquals(setOf(peerA, peerB), sender.deliveries.map { it.peer }.toSet())
        val update = ProfileNameBody.parse(sender.deliveries.first().body)!!
        assertEquals("Alexandra", update.name)
        assertEquals(0L, update.revision)
        store.close()
    }

    @Test
    fun `a second name change increments the revision so peers accept it`() = runBlocking<Unit> {
        val store = newStore()
        seedContacts(store)
        val profiles = profiles(store)
        profiles.publishName("Alexandra")
        profiles.publishName("Lex")
        assertEquals("Lex", store.profile(own)!!.name)
        assertEquals(1L, store.profile(own)!!.nameRevision)
        store.close()
    }

    @Test
    fun `an invalid display name is refused and nothing is stored`() = runBlocking<Unit> {
        val store = newStore()
        seedContacts(store)
        val profiles = profiles(store)
        val bad = runCatching { profiles.publishName("  ") }
        assertTrue(bad.isFailure)
        assertNull(store.profile(own))
        store.close()
    }

    @Test
    fun `receiving a newer name updates the peer and an older replay is dropped`() {
        val store = newStore()
        seedContacts(store)
        val profiles = profiles(store)
        assertTrue(profiles.handle(peerA, ProfileNameBody.format(ProfileNameBody.Update(0, "Bea"))))
        assertEquals("Bea", profiles.displayName(peerA, "bbbbbbbb…"))
        // A same-revision replay is swallowed as a control but must not change the stored name.
        assertTrue(profiles.handle(peerA, ProfileNameBody.format(ProfileNameBody.Update(0, "Mallory"))))
        assertEquals("Bea", profiles.displayName(peerA, "bbbbbbbb…"))
        assertTrue(profiles.handle(peerA, ProfileNameBody.format(ProfileNameBody.Update(1, "Beatrice"))))
        assertEquals("Beatrice", profiles.displayName(peerA, "bbbbbbbb…"))
        // An unknown peer keeps the honest fallback rather than an invented name.
        assertEquals("cccccccc…", profiles.displayName(peerB, "cccccccc…"))
        store.close()
    }

    @Test
    fun `receiving an avatar stores it and a stale revision cannot replace it`() {
        val store = newStore()
        seedContacts(store)
        val profiles = profiles(store)
        val first = jpeg(0xff0000)
        val second = jpeg(0x00ff00)
        assertTrue(profiles.handle(peerA, ProfilePhotoBody.format(ProfilePhotoBody.Update(0, first))))
        assertTrue(profiles.avatar(peerA)!!.contentEquals(first))
        assertTrue(profiles.handle(peerA, ProfilePhotoBody.format(ProfilePhotoBody.Update(0, second))))
        assertTrue("a stale revision must not replace the picture", profiles.avatar(peerA)!!.contentEquals(first))
        assertTrue(profiles.handle(peerA, ProfilePhotoBody.format(ProfilePhotoBody.Update(1, second))))
        assertTrue(profiles.avatar(peerA)!!.contentEquals(second))
        // A clear is a valid update and leaves no avatar.
        assertTrue(profiles.handle(peerA, ProfilePhotoBody.format(ProfilePhotoBody.Update(2, null))))
        assertNull(profiles.avatar(peerA))
        store.close()
    }

    @Test
    fun `a malformed profile control is swallowed and leaves no profile`() {
        val store = newStore()
        val profiles = profiles(store)
        assertTrue(profiles.handle(peerA, "TYPE:PROFILE_PHOTO|KIND:NAME|REV:-1|NAME:!!!"))
        assertTrue(profiles.handle(peerA, "TYPE:PROFILE_PHOTO|REV:0|JPEG:not-base64"))
        assertNull(store.profile(peerA))
        store.close()
    }

    @Test
    fun `a photo request is answered with this install's name and avatar`() {
        val store = newStore()
        seedContacts(store)
        val replies = mutableListOf<Pair<String, String>>()
        val profiles = DesktopProfiles(store, own, { "Alex" }, FakeSender(), onReply = { peer, body -> replies += peer to body })
        profiles.handle(peerA, ProfilePhotoBody.REQUEST)
        assertEquals(2, replies.size)
        assertEquals(peerA, replies.first().first)
        assertTrue(replies.any { ProfileNameBody.isControl(it.second) })
        assertTrue(replies.any { ProfilePhotoBody.isControl(it.second) })
        store.close()
    }

    @Test
    fun `requesting a peer's profile sends the request control once per call`() = runBlocking<Unit> {
        val store = newStore()
        seedContacts(store)
        val sender = FakeSender()
        profiles(store, sender).request(peerA)
        assertEquals(listOf(peerA), sender.deliveries.map { it.peer })
        assertEquals(ProfilePhotoBody.REQUEST, sender.deliveries.single().body)
        store.close()
    }

    @Test
    fun `profile persistence survives a reopen`() {
        val paths = DesktopPaths(tmp.newFolder().toPath(), tmp.newFolder().toPath())
        val secret = FileSecretStore(paths.secretFile)
        DesktopMessageStore(paths.databaseFile, secret).use { store ->
            seedContacts(store)
            profiles(store).handle(peerA, ProfileNameBody.format(ProfileNameBody.Update(0, "Bea")))
        }
        DesktopMessageStore(paths.databaseFile, secret).use { reopened ->
            assertEquals("Bea", reopened.profile(peerA)!!.name)
        }
    }

    @Test
    fun `a failed fan-out is reported without losing the local name`() = runBlocking<Unit> {
        val store = newStore()
        seedContacts(store)
        val sender = FakeSender().apply { failFor = mutableSetOf(peerB) }
        val report = profiles(store, sender).publishName("Alexandra")
        assertEquals(1, report.sent)
        assertEquals(1, report.failed)
        assertEquals("Alexandra", store.profile(own)!!.name)
        store.close()
    }
}
