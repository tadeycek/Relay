package com.relay.app.calculator

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.data.model.GroupMessage
import com.relay.app.data.model.Message
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.data.repository.GroupRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.lab.MediaPickerFixtureActivity
import com.relay.app.media.ChatMediaSource
import com.relay.app.media.PickedChatMedia
import com.relay.app.messaging.Broadcasts
import com.relay.app.messaging.MessagingDb
import com.relay.app.ui.screens.chat.ChatViewModel
import com.relay.app.ui.screens.chat.ChatViewModelFactory
import com.relay.app.ui.screens.chat.GroupChatViewModel
import com.relay.app.ui.screens.chat.GroupChatViewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChatMediaRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun directAndGroupCatchUpAfterDetachAndKeepReceivingAfterRebind() {
        val db = MessagingDb.get(context)
        val contacts = ContactRepository(db)
        val contact = contacts.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "Refresh fixture")
        val groups = GroupRepository(db)
        val group = runBlocking { groups.insertGroup("Refresh group", emptyList()) }
        val store = ViewModelStore()
        lateinit var direct: ChatViewModel
        lateinit var shared: GroupChatViewModel
        var registered = false
        fun awaitBodies(body: String) = runBlocking {
            withTimeout(10_000) {
                direct.messages.first { rows -> rows.any { it.body == body } }
                shared.messages.first { rows -> rows.any { it.body == body } }
            }
        }
        fun insert(body: String) {
            MessageRepository(db).insertMessageSync(Message(contactId = contact.id, body = body))
            GroupMessageRepository(db).insertMessageSync(GroupMessage(groupId = group, body = body))
        }
        try {
            instrumentation.runOnMainSync {
                direct = ViewModelProvider(store, ChatViewModelFactory(contact.id, context.applicationContext as android.app.Application))[ChatViewModel::class.java]
                shared = ViewModelProvider(store, GroupChatViewModelFactory(group, context.applicationContext as android.app.Application))[GroupChatViewModel::class.java]
                direct.registerSmsUpdates(context)
                shared.registerUpdates(context)
                registered = true
            }
            runBlocking { withTimeout(10_000) { direct.loaded.first { it }; shared.loaded.first { it } } }
            instrumentation.runOnMainSync {
                direct.unregisterSmsUpdates(context)
                shared.unregisterUpdates(context)
                registered = false
            }
            insert("While detached")
            // This event happens before the restored conversation exists; binding must catch up itself.
            context.sendBroadcast(Intent(Broadcasts.PRIVATE_FOREGROUND).setPackage(context.packageName))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                direct.registerSmsUpdates(context)
                shared.registerUpdates(context)
                registered = true
            }
            awaitBodies("While detached")
            insert("Resume without disposal")
            instrumentation.runOnMainSync {
                com.relay.app.messaging.ActiveChat.contactId = -1
                com.relay.app.messaging.ActiveChat.groupId = -1
                direct.resume()
                shared.resume()
                assertEquals(contact.id, com.relay.app.messaging.ActiveChat.contactId)
                assertEquals(group, com.relay.app.messaging.ActiveChat.groupId)
            }
            awaitBodies("Resume without disposal")
            insert("After resume")
            context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName).putExtra("contact_id", contact.id))
            context.sendBroadcast(Intent("com.relay.app.NEW_GROUP_MESSAGE").setPackage(context.packageName).putExtra("group_id", group))
            awaitBodies("After resume")
            insert("Foreground catch-up")
            context.sendBroadcast(Intent(Broadcasts.PRIVATE_FOREGROUND).setPackage(context.packageName))
            awaitBodies("Foreground catch-up")
        } finally {
            instrumentation.runOnMainSync {
                if (registered) { direct.unregisterSmsUpdates(context); shared.unregisterUpdates(context) }
                store.clear()
            }
            runBlocking { groups.deleteGroup(group); contacts.deleteContact(contact.id) }
        }
    }

    @Test fun pickerKeepsOrderedSelectionsAcrossPrivacyHideReturnAndRecreation() {
        val photos = listOf(Uri.parse("content://fixture/photo-a"), Uri.parse("content://fixture/photo-b"))
        val video = Uri.parse("content://fixture/video-c")
        ActivityScenario.launch(MediaPickerFixtureActivity::class.java).use { scenario ->
            fun awaitFixture(reason: String, condition: (MediaPickerFixtureActivity) -> Boolean) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = try { condition(activity) } catch (_: UninitializedPropertyAccessException) { false }
                    }
                    if (ready) return
                    android.os.SystemClock.sleep(16)
                }
                fail(reason)
            }
            awaitFixture("Initial picker composition is ready") { it.bindings > 0 && it.picker.draftFor(7).isEmpty() }
            scenario.onActivity { it.picker.open(7, ChatMediaSource.PHOTO) }
            awaitFixture("Opening the picker disposes private content") { !it.privateVisible && it.unbindings > 0 }
            scenario.onActivity {
                assertFalse(it.privateVisible)
                assertTrue(it.unbindings > 0)
                it.returnSelection(photos)
            }
            awaitFixture("Photo results restore private content and selected drafts") {
                it.privateVisible && it.bindings > it.unbindings && it.picker.draftFor(7).map { item -> item.uri } == photos
            }
            var ids: List<String> = emptyList()
            scenario.onActivity {
                assertEquals(photos, it.picker.draftFor(7).map { item -> item.uri })
                ids = it.picker.draftFor(7).map { item -> item.selectionId }
                it.picker.open(7, ChatMediaSource.VIDEO)
            }
            awaitFixture("Second picker launch hides private content") { !it.privateVisible && it.bindings == it.unbindings }
            scenario.onActivity {
                assertEquals(photos, it.picker.draftFor(7).map { item -> item.uri })
                it.returnSelection(listOf(photos.first(), video))
            }
            awaitFixture("Video results append in order and restore private content") {
                it.privateVisible && it.bindings > it.unbindings && it.picker.draftFor(7).map { item -> item.uri } == photos + video
            }
            scenario.onActivity {
                assertEquals(photos + video, it.picker.draftFor(7).map { item -> item.uri })
                assertEquals(ids, it.picker.draftFor(7).take(2).map { item -> item.selectionId })
                it.picker.open(8, ChatMediaSource.PHOTO)
            }
            awaitFixture("Switching chats hides private content for the new picker") { !it.privateVisible && it.bindings == it.unbindings }
            scenario.onActivity {
                // Another conversation may select the same document while the first batch is staging.
                assertEquals(photos + video, it.picker.draftFor(7).map { item -> item.uri })
                it.returnSelection(listOf(photos.first()))
            }
            awaitFixture("Both conversations retain their selected documents") {
                it.privateVisible && it.bindings > it.unbindings && it.picker.draftFor(8).map { item -> item.uri } == listOf(photos.first())
            }
            scenario.recreate()
            awaitFixture("Recreated activity restores both conversation drafts") {
                it.bindings > 0 && it.picker.draftFor(7).map { item -> item.uri } == photos + video &&
                    it.picker.draftFor(8).map { item -> item.uri } == listOf(photos.first())
            }
            scenario.onActivity {
                assertEquals(photos + video, it.picker.draftFor(7).map { item -> item.uri })
                assertEquals(listOf(photos.first()), it.picker.draftFor(8).map { item -> item.uri })
                it.picker.remove(7, setOf(ids.first()))
            }
            awaitFixture("Removing one attachment preserves the other ordered drafts") {
                it.picker.draftFor(7).map { item -> item.uri } == listOf(photos.last(), video)
            }
            scenario.onActivity {
                assertEquals(listOf(photos.last(), video), it.picker.draftFor(7).map { item -> item.uri })
                assertEquals("Accepting an item in one chat retains the other chat's shared URI", photos.first(), it.picker.draftFor(8).single().uri)
                it.picker.remove(8, it.picker.draftFor(8).map { item -> item.selectionId }.toSet())
            }
            var before = 0
            scenario.onActivity { before = it.refreshes }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitFixture("Conversation refresh finishes on resume") { it.refreshes > before }
            scenario.onActivity { assertTrue("Conversation refreshes on resume", it.refreshes > before) }
        }
    }

    @Test fun attachmentBatchesKeepOrderStopAtFailureAndRetryWithoutDuplicatingAcceptedItems() {
        val db = MessagingDb.get(context)
        val contacts = ContactRepository(db)
        val contact = contacts.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "Batch fixture")
        contacts.markQrVerifiedSync(contact.id)
        contacts.setPublicKeySync(contact.id, com.relay.app.crypto.RelayCrypto.myPublicKeyBase64(context)!!)
        val group = com.relay.app.groups.SharedGroups.create(context, "Batch group", listOf(contact.id))
        val image = java.io.File(context.cacheDir, "batch-${java.util.UUID.randomUUID()}.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        bitmap.recycle()
        val store = ViewModelStore()
        lateinit var direct: ChatViewModel
        lateinit var shared: GroupChatViewModel
        fun selections() = listOf(
            PickedChatMedia(Uri.fromFile(image), "image/jpeg", java.util.UUID.randomUUID().toString()),
            PickedChatMedia(Uri.fromFile(java.io.File(context.cacheDir, "missing-${java.util.UUID.randomUUID()}.jpg")), "image/jpeg", java.util.UUID.randomUUID().toString()),
            PickedChatMedia(Uri.fromFile(image), "image/jpeg", java.util.UUID.randomUUID().toString()),
        )
        val directItems = selections()
        val groupItems = selections()
        val directAccepted = mutableListOf<String>()
        val groupAccepted = mutableListOf<String>()
        fun awaitFinished() = runBlocking {
            withTimeout(15_000) { direct.sending.first { !it }; shared.sending.first { !it } }
        }
        try {
            instrumentation.runOnMainSync {
                direct = ViewModelProvider(store, ChatViewModelFactory(contact.id, context.applicationContext as android.app.Application))[ChatViewModel::class.java]
                shared = ViewModelProvider(store, GroupChatViewModelFactory(group, context.applicationContext as android.app.Application))[GroupChatViewModel::class.java]
            }
            runBlocking { withTimeout(10_000) { direct.contact.first { it != null }; shared.loaded.first { it } } }
            instrumentation.runOnMainSync {
                direct.sendMedia(directItems, "Batch caption", context) { directAccepted.add(it.selectionId) }
                shared.sendMedia(context, groupItems, "Batch caption") { groupAccepted.add(it.selectionId) }
            }
            awaitFinished()
            assertEquals(listOf(directItems.first().selectionId), directAccepted)
            assertEquals(listOf(groupItems.first().selectionId), groupAccepted)
            assertEquals(1, runBlocking { MessageRepository(db).getMessages(contact.id) }.size)
            assertEquals(1, runBlocking { GroupMessageRepository(db).getMessages(group) }.size)
            instrumentation.runOnMainSync {
                direct.sendMedia(listOf(directItems.first(), directItems.last()), "Batch caption", context) {}
                shared.sendMedia(context, listOf(groupItems.first(), groupItems.last()), "Batch caption") {}
            }
            awaitFinished()
            val directRows = runBlocking { MessageRepository(db).getMessages(contact.id) }
            val groupRows = runBlocking { GroupMessageRepository(db).getMessages(group) }
            assertEquals("Direct retry progress: ${directItems.map { com.relay.app.media.PendingAttachments.progress.value[it.selectionId] }}",
                listOf(directItems.first().selectionId, directItems.last().selectionId), directRows.map { it.msgId })
            assertEquals("Group retry error: ${shared.error.value}; progress: ${groupItems.map { com.relay.app.media.PendingAttachments.progress.value[it.selectionId] }}",
                listOf(groupItems.first().selectionId, groupItems.last().selectionId), groupRows.map { it.eventId })
            assertEquals(listOf("Batch caption", ""), directRows.map { it.body })
            assertEquals(listOf("Batch caption", ""), groupRows.map { it.body })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            val savedFiles = runBlocking { MessageRepository(db).getMessages(contact.id).mapNotNull { it.mediaUri } +
                GroupMessageRepository(db).getMessages(group).mapNotNull { it.mediaUri } }
            db.writableDatabase.delete("pending_attachments", "(target=? AND is_group=0) OR (target=? AND is_group=1)",
                arrayOf(contact.id.toString(), group.toString()))
            savedFiles.forEach { java.io.File(it).delete() }
            image.delete()
            runBlocking { GroupRepository(db).deleteGroup(group); contacts.deleteContact(contact.id) }
        }
    }
}
