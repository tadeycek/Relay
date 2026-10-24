package com.relay.app.calculator

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.model.DeliveryState
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.groups.GroupDispatch
import com.relay.app.groups.SharedGroups
import com.relay.app.messaging.AndroidInnerEnvelope
import com.relay.app.messaging.MessagingDb
import com.relay.app.transport.Transports
import com.relay.app.ui.screens.chat.ChatViewModel
import com.relay.app.ui.screens.chat.ChatViewModelFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeliveryRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun aSlowErrorConsumerCannotKeepTheSendButtonBusy() = runBlocking {
        val db = MessagingDb.get(context)
        val contacts = ContactRepository(db)
        val contact = contacts.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "QA rejected send")
        contacts.setTrustLevel(contact.id, ContactTrustLevel.BLOCKED)
        val store = ViewModelStore()
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstError = CompletableDeferred<Unit>()
        val releaseError = CompletableDeferred<Unit>()
        try {
            lateinit var vm: ChatViewModel
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ChatViewModelFactory(contact.id, context.applicationContext as Application))
                    .get(ChatViewModel::class.java)
            }
            withTimeout(5_000) { vm.contact.first { it != null } }
            collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
                vm.toastMessage.collect { firstError.complete(Unit); releaseError.await() }
            }
            repeat(2) { attempt ->
                instrumentation.runOnMainSync { vm.sendText("QA draft stays $attempt", context) }
                withTimeout(2_000) { vm.sending.first { !it } }
                if (attempt == 0) withTimeout(2_000) { firstError.await() }
            }
            assertTrue(MessageRepository(db).getMessages(contact.id).isEmpty())
            assertNull(vm.acceptedDraft.value)
        } finally {
            releaseError.complete(Unit)
            collectorScope.cancel()
            instrumentation.runOnMainSync { store.clear() }
            contacts.deleteContact(contact.id)
        }
    }

    @Test fun slowGroupCryptoLeavesTheWriterAndNewSendsFreeAndRechecksBlocking() = runBlocking {
        val db = MessagingDb.get(context)
        val contacts = ContactRepository(db)
        val contact = contacts.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "QA slow sealing")
        contacts.markQrVerifiedSync(contact.id)
        contacts.setPublicKeySync(contact.id, com.relay.app.crypto.RelayCrypto.myPublicKeyBase64(context)!!)
        val transport = Transports.get(context)
        transport.stop()
        val executor = Executors.newFixedThreadPool(2)
        val cryptoStarted = CountDownLatch(1)
        val releaseCrypto = CountDownLatch(1)
        var group: Long? = null
        try {
            val id = SharedGroups.create(context, "QA preparation isolation", listOf(contact.id))
            group = id
            val event = UUID.randomUUID().toString()
            SharedGroups.send(context, id, "QA prepare first", event = event)
            val payload = db.readableDatabase.rawQuery(
                "SELECT payload_id FROM group_dispatch WHERE group_id=? AND event=? AND requires_source=1",
                arrayOf(id.toString(), event)).use { assertTrue(it.moveToFirst()); it.getString(0) }
            val dispatch = executor.submit {
                GroupDispatch.dispatch(context, db, payload) { key, body ->
                    cryptoStarted.countDown()
                    check(releaseCrypto.await(10, TimeUnit.SECONDS))
                    AndroidInnerEnvelope.seal(key, body)
                }
            }
            assertTrue(cryptoStarted.await(5, TimeUnit.SECONDS))
            val anotherSend = executor.submit {
                db.writableDatabase.execSQL("UPDATE contacts SET name=name WHERE _id=?", arrayOf(contact.id))
                checkNotNull(SharedGroups.send(context, id, "QA accepted while preparing"))
            }
            // This times out if either the writer transaction or the group monitor spans crypto.
            anotherSend.get(2, TimeUnit.SECONDS)
            contacts.setTrustLevel(contact.id, ContactTrustLevel.BLOCKED)
            releaseCrypto.countDown()
            dispatch.get(5, TimeUnit.SECONDS)
            db.readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE payload_id=?", arrayOf(payload)).use { assertFalse(it.moveToFirst()) }
            db.readableDatabase.rawQuery("SELECT state FROM group_delivery WHERE payload_id=?", arrayOf(payload)).use {
                assertTrue(it.moveToFirst()); assertEquals(DeliveryState.FAILED, it.getInt(0))
            }
        } finally {
            releaseCrypto.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            group?.let { db.writableDatabase.delete("groups", "_id=?", arrayOf(it.toString())) }
            contacts.deleteContact(contact.id)
            transport.start()
        }
    }

    @Test fun anInvalidProfileKeyDoesNotBlockOtherRecipients() = runBlocking {
        val db = MessagingDb.get(context)
        val contacts = ContactRepository(db)
        val bad = contacts.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "AAA QA bad key")
        val good = contacts.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "ZZZ QA good key")
        try {
            for (id in listOf(bad.id, good.id)) contacts.markQrVerifiedSync(id)
            contacts.setPublicKeySync(bad.id, "invalid-key")
            contacts.setPublicKeySync(good.id, com.relay.app.crypto.RelayCrypto.myPublicKeyBase64(context)!!)
            com.relay.app.media.ProfilePhotoSync.syncPending(context)
            fun queued(id: Long) = db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM outbox WHERE contact_id=?", arrayOf(id.toString())).use { it.moveToFirst(); it.getInt(0) }
            withTimeout(5_000) { while (queued(good.id) == 0) delay(50) }
            assertEquals(0, queued(bad.id))
        } finally {
            contacts.deleteContact(bad.id)
            contacts.deleteContact(good.id)
        }
    }

    @Test fun anInvalidKnownKeyCannotSilentlyFallBackToTheUnsealedBody() {
        try {
            AndroidInnerEnvelope.seal("invalid-key", "QA private body")
            fail("An invalid known key must abort instead of returning the body")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("encrypt"))
        }
    }
}
