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
            assertEquals(0xFF8E4BC4.toInt(), avatar.getPixel(158, 183))
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
