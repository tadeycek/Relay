package com.relay.app.messaging

import android.content.Context
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.groups.GroupProtocol
import com.relay.app.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Verified contacts only, through the existing encrypted/Tor-aware transport. No probes or durable retries. */
object PresenceSignals {
    const val CREATE = "CREATE TABLE contact_activity (peer TEXT PRIMARY KEY,last_at INTEGER NOT NULL,active INTEGER NOT NULL)"
    val observed = MutableStateFlow<Map<String,PresenceBody.Status>>(emptyMap())
    @Volatile private var visible = false
    private val sendLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastTime = 0L
    private var advertised = false
    @Synchronized fun load(context: Context) {
        observed.value = MessagingDb.get(context).readableDatabase.rawQuery("SELECT peer,last_at,active FROM contact_activity",null).use { c ->
            buildMap { while(c.moveToNext()) put(c.getString(0),PresenceBody.Status(c.getLong(1),c.getInt(2)!=0)) } }
    }
    fun visible(context: Context, value: Boolean) {
        visible=value
        MessagingRuntime.launchIo { broadcast(context) }
    }
    suspend fun heartbeat(context: Context) { if(visible) broadcast(context) }
    private suspend fun broadcast(context: Context) {
        sendLock.lock()
        try {
            val prefs=com.relay.app.util.RelayPreferences(context)
            val active=visible && prefs.shareActivity
            if(!active && !advertised) return
            val transport=Transports.get(context)
            if(transport.status.value!=TransportStatus.ONLINE) return
            val now=maxOf(System.currentTimeMillis(),lastTime+1); lastTime=now
            val status=PresenceBody.Status(now,active)
            advertised=active
            ContactRepository(MessagingDb.get(context)).getAllContacts().filter(GroupProtocol::eligible).forEach { contact ->
                // Recheck the gate before each recipient; never send an active lease after locking/backgrounding.
                if(status.active && (!visible || !com.relay.app.util.RelayPreferences(context).shareActivity)) return@forEach
                withTimeoutOrNull(2500) { transport.send(contact.nostrPubkey!!,
                    RelayPayload(PayloadCodec.newId(),now,PresenceBody.format(status)),contact.relayHints) }
            }
        } finally { sendLock.unlock() }
    }
    @Synchronized fun handle(context: Context, contact: Contact?, body: String): Boolean {
        if(!body.startsWith(PresenceBody.PREFIX)) return false
        if(contact==null || !GroupProtocol.eligible(contact)) return true
        val status=PresenceBody.parse(body,System.currentTimeMillis()) ?: return true
        val key=contact.nostrPubkey!!
        val sql=MessagingDb.get(context).writableDatabase
        sql.execSQL("INSERT INTO contact_activity(peer,last_at,active) VALUES(?,?,?) ON CONFLICT(peer) DO UPDATE SET last_at=excluded.last_at,active=excluded.active WHERE excluded.last_at>contact_activity.last_at",arrayOf(key,status.time,if(status.active) 1 else 0))
        val latest=sql.rawQuery("SELECT last_at,active FROM contact_activity WHERE peer=?",arrayOf(key)).use { c -> c.moveToFirst(); PresenceBody.Status(c.getLong(0),c.getInt(1)!=0) }
        observed.value = observed.value + (key to latest)
        return true
    }
}
