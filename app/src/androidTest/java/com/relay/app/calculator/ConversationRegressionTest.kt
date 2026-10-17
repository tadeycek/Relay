package com.relay.app.calculator

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.first

class ConversationRegressionTest {
    @Test fun muteCancelsAndSeparatesContactsAndGroupsWithoutReplayingOldMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation
            .grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val repo = ContactRepository(MessagingDb.get(context))
        val contact = repo.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "Mute test")
        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            ConversationNotifications.setMuted(context, contact.id, false, false)
            val before = ConversationNotifications.token(context, contact.id)
            MessageNotifier.notifyIncoming(context, contact, MessageType.TEXT, "Before mute")
            assertTrue(nm.activeNotifications.any { it.tag == "conversation:${contact.id}" })
            ConversationNotifications.setMuted(context, contact.id, false, true)
            assertFalse(nm.activeNotifications.any { it.tag == "conversation:${contact.id}" })
            assertNull(ConversationNotifications.token(context, contact.id))
            assertNotNull(ConversationNotifications.token(context, contact.id, true))
            MessageNotifier.notifyIncoming(context, contact, MessageType.IMAGE, "Muted image")
            assertFalse(nm.activeNotifications.any { it.tag == "conversation:${contact.id}" })
            ConversationNotifications.setMuted(context, contact.id, false, false)
            MessageNotifier.notifyIncoming(context, contact, MessageType.IMAGE, "Delayed download", before)
            assertFalse(nm.activeNotifications.any { it.tag == "conversation:${contact.id}" })
            MessageNotifier.notifyIncoming(context, contact, MessageType.TEXT, "Future message")
            assertTrue(nm.activeNotifications.any { it.tag == "conversation:${contact.id}" })
        } finally {
            MessageNotifier.cancel(context, contact.id)
            kotlinx.coroutines.runBlocking { repo.deleteContact(contact.id) }
        }
    }

    @Test fun localAttachmentAcceptanceNeedsNoUploadAndPersistsExactlyOneBubble() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = MessagingDb.get(context)
        val repo = ContactRepository(db)
        val contact = repo.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "Local queue")
        val file = java.io.File(context.cacheDir, "local-acceptance.jpg")
        file.writeBytes(byteArrayOf(-1,-40,-1,1,2,3))
        try {
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            com.relay.app.media.PendingAttachments.stage(context, contact.id, false, file, "image/jpeg", "Caption")
            val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            android.util.Log.i("RelayAcceptanceTiming", "local attachment acceptance: $elapsed ms")
            val messages = kotlinx.coroutines.runBlocking { com.relay.app.data.repository.MessageRepository(db).getMessages(contact.id) }
            assertEquals(1, messages.size)
            assertEquals(com.relay.app.data.model.DeliveryState.QUEUED, messages.single().deliveryState)
            assertTrue(java.io.File(messages.single().mediaUri!!).isFile)
            db.readableDatabase.rawQuery("SELECT id FROM pending_attachments WHERE target=? AND is_group=0", arrayOf(contact.id.toString())).use {
                assertTrue(it.moveToFirst()); assertEquals(messages.single().msgId, it.getString(0)); assertFalse(it.moveToNext())
            }
        } finally {
            db.writableDatabase.delete("pending_attachments", "target=? AND is_group=0", arrayOf(contact.id.toString()))
            file.delete()
            kotlinx.coroutines.runBlocking { repo.deleteContact(contact.id) }
        }
    }
    @Test fun directAndGroupTextAcceptOnceWithoutWaitingForDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as android.app.Application
        val db = MessagingDb.get(context)
        val repo = ContactRepository(db)
        val contact = repo.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "Submission test")
        repo.markQrVerifiedSync(contact.id)
        repo.setPublicKeySync(contact.id, com.relay.app.crypto.RelayCrypto.myPublicKeyBase64(context)!!)
        val store = androidx.lifecycle.ViewModelStore()
        var group: Long? = null
        try {
            lateinit var direct: com.relay.app.ui.screens.chat.ChatViewModel
            instrumentation.runOnMainSync {
                direct = androidx.lifecycle.ViewModelProvider(store,
                    com.relay.app.ui.screens.chat.ChatViewModelFactory(contact.id, app))
                    .get(com.relay.app.ui.screens.chat.ChatViewModel::class.java)
            }
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000) { direct.contact.first { it != null } }
            }
            val accepted = java.util.concurrent.CountDownLatch(1)
            val callbacks = java.util.concurrent.atomic.AtomicInteger()
            val started = android.os.SystemClock.elapsedRealtimeNanos()
            instrumentation.runOnMainSync {
                repeat(2) { direct.sendText("Accepted once", context) { callbacks.incrementAndGet(); accepted.countDown() } }
            }
            assertTrue(accepted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
            assertEquals(1, kotlinx.coroutines.runBlocking { com.relay.app.data.repository.MessageRepository(db).getMessages(contact.id) }.size)
            android.util.Log.i("RelayAcceptanceTiming", "direct text acceptance: ${(android.os.SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0} ms")
            group = com.relay.app.groups.SharedGroups.create(context, "Submission group", listOf(contact.id))
            lateinit var groupVm: com.relay.app.ui.screens.chat.GroupChatViewModel
            instrumentation.runOnMainSync {
                groupVm = androidx.lifecycle.ViewModelProvider(store,
                    com.relay.app.ui.screens.chat.GroupChatViewModelFactory(group!!, app))
                    .get(com.relay.app.ui.screens.chat.GroupChatViewModel::class.java)
            }
            val groupAccepted = java.util.concurrent.CountDownLatch(1)
            val groupCallbacks = java.util.concurrent.atomic.AtomicInteger()
            val groupStarted = android.os.SystemClock.elapsedRealtimeNanos()
            instrumentation.runOnMainSync {
                repeat(2) { groupVm.sendText("Group accepted once", context) { groupCallbacks.incrementAndGet(); groupAccepted.countDown() } }
            }
            assertTrue(groupAccepted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, groupCallbacks.get())
            assertEquals(1, kotlinx.coroutines.runBlocking { com.relay.app.data.repository.GroupMessageRepository(db).getMessages(group!!) }.size)
            android.util.Log.i("RelayAcceptanceTiming", "group text acceptance: ${(android.os.SystemClock.elapsedRealtimeNanos()-groupStarted)/1_000_000.0} ms")
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            group?.let { kotlinx.coroutines.runBlocking { com.relay.app.data.repository.GroupRepository(db).deleteGroup(it) } }
            kotlinx.coroutines.runBlocking { repo.deleteContact(contact.id) }
        }
    }

}
