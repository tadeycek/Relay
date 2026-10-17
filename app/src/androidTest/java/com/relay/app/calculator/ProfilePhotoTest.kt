package com.relay.app.calculator

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.repository.ContactRepository
import com.relay.app.media.ContactPhotos
import com.relay.app.media.ProfilePhotoBody
import com.relay.app.media.ProfilePhotoSync
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.NotificationAvatar
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class ProfilePhotoTest {
    @Test fun mutualQrCompletionRepairsAnExistingPlaceholderContact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contacts = ContactRepository(MessagingDb.get(context))
        val key = rust.nostr.sdk.Keys.generate().publicKey().toHex()
        val contact = contacts.findOrCreateByNostrSync(key, "Contact ${key.take(8)}")
        try {
            val scanned = com.relay.app.util.QrContactCode.ScannedContact(
                phone = null, name = "Actual profile name", nostrPubkeyHex = key,
                publicKeyBase64 = RelayCrypto.myPublicKeyBase64(context)!!)
            val paired = com.relay.app.sms.QrContactExchange.onScanned(context, contacts, scanned, sendHandshake = false)
            assertTrue(paired.qrVerified)
            assertEquals("Actual profile name", paired.name)
            contacts.setNameSync(contact.id, "Local alias")
            val repaired = com.relay.app.sms.QrContactExchange.onScanned(context, contacts,
                scanned.copy(name = "New peer name"), sendHandshake = false)
            assertEquals("Local alias", repaired.name)
            assertEquals("New peer name", repaired.originalName)
        } finally {
            ContactPhotos.remove(context, contact.id)
            kotlinx.coroutines.runBlocking { contacts.deleteContact(contact.id) }
        }
    }

    @Test fun lateSharedProfileUpdatesTheActualAndroidNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val contacts = ContactRepository(MessagingDb.get(context))
        val key = rust.nostr.sdk.Keys.generate().publicKey().toHex()
        var contact = contacts.findOrCreateByNostrSync(key, "Contact ${key.take(8)}")
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        try {
            contacts.markQrVerifiedSync(contact.id)
            contacts.setPublicKeySync(contact.id, RelayCrypto.myPublicKeyBase64(context)!!)
            contact = contacts.getByIdSync(contact.id)!!
            com.relay.app.messaging.MessageNotifier.notifyIncoming(context, contact,
                com.relay.app.data.model.MessageType.TEXT, "Shared profile preview")
            android.os.SystemClock.sleep(300)
            val source = android.graphics.BitmapFactory.decodeResource(context.resources,
                com.relay.app.R.drawable.notification_sender_badge)
            val small = Bitmap.createScaledBitmap(source, 64, 64, true)
            val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            source.recycle(); small.recycle()
            ProfilePhotoSync.handle(context, contact, ProfilePhotoBody.format(ProfilePhotoBody.Update(1, jpeg)))
            ProfilePhotoSync.handle(context, contact, com.relay.app.media.ProfileNameBody.format(
                com.relay.app.media.ProfileNameBody.Update(1, "Exion preview")))
            android.os.SystemClock.sleep(500)
            val notification = manager.activeNotifications.first { it.tag == "conversation:${contact.id}" }.notification
            val history = com.relay.app.messaging.NotificationPreview.history(notification)
            val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
            assertEquals("Exion preview", style.messages.last().person!!.name.toString())
            assertEquals(1, history.size)
            assertEquals(history.last().text, style.messages.last().text.toString())
            assertNotNull(style.messages.last().person!!.icon)
            assertEquals("android.app.Notification\$MessagingStyle", notification.extras.getString("android.template"))
            assertEquals("conversation:${contact.id}", notification.shortcutId)
            assertFalse(notification.contentIntent.isActivity)
            device.openNotification()
            device.waitForIdle()
            assertTrue(device.takeScreenshot(File(context.filesDir, "shared-profile-notification.png")))
        } finally {
            device.pressBack()
            com.relay.app.messaging.MessageNotifier.cancel(context, contact.id)
            ContactPhotos.remove(context, contact.id)
            kotlinx.coroutines.runBlocking { contacts.deleteContact(contact.id) }
        }
    }

    @Test fun profileNamesRepairPlaceholdersRejectReplayAndPreserveLocalAliases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contacts = ContactRepository(MessagingDb.get(context))
        val key = rust.nostr.sdk.Keys.generate().publicKey().toHex()
        var contact = contacts.findOrCreateByNostrSync(key, "Contact ${key.take(8)}")
        fun name(revision: Long, value: String) = com.relay.app.media.ProfileNameBody.format(
            com.relay.app.media.ProfileNameBody.Update(revision, value))
        try {
            ProfilePhotoSync.handle(context, contact, name(1, "Exion"))
            assertTrue(contacts.getByIdSync(contact.id)!!.name.startsWith("Contact "))
            contacts.markQrVerifiedSync(contact.id)
            contacts.setPublicKeySync(contact.id, RelayCrypto.myPublicKeyBase64(context)!!)
            contact = contacts.getByIdSync(contact.id)!!
            ProfilePhotoSync.handle(context, contact, name(1, "Exion"))
            assertEquals("Exion", contacts.getByIdSync(contact.id)!!.name)
            ProfilePhotoSync.handle(context, contact, name(2, "Updated name"))
            ProfilePhotoSync.handle(context, contact, name(1, "Old name"))
            assertEquals("Updated name", contacts.getByIdSync(contact.id)!!.name)
            contacts.setNameSync(contact.id, "My local alias")
            ProfilePhotoSync.handle(context, contact, name(3, "Another profile name"))
            assertEquals("My local alias", contacts.getByIdSync(contact.id)!!.name)
            assertEquals("Another profile name", contacts.getByIdSync(contact.id)!!.originalName)
            ProfilePhotoSync.handle(context, contact.copy(trustLevel = ContactTrustLevel.BLOCKED), name(4, "Blocked"))
            assertEquals("Another profile name", contacts.getByIdSync(contact.id)!!.originalName)
        } finally {
            ContactPhotos.remove(context, contact.id)
            kotlinx.coroutines.runBlocking { contacts.deleteContact(contact.id) }
        }
    }

    @Test fun onlyApprovedContactsCanSetPhotosAndOldUpdatesCannotUndoRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val contacts = ContactRepository(MessagingDb.get(context))
        var contact = contacts.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "Photo fixture")
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val jpeg = ByteArrayOutputStream().also { source.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        source.recycle()
        val body = ProfilePhotoBody.format(ProfilePhotoBody.Update(1, jpeg))
        try {
            assertTrue(ProfilePhotoSync.handle(context, contact, body))
            assertFalse("Unapproved sender cannot set an avatar", ContactPhotos.hasPhoto(context, contact.id))
            contacts.markQrVerifiedSync(contact.id)
            contacts.setPublicKeySync(contact.id, RelayCrypto.myPublicKeyBase64(context)!!)
            contact = contacts.getByIdSync(contact.id)!!
            assertTrue(ProfilePhotoSync.handle(context, contact, body))
            val decoded = ContactPhotos.load(context, contact.id)!!
            assertEquals(64, decoded.width)
            assertTrue(Color.green(decoded.getPixel(10, 10)) > 200)
            val avatar = NotificationAvatar.forContact(contact, decoded)
            assertTrue(Color.green(avatar.getPixel(50, 50)) > 200)
            assertEquals(0, Color.alpha(avatar.getPixel(0, 0)))
            assertTrue("Rounded-square source is supplied before the OS applies its mask", Color.alpha(avatar.getPixel(20, 20)) > 0)
            assertTrue("Sender image has no painted app badge", Color.green(avatar.getPixel(158, 158)) > 200)
            File(context.filesDir, "notification-avatar-preview.png").outputStream().use {
                avatar.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            decoded.recycle(); avatar.recycle()
            val stored = File(context.filesDir, "contact-photos/${contact.id}.enc").readBytes()
            assertFalse("Canonical avatar is not a plaintext JPEG", stored.take(3) == jpeg.take(3))
            ProfilePhotoSync.handle(context, contact, ProfilePhotoBody.format(ProfilePhotoBody.Update(2, null)))
            assertFalse(ContactPhotos.hasPhoto(context, contact.id))
            ProfilePhotoSync.handle(context, contact, body)
            assertFalse("A delayed old photo cannot undo removal", ContactPhotos.hasPhoto(context, contact.id))
            ProfilePhotoSync.handle(context, contact.copy(trustLevel = ContactTrustLevel.BLOCKED),
                ProfilePhotoBody.format(ProfilePhotoBody.Update(3, jpeg)))
            assertFalse("Blocked contacts cannot update avatars", ContactPhotos.hasPhoto(context, contact.id))
        } finally {
            ContactPhotos.remove(context, contact.id)
            kotlinx.coroutines.runBlocking { contacts.deleteContact(contact.id) }
        }
    }
}
