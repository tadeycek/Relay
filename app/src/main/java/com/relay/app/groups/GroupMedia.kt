package com.relay.app.groups

import android.content.ContentValues
import android.content.Context
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.media.*
import com.relay.app.messaging.MessagingDb
import java.io.File
import java.util.concurrent.Semaphore

/** Called only after authenticated, current group membership was checked. */
object GroupMedia {
    private val slots = Semaphore(2)
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun retryPending(context: Context) {
        if (com.relay.app.calculator.CalculatorOnly.enabled(context)) return
        val db = MessagingDb.get(context)
        val pending = db.readableDatabase.rawQuery("SELECT group_id,event_id,media_ref FROM group_messages WHERE media_ref IS NOT NULL AND media_uri IS NULL LIMIT 32", null).use { c ->
            buildList { while(c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
        }
        for ((group, event, body) in pending) {
            val state = SharedGroups.get(db, group) ?: continue
            val own = com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context)
            if (state.hidden || state.state.members.none { it.key == own }) continue
            MediaBody.parse(body)?.let { ref -> com.relay.app.messaging.MessagingRuntime.launchIo { download(context, group, event, ref) } }
        }
    }
    fun download(context: Context, group: Long, event: String, ref: MediaRef) {
        if (com.relay.app.calculator.CalculatorOnly.enabled(context)) return
        val token = "$group:$event"
        if (!active.add(token)) return
        slots.acquireUninterruptibly()
        var file: File? = null
        try {
            if (com.relay.app.calculator.CalculatorOnly.enabled(context)) return
            val db = MessagingDb.get(context)
            val groupState = SharedGroups.get(db, group) ?: return
            if (groupState.hidden || groupState.state.members.none { it.key == com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context) }) return
            if (!db.readableDatabase.rawQuery("SELECT 1 FROM group_messages WHERE group_id=? AND event_id=? AND media_uri IS NULL", arrayOf(group.toString(), event)).use { it.moveToFirst() }) return
            val client = blossomClientFor(context) ?: return
            val bytes = client.download(ref.url, ref.sha256Hex, MediaContainer.wrappedSize((ref.size + 28).toInt()).toLong()) ?: return
            val plain = MediaCrypto.decrypt(MediaContainer.unwrap(bytes) ?: bytes, ref.keyBase64) ?: return
            if (plain.size.toLong() != ref.size) return
            file = File(File(context.filesDir, "media").also { it.mkdirs() }, "group_${MediaCrypto.sha256Hex("$group:$event".toByteArray())}.enc")
            RelayFileCrypto.writeEncrypted(context, file, plain)
            synchronized(SharedGroups) {
                val changed = MessagingDb.get(context).writableDatabase.update("group_messages", ContentValues().apply {
                    put("media_uri", file.absolutePath)
                    putNull("media_ref")
                }, "group_id=? AND event_id=?", arrayOf(group.toString(), event))
                if (changed == 0) file.delete() // A deletion arrived during the download.
            }
            SharedGroups.notify(context, group)
        } catch (_: Exception) { file?.delete() } finally { slots.release(); active.remove(token) }
    }
}
