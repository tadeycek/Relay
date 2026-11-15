package com.relay.app.media

import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.calculator.CalculatorOnly
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.ConversationNotifications
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.RetentionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID

/** Real encrypted database, local fixtures only: no media server or transport is contacted. */
class IncomingMediaQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: RelayDbHelper
    private lateinit var contact: Contact
    private val ids = mutableListOf<String>()
    private val suffix = UUID.randomUUID().toString().replace("-", "")
    private val gate = "incoming_media_gate_$suffix"
    private val rejection = "incoming_media_reject_$suffix"
    private val hash = MediaCrypto.sha256Hex(suffix.toByteArray())
    private val ref = MediaRef(
        url = "https://fixture.invalid/v2/media/$hash",
        sha256Hex = hash,
        keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        mime = "video/mp4",
        size = 3L * 1024 * 1024 * 1024,
        caption = "Durable receive fixture",
        version = 2,
    )

    @Before fun prepare() {
        assertFalse("Queue fixtures require normal messaging mode", CalculatorOnly.enabled(context))
        db = MessagingDb.get(context)
        val contacts = ContactRepository(db)
        val key = rust.nostr.sdk.Keys.generate().publicKey().toHex()
        val created = contacts.findOrCreateByNostrSync(key, "Incoming queue fixture")
        contacts.markQrVerifiedSync(created.id)
        contact = requireNotNull(contacts.getByIdSync(created.id))
        ConversationNotifications.setMuted(context, contact.id, false, true)
        // The process worker runs during instrumentation. Gate only this fixture's new jobs inside
        // their insertion transaction, so it cannot race the committed-state assertions or fetch.
        db.writableDatabase.execSQL("""CREATE TRIGGER $gate AFTER INSERT ON pending_media_receives
            WHEN NEW.contact_id=${contact.id} BEGIN
            UPDATE pending_media_receives SET next_at=9223372036854775807
            WHERE contact_id=NEW.contact_id AND payload_id=NEW.payload_id; END""")
        assertEquals(ref, MediaBody.parse(MediaBody.format(ref)))
    }

    @After fun clean() {
        if (!::db.isInitialized) return
        db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $rejection")
        db.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $gate")
        if (!::contact.isInitialized) return
        db.writableDatabase.delete("pending_media_receives", "contact_id=?", arrayOf(contact.id.toString()))
        ids.forEach { PendingAttachments.clearProgress(context, it) }
        db.writableDatabase.delete("message_deletions", "scope=?", arrayOf("direct:${contact.id}"))
        File(File(context.filesDir, "media-downloads"), "$hash.cipher").delete()
        runBlocking { ContactRepository(db).deleteContact(contact.id) }
        context.getSharedPreferences("conversation_notifications", android.content.Context.MODE_PRIVATE).edit()
            .remove("contact:${contact.id}").remove("epoch:contact:${contact.id}").commit()
    }

    private fun id() = UUID.randomUUID().toString().also { ids.add(it) }
    private fun count(table: String, id: String): Int {
        val column = if (table == "messages") "msg_id" else "payload_id"
        return db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE contact_id=? AND $column=?",
            arrayOf(contact.id.toString(), id)).use { it.moveToFirst(); it.getInt(0) }
    }

    @Test fun enqueueReturnsWithOneCommittedPlaceholderAndTheExactDurableJobBeforeCallerCanAck() {
        val payload = id()
        val sentAt = System.currentTimeMillis()
        MediaReceiver.enqueue(context, contact, payload, ref, sentAt)
        MediaReceiver.enqueue(context, contact, payload, ref, sentAt)
        assertFalse("The receiver must finish its transaction before returning", db.writableDatabase.inTransaction())
        assertEquals(1, count("messages", payload))
        assertEquals(1, count("pending_media_receives", payload))
        db.readableDatabase.rawQuery("SELECT type,body,timestamp,media_uri,is_sent,unread FROM messages WHERE contact_id=? AND msg_id=?",
            arrayOf(contact.id.toString(), payload)).use {
            assertTrue(it.moveToFirst())
            assertEquals(MessageType.VIDEO.name, it.getString(0))
            assertEquals(ref.caption, it.getString(1))
            assertEquals(sentAt, it.getLong(2))
            assertTrue("The placeholder must not claim downloaded media", it.isNull(3))
            assertEquals(0, it.getInt(4))
            assertEquals(1, it.getInt(5))
        }
        db.readableDatabase.rawQuery("SELECT ref,sent_at,attempts FROM pending_media_receives WHERE contact_id=? AND payload_id=?",
            arrayOf(contact.id.toString(), payload)).use {
            assertTrue(it.moveToFirst())
            assertEquals(MediaBody.format(ref), it.getString(0))
            assertEquals(ref, MediaBody.parse(it.getString(0)))
            assertEquals(sentAt, it.getLong(1))
            assertEquals(0, it.getInt(2))
        }
        assertEquals("Waiting to download", PendingAttachments.progress.value[payload]?.phase)
        assertEquals(ref.size, requireNotNull(PendingAttachments.progress.value[payload]).total)
    }

    @Test fun failedJobInsertionRollsBackThePlaceholderAndDoesNotReportAcceptance() {
        val payload = id()
        db.writableDatabase.execSQL("""CREATE TRIGGER $rejection BEFORE INSERT ON pending_media_receives
            WHEN NEW.contact_id=${contact.id} BEGIN SELECT RAISE(ABORT,'fixture queue insertion failed'); END""")
        assertThrows(Exception::class.java) {
            MediaReceiver.enqueue(context, contact, payload, ref, System.currentTimeMillis())
        }
        assertFalse(db.writableDatabase.inTransaction())
        assertEquals(0, count("messages", payload))
        assertEquals(0, count("pending_media_receives", payload))
        assertNull(PendingAttachments.progress.value[payload])
    }

    @Test fun unverifiedMediaCreatesOneTextNoticeAndNeverAReceiveJob() {
        val payload = id()
        val unverified = contact.copy(qrVerified = false)
        repeat(2) { MediaReceiver.enqueue(context, unverified, payload, ref, System.currentTimeMillis()) }
        assertEquals(1, count("messages", payload))
        assertEquals(0, count("pending_media_receives", payload))
        db.readableDatabase.rawQuery("SELECT type,body,media_uri FROM messages WHERE contact_id=? AND msg_id=?",
            arrayOf(contact.id.toString(), payload)).use {
            assertTrue(it.moveToFirst())
            assertEquals(MessageType.TEXT.name, it.getString(0))
            assertTrue(it.getString(1).contains("unverified"))
            assertTrue(it.isNull(2))
        }
        assertNull(PendingAttachments.progress.value[payload])
    }

    @Test fun deletedAndExpiredPayloadsNeverCreateAPlaceholderOrDownloadJob() {
        val deleted = id()
        val expired = id()
        db.writableDatabase.execSQL("INSERT INTO message_deletions(scope,author,event) VALUES(?,?,?)",
            arrayOf("direct:${contact.id}", contact.nostrPubkey!!, deleted))
        MediaReceiver.enqueue(context, contact, deleted, ref, System.currentTimeMillis())
        MediaReceiver.enqueue(context, contact, expired, ref, RetentionPolicy.cutoff(System.currentTimeMillis()) - 1)
        for (payload in listOf(deleted, expired)) {
            assertEquals(0, count("messages", payload))
            assertEquals(0, count("pending_media_receives", payload))
            assertNull(PendingAttachments.progress.value[payload])
        }
    }

    @Test fun deletingThePlaceholderDropsTheJobProgressAndPartialCipherWithoutDownloading() {
        val payload = id()
        MediaReceiver.enqueue(context, contact, payload, ref, System.currentTimeMillis())
        val partial = File(File(context.filesDir, "media-downloads").also { it.mkdirs() }, "$hash.cipher")
        partial.writeBytes(byteArrayOf(1, 2, 3))
        db.writableDatabase.delete("messages", "contact_id=? AND msg_id=?", arrayOf(contact.id.toString(), payload))
        // Only run the real drain when it cannot touch another test's ready receive job.
        val othersReady = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM pending_media_receives WHERE next_at<=? AND NOT(contact_id=? AND payload_id=?)",
            arrayOf(System.currentTimeMillis().toString(), contact.id.toString(), payload)).use { it.moveToFirst(); it.getInt(0) }
        assumeTrue("Another ready media job must not be drained by this local fixture", othersReady == 0)
        db.writableDatabase.execSQL("UPDATE pending_media_receives SET next_at=0 WHERE contact_id=? AND payload_id=?", arrayOf(contact.id, payload))
        MediaReceiver.drain(context)
        assertEquals(0, count("messages", payload))
        assertEquals(0, count("pending_media_receives", payload))
        assertNull(PendingAttachments.progress.value[payload])
        assertFalse(partial.exists())
    }
}
