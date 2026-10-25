package com.relay.app.desktop

import com.relay.app.groups.GroupProtocol
import com.relay.app.linking.GroupRosterSync
import com.relay.app.transport.mailbox.MailboxMessage
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64

/**
 * One conversation row in the local encrypted database.
 */
data class DesktopConversation(
    val id: String,
    val peer: String,
    val title: String,
    val updatedAt: Long,
)

/**
 * One decrypted text message. [id] is the RelayPayload id, so redelivery of the same envelope is
 * inserted once. [outgoing] distinguishes this install's messages from the peer's.
 */
data class DesktopMessage(
    val id: String,
    val conversationId: String,
    val sender: String,
    val body: String,
    val ts: Long,
    val outgoing: Boolean,
)

/**
 * Local encrypted message store for the desktop client.
 *
 * Uses the Willena sqlite-jdbc fork, which bundles SQLite3 Multiple Ciphers and supports
 * SQLCipher-compatible modes. The database key is a 256-bit master key from [SecretStore], passed
 * as a passphrase (base64 text). WAL is enabled; SQLCipher encrypts the main database, the WAL and
 * the rollback journal. The schema is versioned through `PRAGMA user_version`.
 *
 * Thread-safety: one connection guarded by [lock]. Adequate for the single-window desktop MVP;
 * a later WP can move to a connection pool if background work grows.
 */
class DesktopMessageStore(
    private val databaseFile: Path,
    secretStore: SecretStore,
) : AutoCloseable, RawPacketStore, SyncStore {

    private val lock = Any()
    private val key: String = Base64.getEncoder().encodeToString(secretStore.getOrCreateKey(DATABASE_KEY_ID))
    private val connection: Connection

    init {
        DesktopPaths.secureDirectory(databaseFile.parent)
        // The driver opens the database and checks sqlite_master during connection setup. An
        // encrypted database must therefore receive its key and cipher through SQLiteConfig,
        // before that check; manual PRAGMAs afterwards are too late and fail with SQLITE_NOTADB.
        val config = org.sqlite.SQLiteConfig().apply {
            setPragma(org.sqlite.SQLiteConfig.Pragma.CIPHER, "sqlcipher")
            setPragma(org.sqlite.SQLiteConfig.Pragma.KEY, key)
            setPragma(org.sqlite.SQLiteConfig.Pragma.BUSY_TIMEOUT, "5000")
            setPragma(org.sqlite.SQLiteConfig.Pragma.JOURNAL_MODE, "WAL")
            setPragma(org.sqlite.SQLiteConfig.Pragma.SYNCHRONOUS, "FULL")
            setPragma(org.sqlite.SQLiteConfig.Pragma.FOREIGN_KEYS, "ON")
        }
        connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath()}", config.toProperties())
        migrate()
        ensureGroupHiddenColumn()
        createSyncTables()
        connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS file_transfers(id TEXT PRIMARY KEY, record TEXT NOT NULL)") }
        connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS file_sync_refs(wire TEXT PRIMARY KEY, file_id TEXT NOT NULL, media TEXT NOT NULL)") }
        connection.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS media_transfers(id TEXT PRIMARY KEY, record TEXT NOT NULL)") }
        secureFiles()
    }

    fun schemaVersion(): Int = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun ensureConversation(peer: String, title: String = peer): DesktopConversation = synchronized(lock) {
        val now = System.currentTimeMillis()
        connection.prepareStatement(
            "INSERT INTO conversations(id, peer, title, updated_at) VALUES(?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET title = excluded.title",
        ).use { ps ->
            ps.setString(1, peer)
            ps.setString(2, peer)
            ps.setString(3, title)
            ps.setLong(4, now)
            ps.executeUpdate()
        }
        conversation(peer) ?: error("conversation row disappeared")
    }

    fun conversation(peer: String): DesktopConversation? = synchronized(lock) {
        connection.prepareStatement("SELECT id, peer, title, updated_at FROM conversations WHERE id = ?").use { ps ->
            ps.setString(1, peer)
            ps.executeQuery().use { rs ->
                if (rs.next()) DesktopConversation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))
                else null
            }
        }
    }

    fun conversations(): List<DesktopConversation> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT id, peer, title, updated_at FROM conversations WHERE NOT EXISTS " +
                    "(SELECT 1 FROM group_rosters g WHERE g.conversation_id = conversations.id AND g.hidden = 1) ORDER BY updated_at DESC, id ASC").use { rs ->
                buildList {
                    while (rs.next()) add(DesktopConversation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
                }
            }
        }
    }

    /** Inserts [message] once by payload id. Returns true when this call inserted it. */
    fun insertMessage(message: DesktopMessage): Boolean = synchronized(lock) {
        registerFile(message)
        registerMedia(message)
        // Never retitle an existing conversation: the rail title is owned by the contact import or the
        // group roster, and a message arriving must not replace "Jane" or "Design" with a raw id.
        connection.prepareStatement("INSERT INTO conversations(id, peer, title, updated_at) VALUES(?, ?, ?, ?) ON CONFLICT(id) DO NOTHING").use { ps ->
            ps.setString(1, message.conversationId)
            ps.setString(2, message.conversationId)
            ps.setString(3, message.conversationId)
            ps.setLong(4, message.ts)
            ps.executeUpdate()
        }
        connection.autoCommit = false
        try {
            val inserted = connection.prepareStatement(
                "INSERT OR IGNORE INTO messages(id, conversation_id, sender, body, ts, outgoing) VALUES(?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, message.id)
                ps.setString(2, message.conversationId)
                ps.setString(3, message.sender)
                ps.setString(4, message.body)
                ps.setLong(5, message.ts)
                ps.setInt(6, if (message.outgoing) 1 else 0)
                ps.executeUpdate() == 1
            }
            connection.prepareStatement("UPDATE conversations SET updated_at = ? WHERE id = ?").use { ps ->
                ps.setLong(1, message.ts)
                ps.setString(2, message.conversationId)
                ps.executeUpdate()
            }
            connection.commit()
            secureFiles()
            inserted
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun messages(conversationId: String, limit: Int = 200): List<DesktopMessage> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT id, conversation_id, sender, body, ts, outgoing FROM messages " +
                "WHERE conversation_id = ? ORDER BY ts DESC, rowid DESC LIMIT ?",
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(DesktopMessage(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getInt(6) == 1))
                    }
                }.asReversed()
            }
        }
    }

    /** Newest row of a conversation, for the rail preview and its ordering. */
    fun lastMessage(conversationId: String): DesktopMessage? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT id, conversation_id, sender, body, ts, outgoing FROM messages " +
                "WHERE conversation_id = ? ORDER BY ts DESC, rowid DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs ->
                if (rs.next()) DesktopMessage(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getInt(6) == 1)
                else null
            }
        }
    }

    fun countMessages(): Long = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM messages").use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }

    // ---- raw mailbox packets: stored before ack, deleted only after the message is committed ----

    /** Inserts raw envelopes and returns the seqs newly stored (duplicates ignored). */
    override fun store(messages: List<MailboxMessage>): List<Long> = synchronized(lock) {
        if (messages.isEmpty()) return emptyList()
        connection.autoCommit = false
        try {
            val fresh = mutableListOf<Long>()
            connection.prepareStatement("INSERT OR IGNORE INTO mailbox_raw(seq, envelope) VALUES(?, ?)").use { ps ->
                for (m in messages) {
                    ps.setLong(1, m.seq)
                    ps.setString(2, m.envelope)
                    if (ps.executeUpdate() == 1) fresh += m.seq
                }
            }
            connection.commit()
            secureFiles()
            fresh
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun pending(): List<MailboxMessage> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT seq, envelope FROM mailbox_raw ORDER BY seq ASC").use { rs ->
                buildList {
                    while (rs.next()) add(MailboxMessage(rs.getLong(1), rs.getString(2)))
                }
            }
        }
    }

    override fun delete(seq: Long): Boolean = synchronized(lock) {
        connection.prepareStatement("DELETE FROM mailbox_raw WHERE seq = ?").use { ps ->
            ps.setLong(1, seq)
            ps.executeUpdate() == 1
        }
    }

    // ---- sent-copy sync (WP05-AC2): outbox, cursor, atomic apply ------------------------------

    /**
     * The outbox is created with `IF NOT EXISTS` rather than a `user_version` bump, so databases
     * written by earlier builds open unchanged and the schema stays at version 2. The table and the
     * sync cursor (`meta` row `sync.cursor`) live in the same encrypted database as the messages.
     */
    private fun createSyncTables() {
        synchronized(lock) {
            connection.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE IF NOT EXISTS sync_outbox(" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, wire TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }
    }

    override fun enqueueSent(message: DesktopMessage, wires: List<String>, now: Long): Boolean = synchronized(lock) {
        ensureConversation(message.conversationId)
        connection.autoCommit = false
        try {
            val inserted = insertMessageRow(message)
            // A duplicate of an existing row must not queue a second copy for the other devices.
            if (inserted) {
                connection.prepareStatement("INSERT INTO sync_outbox(wire, created_at) VALUES(?, ?)").use { ps ->
                    for (wire in wires) {
                        ps.setString(1, wire)
                        ps.setLong(2, now)
                        ps.executeUpdate()
                    }
                }
            }
            connection.commit()
            secureFiles()
            inserted
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun outbox(limit: Int): List<OutboxEntry> = synchronized(lock) {
        connection.prepareStatement("SELECT id, wire, created_at, attempts FROM sync_outbox ORDER BY id ASC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(OutboxEntry(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4), fileSyncMedia(rs.getString(2)))) }
            }
        }
    }

    private fun fileSyncMedia(wire: String): List<String> = connection.prepareStatement("SELECT media FROM file_sync_refs WHERE wire=?").use { ps ->
        ps.setString(1, wire); ps.executeQuery().use { rs -> if (rs.next()) org.json.JSONArray(rs.getString(1)).let { a -> List(a.length()) { a.getString(it) } } else emptyList() }
    }

    override fun outboxDone(id: Long) {
        synchronized(lock) {
            connection.prepareStatement("DELETE FROM sync_outbox WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
    }

    /**
     * A file copy that will never be published (expired or permanently rejected) must lose its reference
     * row: [refreshFileSyncPublication] treats "no outbox row" as "published", and a dropped copy that
     * still had refs would silently mark the file synced, letting the sender confirm before grants exist.
     */
    fun dropFileSync(wire: String) = synchronized(lock) {
        connection.prepareStatement("DELETE FROM file_sync_refs WHERE wire = ?").use { ps -> ps.setString(1, wire); ps.executeUpdate() }
    }

    fun refreshFileSyncPublication() = synchronized(lock) {
        connection.createStatement().use { st -> st.executeQuery("SELECT DISTINCT f.file_id FROM file_sync_refs f WHERE NOT EXISTS (SELECT 1 FROM file_sync_refs r JOIN sync_outbox o ON o.wire=r.wire WHERE r.file_id=f.file_id)").use { rs ->
            while (rs.next()) {
                val id = rs.getString(1)
                fileRecord(id)?.let { saveFile(it.copy(syncPublished = true)) }
                mediaRecord(id)?.let { saveMedia(it.copy(syncPublished = true)) }
            }
        } }
    }

    override fun outboxAttempt(id: Long) {
        synchronized(lock) {
            connection.prepareStatement("UPDATE sync_outbox SET attempts = attempts + 1 WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
    }

    override fun syncCursor(): Long = synchronized(lock) { meta(SYNC_CURSOR)?.toLongOrNull() ?: 0L }

    /**
     * Inserts [message] (if any) and moves the sync cursor to [cursor] in ONE transaction, so the
     * cursor can never be ahead of a message that was not committed. The cursor never moves back.
     * An existing row with the same payload id (received or sent) is kept: the result is
     * [SyncCommit.MERGED] and no second row appears.
     */
    override fun commitSync(message: DesktopMessage?, cursor: Long): SyncCommit = synchronized(lock) {
        if (message != null) ensureConversation(message.conversationId)
        connection.autoCommit = false
        try {
            var result = SyncCommit.NONE
            if (message != null) {
                result = if (insertMessageRow(message)) SyncCommit.INSERTED else SyncCommit.MERGED
            }
            if (cursor > syncCursor()) setMeta(SYNC_CURSOR, cursor.toString())
            connection.commit()
            secureFiles()
            result
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /** Test seam: runs inside [commitGroupSync] after the roster write and before the cursor write. */
    @Volatile internal var afterRosterWriteHook: (() -> Unit)? = null

    override fun commitGroupSync(roster: GroupRosterSync.Roster, cursor: Long, now: Long): GroupCommit = synchronized(lock) {
        val st = roster.state
        val conv = groupConversationId(st.owner, st.id)
        connection.autoCommit = false
        try {
            val conflict = connection.prepareStatement("SELECT 1 FROM group_rosters WHERE shared_id = ? AND owner <> ?").use { ps ->
                ps.setString(1, st.id); ps.setString(2, st.owner); ps.executeQuery().use { it.next() }
            }
            var changed = false
            if (!conflict) {
                var storedRevision = -1L; var storedAvatarRevision = -1L; var storedHidden = false; var exists = false
                connection.prepareStatement("SELECT revision, avatar_revision, hidden FROM group_rosters WHERE conversation_id = ?").use { ps ->
                    ps.setString(1, conv)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) { exists = true; storedRevision = rs.getLong(1); storedAvatarRevision = rs.getLong(2); storedHidden = rs.getInt(3) != 0 }
                    }
                }
                val members = JSONArray().apply {
                    for (m in st.members) put(JSONObject().put("key", m.key).put("name", m.name).put("inner", m.inner).put("relays", JSONArray(m.relays)))
                }
                var retitle = false
                if (!exists) {
                    connection.prepareStatement(
                        "INSERT INTO group_rosters(conversation_id, owner, shared_id, name, revision, members, avatar_revision, updated_at, hidden) VALUES(?, ?, ?, ?, ?, ?, -1, ?, ?)",
                    ).use { ps ->
                        ps.setString(1, conv); ps.setString(2, st.owner); ps.setString(3, st.id); ps.setString(4, st.name)
                        ps.setLong(5, st.revision); ps.setString(6, members.toString()); ps.setLong(7, now); ps.setInt(8, if (roster.tombstone) 1 else 0)
                        ps.executeUpdate()
                    }
                    changed = true; retitle = true
                } else if (st.revision > storedRevision) {
                    connection.prepareStatement(
                        "UPDATE group_rosters SET name = ?, revision = ?, members = ?, updated_at = ?, hidden = ? WHERE conversation_id = ? AND revision < ?",
                    ).use { ps ->
                        ps.setString(1, st.name); ps.setLong(2, st.revision); ps.setString(3, members.toString()); ps.setLong(4, now)
                        ps.setInt(5, if (roster.tombstone) 1 else 0); ps.setString(6, conv); ps.setLong(7, st.revision)
                        changed = ps.executeUpdate() == 1
                    }
                    retitle = changed
                } else if (roster.tombstone && st.revision == storedRevision && !storedHidden) {
                    // Same revision, but we left or were removed: hide only (a tombstone never un-hides).
                    connection.prepareStatement("UPDATE group_rosters SET hidden = 1 WHERE conversation_id = ?").use { ps ->
                        ps.setString(1, conv); changed = ps.executeUpdate() == 1
                    }
                }
                if (retitle) {
                    connection.prepareStatement(
                        "INSERT INTO conversations(id, peer, title, updated_at) VALUES(?, '', ?, ?) ON CONFLICT(id) DO UPDATE SET title = excluded.title",
                    ).use { ps -> ps.setString(1, conv); ps.setString(2, st.name); ps.setLong(3, now); ps.executeUpdate() }
                }
                if (roster.avatarRevision > storedAvatarRevision && validDesktopAvatar(roster.avatar)) {
                    connection.prepareStatement("UPDATE group_rosters SET avatar = ?, avatar_revision = ? WHERE conversation_id = ? AND avatar_revision < ?").use { ps ->
                        ps.setBytes(1, roster.avatar); ps.setLong(2, roster.avatarRevision); ps.setString(3, conv); ps.setLong(4, roster.avatarRevision)
                        if (ps.executeUpdate() == 1) changed = true
                    }
                }
            }
            afterRosterWriteHook?.invoke()
            if (cursor > syncCursor()) setMeta(SYNC_CURSOR, cursor.toString())
            connection.commit()
            secureFiles()
            if (conflict) GroupCommit.CONFLICT else if (changed) GroupCommit.APPLIED else GroupCommit.STALE
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun mediaSendBodyOrNull(peer: String, id: String): String? = synchronized(lock) { meta("file.send.$peer.$id") }

    fun mediaSendBody(peer: String, id: String, create: () -> String): String = synchronized(lock) {
        val key = "file.send.$peer.$id"
        meta(key) ?: create().also { setMeta(key, it); secureFiles() }
    }

    /** Encrypted durable file journal. Manifest keys never enter a sidecar JSON file. */
    fun fileRecord(id: String): DesktopFileRecord? = synchronized(lock) {
        connection.prepareStatement("SELECT record FROM file_transfers WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) DesktopFileRecord.decode(rs.getString(1)) else null }
        }
    }

    fun fileRecords(): List<DesktopFileRecord> = synchronized(lock) {
        connection.createStatement().use { st -> st.executeQuery("SELECT record FROM file_transfers").use { rs ->
            buildList { while (rs.next()) add(DesktopFileRecord.decode(rs.getString(1))) }
        } }
    }

    fun saveFile(record: DesktopFileRecord) = synchronized(lock) {
        connection.prepareStatement("INSERT INTO file_transfers(id,record) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET record=excluded.record").use { ps ->
            ps.setString(1, record.id); ps.setString(2, record.encode()); ps.executeUpdate()
        }
        secureFiles()
    }


    // ---- WP08 photos and videos ----------------------------------------------------------------

    /** Encrypted media journal. The media key lives in `body`, so it never leaves this database. */
    fun mediaRecord(id: String): DesktopMediaRecord? = synchronized(lock) {
        connection.prepareStatement("SELECT record FROM media_transfers WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) DesktopMediaRecord.decode(rs.getString(1)) else null }
        }
    }

    fun mediaRecords(): List<DesktopMediaRecord> = synchronized(lock) {
        connection.createStatement().use { st -> st.executeQuery("SELECT record FROM media_transfers").use { rs ->
            buildList { while (rs.next()) add(DesktopMediaRecord.decode(rs.getString(1))) }
        } }
    }

    fun saveMedia(record: DesktopMediaRecord) = synchronized(lock) {
        connection.prepareStatement("INSERT INTO media_transfers(id,record) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET record=excluded.record").use { ps ->
            ps.setString(1, record.id); ps.setString(2, record.encode()); ps.executeUpdate()
        }
        secureFiles()
    }

    /** A received/synced TYPE:MEDIA row gets its journal entry before the cursor can pass it. */
    private fun registerMedia(message: DesktopMessage) {
        if (!message.body.startsWith("TYPE:MEDIA|") || message.conversationId.startsWith("group:")) return
        if (com.relay.app.media.MediaBody.parse(message.body) == null || mediaRecord(message.id) != null) return
        saveMedia(DesktopMediaRecord(message.id, message.conversationId, message.sender, message.ts, message.outgoing, message.body, "QUEUED"))
    }

    /** Outgoing media: the journal row and the visible bubble commit together or not at all. */
    fun saveMediaWithMessage(record: DesktopMediaRecord, message: DesktopMessage) = synchronized(lock) {
        // Never retitle an existing conversation (ensureConversation would overwrite a contact's name).
        connection.prepareStatement("INSERT INTO conversations(id, peer, title, updated_at) VALUES(?, ?, ?, ?) ON CONFLICT(id) DO NOTHING").use { ps ->
            ps.setString(1, message.conversationId); ps.setString(2, message.conversationId); ps.setString(3, message.conversationId); ps.setLong(4, message.ts); ps.executeUpdate()
        }
        connection.autoCommit = false
        try {
            saveMedia(record)
            insertMessageRow(message)
            connection.commit()
            secureFiles()
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /** Replaces a deleted bubble's body so the key and server reference are gone but replay cannot re-insert it. */
    fun tombstoneMessage(id: String, text: String) = synchronized(lock) {
        connection.prepareStatement("UPDATE messages SET body=? WHERE id=?").use { ps -> ps.setString(1, text); ps.setString(2, id); ps.executeUpdate() }
        secureFiles()
    }

    fun deleteMessage(id: String): Boolean = synchronized(lock) {
        val removed = connection.prepareStatement("DELETE FROM messages WHERE id=?").use { ps -> ps.setString(1, id); ps.executeUpdate() > 0 }
        connection.prepareStatement("DELETE FROM media_transfers WHERE id=?").use { ps -> ps.setString(1, id); ps.executeUpdate() }
        connection.prepareStatement("DELETE FROM file_sync_refs WHERE file_id=?").use { ps -> ps.setString(1, id); ps.executeUpdate() }
        secureFiles()
        removed
    }

    fun messageExists(id: String): Boolean = synchronized(lock) {
        connection.prepareStatement("SELECT 1 FROM messages WHERE id=?").use { ps -> ps.setString(1, id); ps.executeQuery().use { it.next() } }
    }

    /** [now] is injectable so tests can age a queued copy without waiting. */
    fun queueMediaSync(id: String, wires: List<String>, media: List<String>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val record = mediaRecord(id) ?: error("missing media")
        if (record.syncQueued) return@synchronized
        require(wires.isNotEmpty()) { "No SENT copy" }
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT INTO sync_outbox(wire,created_at) VALUES(?,?)").use { ps ->
                for (wire in wires) { ps.setString(1, wire); ps.setLong(2, now); ps.executeUpdate() }
            }
            connection.prepareStatement("INSERT OR IGNORE INTO file_sync_refs(wire,file_id,media) VALUES(?,?,?)").use { ps ->
                for (wire in wires) { ps.setString(1, wire); ps.setString(2, id); ps.setString(3, org.json.JSONArray(media).toString()); ps.executeUpdate() }
            }
            saveMedia(record.copy(syncQueued = true))
            connection.commit()
        } catch (e: Exception) { runCatching { connection.rollback() }; throw e }
        finally { connection.autoCommit = true }
    }

    /** File row exists before a message/cursor can become visible. Replay repairs either crash boundary. */
    private fun registerFile(message: DesktopMessage) {
        if (!message.body.startsWith("TYPE:FILE|") || message.conversationId.startsWith("group:")) return
        val manifest = com.relay.app.files.FileManifest.fromAuthenticatedPayload(
            com.relay.app.transport.RelayPayload(message.id, message.ts, message.body), { true }) ?: return
        if (fileRecord(message.id) == null) saveFile(DesktopFileRecord(message.id, message.conversationId,
            message.sender, message.ts, message.outgoing, message.body, "QUEUED", List(manifest.body.parts.size) { false },
            List(manifest.body.parts.size) { false }))
    }

    /** [now] is injectable so tests can age a queued copy without waiting. */
    fun queueFileSync(id: String, wires: List<String>, media: List<String>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val record = fileRecord(id) ?: error("missing file")
        if (record.syncQueued) return@synchronized
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT INTO sync_outbox(wire,created_at) VALUES(?,?)").use { ps ->
                for (wire in wires) { ps.setString(1, wire); ps.setLong(2, now); ps.executeUpdate() }
            }
            require(wires.isNotEmpty()) { "No SENT copy" }
            connection.prepareStatement("INSERT OR IGNORE INTO file_sync_refs(wire,file_id,media) VALUES(?,?,?)").use { ps ->
                for (wire in wires) { ps.setString(1, wire); ps.setString(2, id); ps.setString(3, org.json.JSONArray(media).toString()); ps.executeUpdate() }
            }
            saveFile(record.copy(syncQueued = true))
            connection.commit()
        } catch (e: Exception) { connection.rollback(); throw e }
        finally { connection.autoCommit = true }
    }

    private fun insertMessageRow(message: DesktopMessage): Boolean {
        registerFile(message)
        registerMedia(message)
        val inserted = connection.prepareStatement(
            "INSERT OR IGNORE INTO messages(id, conversation_id, sender, body, ts, outgoing) VALUES(?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, message.id)
            ps.setString(2, message.conversationId)
            ps.setString(3, message.sender)
            ps.setString(4, message.body)
            ps.setLong(5, message.ts)
            ps.setInt(6, if (message.outgoing) 1 else 0)
            ps.executeUpdate() == 1
        }
        if (inserted) {
            connection.prepareStatement("UPDATE conversations SET updated_at = ? WHERE id = ?").use { ps ->
                ps.setLong(1, message.ts)
                ps.setString(2, message.conversationId)
                ps.executeUpdate()
            }
        }
        return inserted
    }

    // ---- link bundle import (WP05 part B) ------------------------------------------------------

    /**
     * Imports a phone's link bundle: contacts, groups and settings. Identity keys are written by
     * [DesktopStoreLinkImporter] through [DesktopIdentity]/[DesktopKeyMaterial], not here.
     *
     * Idempotent by construction: rows are keyed by Nostr pubkey (or a hash of name+phone for a
     * contact without one) and a bundle never overwrites a row that is newer than [appliedAt].
     * Re-importing the same bundle at the same timestamp changes nothing and reports zero inserts,
     * zero updates and `settingsApplied = false`.
     */
    fun importLinkBundle(bundle: com.relay.app.linking.LinkBundle, appliedAt: Long): LinkImportCounts = synchronized(lock) {
        connection.autoCommit = false
        try {
            var inserted = 0
            var updated = 0
            var unchanged = 0

            val byKey = LinkedHashMap<String, com.relay.app.linking.LinkContact>()
            for (contact in bundle.contacts) byKey.putIfAbsent(contactKey(contact), contact)

            connection.prepareStatement(
                "INSERT INTO contacts(key, nostr_pubkey, name, phone, has_relay, trust_level, public_key, " +
                    "pending_public_key, signing_public_key, relay_hints, qr_verified, deleted_at, original_name, updated_at) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET nostr_pubkey = excluded.nostr_pubkey, " +
                    "name = excluded.name, phone = excluded.phone, has_relay = excluded.has_relay, " +
                    "trust_level = excluded.trust_level, public_key = excluded.public_key, " +
                    "pending_public_key = excluded.pending_public_key, signing_public_key = excluded.signing_public_key, " +
                    "relay_hints = excluded.relay_hints, qr_verified = excluded.qr_verified, " +
                    "deleted_at = excluded.deleted_at, original_name = excluded.original_name, " +
                    "updated_at = excluded.updated_at",
            ).use { ps ->
                for ((key, contact) in byKey) {
                    val existing = contactUpdatedAt(key)
                    if (existing != null && existing >= appliedAt) {
                        unchanged++
                        continue
                    }
                    ps.setString(1, key)
                    ps.setString(2, contact.nostrPubkey)
                    ps.setString(3, contact.name)
                    ps.setString(4, contact.phone)
                    ps.setInt(5, if (contact.hasRelay) 1 else 0)
                    ps.setString(6, contact.trustLevel)
                    ps.setString(7, contact.publicKey)
                    ps.setString(8, contact.pendingPublicKey)
                    ps.setString(9, contact.signingPublicKey)
                    ps.setString(10, org.json.JSONArray(contact.relayHints).toString())
                    ps.setInt(11, if (contact.qrVerified) 1 else 0)
                    contact.deletedAt?.let { ps.setLong(12, it) } ?: ps.setNull(12, java.sql.Types.INTEGER)
                    ps.setString(13, contact.originalName)
                    ps.setLong(14, appliedAt)
                    ps.executeUpdate()
                    if (existing == null) inserted++ else updated++
                }
            }

            var groups = 0
            connection.prepareStatement(
                "INSERT INTO link_groups(id, name, shared_owner, members, updated_at) VALUES(?, ?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET name = excluded.name, shared_owner = excluded.shared_owner, " +
                    "members = excluded.members, updated_at = excluded.updated_at",
            ).use { ps ->
                for (group in bundle.groups) {
                    val existing = groupUpdatedAt(group.id)
                    if (existing != null && existing >= appliedAt) continue
                    ps.setString(1, group.id)
                    ps.setString(2, group.name)
                    ps.setString(3, group.sharedOwner)
                    ps.setString(4, org.json.JSONArray(group.members).toString())
                    ps.setLong(5, appliedAt)
                    ps.executeUpdate()
                    groups++
                }
            }

            val settingsAt = meta(LINK_SETTINGS_AT)?.toLongOrNull() ?: 0
            val settingsApplied = settingsAt < appliedAt
            if (settingsApplied) {
                setMeta(LINK_SETTINGS, encodeSettings(bundle.settings).toString())
                setMeta(LINK_SETTINGS_AT, appliedAt.toString())
            }

            connection.commit()
            secureFiles()
            LinkImportCounts(inserted, updated, unchanged, groups, settingsApplied)
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    fun contacts(): List<DesktopContact> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT $CONTACT_COLUMNS FROM contacts ORDER BY name").use { rs ->
                buildList { while (rs.next()) add(readContact(rs)) }
            }
        }
    }

    fun groups(): List<DesktopGroup> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT id, name, shared_owner, members, updated_at FROM link_groups ORDER BY name").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            DesktopGroup(
                                id = rs.getString(1),
                                name = rs.getString(2),
                                sharedOwner = rs.getString(3),
                                members = stringList(rs.getString(4)),
                                updatedAt = rs.getLong(5),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Settings mirrored from the phone, or null before the first link. */
    fun linkSettings(): com.relay.app.linking.LinkSettings? = synchronized(lock) {
        val raw = meta(LINK_SETTINGS) ?: return null
        runCatching {
            val json = org.json.JSONObject(raw)
            com.relay.app.linking.LinkSettings(
                typingIndicators = json.optBoolean("typingIndicators", true),
                notificationPreview = json.optBoolean("notificationPreview", true),
                retentionDays = json.optInt("retentionDays", 7),
                readReceipts = json.optBoolean("readReceipts", true),
                mediaAutoDownload = json.optBoolean("mediaAutoDownload", true),
                theme = json.optString("theme"),
                locale = json.optString("locale"),
            )
        }.getOrNull()
    }

    fun linkSettingsUpdatedAt(): Long = synchronized(lock) { meta(LINK_SETTINGS_AT)?.toLongOrNull() ?: 0 }

    fun contactCount(): Long = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM contacts").use { rs -> if (rs.next()) rs.getLong(1) else 0 }
        }
    }

    private fun contactKey(contact: com.relay.app.linking.LinkContact): String =
        contact.nostrPubkey ?: "local-" + sha256Hex(contact.name + "\u0000" + contact.phone)

    private fun contactUpdatedAt(key: String): Long? =
        connection.prepareStatement("SELECT updated_at FROM contacts WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun groupUpdatedAt(id: String): Long? =
        connection.prepareStatement("SELECT updated_at FROM link_groups WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    private fun readContact(rs: java.sql.ResultSet) = DesktopContact(
        key = rs.getString(1),
        nostrPubkey = rs.getString(2),
        name = rs.getString(3),
        phone = rs.getString(4),
        hasRelay = rs.getInt(5) == 1,
        trustLevel = rs.getString(6),
        publicKey = rs.getString(7),
        pendingPublicKey = rs.getString(8),
        signingPublicKey = rs.getString(9),
        relayHints = stringList(rs.getString(10)),
        qrVerified = rs.getInt(11) == 1,
        deletedAt = rs.getLong(12).takeIf { !rs.wasNull() },
        originalName = rs.getString(13),
        updatedAt = rs.getLong(14),
    )

    private fun stringList(raw: String?): List<String> = runCatching {
        val array = org.json.JSONArray(raw ?: "[]")
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun encodeSettings(settings: com.relay.app.linking.LinkSettings): org.json.JSONObject =
        org.json.JSONObject()
            .put("typingIndicators", settings.typingIndicators)
            .put("notificationPreview", settings.notificationPreview)
            .put("retentionDays", settings.retentionDays)
            .put("readReceipts", settings.readReceipts)
            .put("mediaAutoDownload", settings.mediaAutoDownload)
            .put("theme", settings.theme)
            .put("locale", settings.locale)

    private fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun setMeta(name: String, value: String) = synchronized(lock) {
        if (name == "ui.selected") require(value.length <= MAX_CONVERSATION_ID_LENGTH) { "Conversation selection is too long" }
        connection.prepareStatement("INSERT INTO meta(name, value) VALUES(?, ?) ON CONFLICT(name) DO UPDATE SET value = excluded.value").use { ps ->
            ps.setString(1, name)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    // ---- groups, profiles, read state and drafts (WP07) ---------------------------------------

    /**
     * Upserts a roster from a [GroupProtocol.State]. The caller has already decided that this state
     * is authentic and newer (`GroupProtocol.acceptSync`); the row keeps the group's own revision so a
     * later replay can be compared. The conversation row is created or retitled here, so a group
     * appears in the rail even before its first message.
     */
    fun saveGroupState(state: GroupProtocol.State, now: Long = System.currentTimeMillis(), visible: Boolean = false) {
        val conv = groupConversationId(state.owner, state.id)
        val members = JSONArray().apply {
            for (m in state.members) put(JSONObject().put("key", m.key).put("name", m.name).put("inner", m.inner).put("relays", JSONArray(m.relays)))
        }
        synchronized(lock) {
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT INTO group_rosters(conversation_id, owner, shared_id, name, revision, members, avatar_revision, updated_at) " +
                        "VALUES(?, ?, ?, ?, ?, ?, -1, ?) ON CONFLICT(conversation_id) DO UPDATE SET " +
                        "name = excluded.name, revision = excluded.revision, members = excluded.members, updated_at = excluded.updated_at" +
                        (if (visible) ", hidden = 0 " else " ") +
                        "WHERE group_rosters.revision < excluded.revision",
                ).use { ps ->
                    ps.setString(1, conv); ps.setString(2, state.owner); ps.setString(3, state.id)
                    ps.setString(4, state.name); ps.setLong(5, state.revision); ps.setString(6, members.toString()); ps.setLong(7, now)
                    if (ps.executeUpdate() == 0) {
                        connection.rollback()
                        return@synchronized
                    }
                }
                connection.prepareStatement(
                    "INSERT INTO conversations(id, peer, title, updated_at) VALUES(?, '', ?, ?) " +
                        "ON CONFLICT(id) DO UPDATE SET title = excluded.title",
                ).use { ps ->
                    ps.setString(1, conv); ps.setString(2, state.name); ps.setLong(3, now); ps.executeUpdate()
                }
                connection.commit()
                secureFiles()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun storedGroup(conversationId: String): StoredGroup? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT conversation_id, owner, shared_id, name, revision, members, avatar, avatar_revision, updated_at, hidden " +
                "FROM group_rosters WHERE conversation_id = ?",
        ).use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs -> if (rs.next()) readGroup(rs) else null }
        }
    }

    fun storedGroup(owner: String, sharedId: String): StoredGroup? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT conversation_id, owner, shared_id, name, revision, members, avatar, avatar_revision, updated_at, hidden " +
                "FROM group_rosters WHERE owner = ? AND shared_id = ?",
        ).use { ps ->
            ps.setString(1, owner); ps.setString(2, sharedId)
            ps.executeQuery().use { rs -> if (rs.next()) readGroup(rs) else null }
        }
    }

    fun storedGroups(): List<StoredGroup> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery(
                "SELECT conversation_id, owner, shared_id, name, revision, members, avatar, avatar_revision, updated_at, hidden " +
                    "FROM group_rosters ORDER BY updated_at DESC",
            ).use { rs -> buildList { while (rs.next()) add(readGroup(rs)) } }
        }
    }

    private fun readGroup(rs: java.sql.ResultSet): StoredGroup {
        val array = JSONArray(rs.getString(6))
        val members = buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val relays = o.optJSONArray("relays") ?: JSONArray()
                add(
                    GroupProtocol.Member(
                        key = o.getString("key"),
                        name = o.optString("name", ""),
                        inner = if (o.isNull("inner")) null else o.getString("inner"),
                        relays = buildList { for (r in 0 until relays.length()) add(relays.getString(r)) },
                    ),
                )
            }
        }
        return StoredGroup(
            conversationId = rs.getString(1), owner = rs.getString(2), sharedId = rs.getString(3),
            name = rs.getString(4), revision = rs.getLong(5), members = members,
            avatar = rs.getBytes(7), avatarRevision = rs.getLong(8), updatedAt = rs.getLong(9), hidden = rs.getInt(10) != 0,
        )
    }

    /** Owner-only avatar; only a strictly newer revision is applied. Returns true when it was stored. */
    fun setGroupAvatar(conversationId: String, jpeg: ByteArray?, revision: Long): Boolean = synchronized(lock) {
        if (!validDesktopAvatar(jpeg)) return@synchronized false
        connection.prepareStatement(
            "UPDATE group_rosters SET avatar = ?, avatar_revision = ? WHERE conversation_id = ? AND avatar_revision < ?",
        ).use { ps ->
            ps.setBytes(1, jpeg); ps.setLong(2, revision); ps.setString(3, conversationId); ps.setLong(4, revision)
            ps.executeUpdate() == 1
        }.also { if (it) secureFiles() }
    }

    fun profile(pubkey: String): DesktopProfile? = synchronized(lock) {
        connection.prepareStatement(
            "SELECT pubkey, name, avatar, name_revision, avatar_revision, updated_at FROM profiles WHERE pubkey = ?",
        ).use { ps ->
            ps.setString(1, pubkey)
            ps.executeQuery().use { rs -> if (rs.next()) readProfile(rs) else null }
        }
    }

    fun profiles(): List<DesktopProfile> = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT pubkey, name, avatar, name_revision, avatar_revision, updated_at FROM profiles").use { rs ->
                buildList { while (rs.next()) add(readProfile(rs)) }
            }
        }
    }

    private fun readProfile(rs: java.sql.ResultSet) = DesktopProfile(
        pubkey = rs.getString(1),
        name = rs.getString(2),
        avatar = rs.getBytes(3),
        nameRevision = rs.getLong(4),
        avatarRevision = rs.getLong(5),
        updatedAt = rs.getLong(6),
    )

    /** Revision-guarded name update. Returns false when [revision] is not newer than what is stored. */
    fun saveProfileName(pubkey: String, name: String, revision: Long, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val exists = connection.prepareStatement("SELECT 1 FROM profiles WHERE pubkey = ?").use { ps ->
                ps.setString(1, pubkey); ps.executeQuery().use { it.next() }
            }
            if (!exists) {
                connection.prepareStatement(
                    "INSERT INTO profiles(pubkey, name, name_revision, updated_at) VALUES(?, ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, pubkey); ps.setString(2, name); ps.setLong(3, revision); ps.setLong(4, now)
                    ps.executeUpdate()
                }
                return@synchronized true
            }
            connection.prepareStatement(
                "UPDATE profiles SET name = ?, name_revision = ?, updated_at = ? WHERE pubkey = ? AND name_revision < ?",
            ).use { ps ->
                ps.setString(1, name); ps.setLong(2, revision); ps.setLong(3, now); ps.setString(4, pubkey); ps.setLong(5, revision)
                ps.executeUpdate() == 1
            }.also { if (it) secureFiles() }
        }

    /** Revision-guarded avatar update; `null` clears the photo. Returns false for a stale revision. */
    fun saveProfileAvatar(pubkey: String, jpeg: ByteArray?, revision: Long, now: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            if (!validDesktopAvatar(jpeg)) return@synchronized false
            val exists = connection.prepareStatement("SELECT 1 FROM profiles WHERE pubkey = ?").use { ps ->
                ps.setString(1, pubkey); ps.executeQuery().use { it.next() }
            }
            if (!exists) {
                connection.prepareStatement(
                    "INSERT INTO profiles(pubkey, avatar, avatar_revision, updated_at) VALUES(?, ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, pubkey); ps.setBytes(2, jpeg); ps.setLong(3, revision); ps.setLong(4, now)
                    ps.executeUpdate()
                }
                return@synchronized true
            }
            connection.prepareStatement(
                "UPDATE profiles SET avatar = ?, avatar_revision = ?, updated_at = ? WHERE pubkey = ? AND avatar_revision < ?",
            ).use { ps ->
                ps.setBytes(1, jpeg); ps.setLong(2, revision); ps.setLong(3, now); ps.setString(4, pubkey); ps.setLong(5, revision)
                ps.executeUpdate() == 1
            }.also { if (it) secureFiles() }
        }

    fun readThrough(conversationId: String): Long = synchronized(lock) {
        connection.prepareStatement("SELECT read_through FROM conversation_state WHERE conversation_id = ?").use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    /** Moves the read mark forward only: a late or repeated mark never re-badges read messages. */
    fun markRead(conversationId: String, through: Long) {
        synchronized(lock) {
            connection.prepareStatement(
                "INSERT INTO conversation_state(conversation_id, read_through, updated_at) VALUES(?, ?, ?) " +
                    "ON CONFLICT(conversation_id) DO UPDATE SET read_through = max(conversation_state.read_through, excluded.read_through), " +
                    "updated_at = excluded.updated_at",
            ).use { ps ->
                ps.setString(1, conversationId); ps.setLong(2, through); ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    fun unreadCount(conversationId: String): Int = synchronized(lock) {
        connection.prepareStatement(
            "SELECT count(*) FROM messages WHERE conversation_id = ? AND outgoing = 0 AND ts > ?",
        ).use { ps ->
            ps.setString(1, conversationId); ps.setLong(2, readThrough(conversationId))
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun conversationDraft(conversationId: String): String = synchronized(lock) {
        connection.prepareStatement("SELECT draft FROM conversation_state WHERE conversation_id = ?").use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
        }
    }

    fun setConversationDraft(conversationId: String, draft: String) {
        require(conversationId.length <= MAX_CONVERSATION_ID_LENGTH) { "Conversation id is too long" }
        require(draft.length <= MAX_DRAFT_LENGTH) { "Draft is too long" }
        synchronized(lock) {
            connection.prepareStatement(
                "INSERT INTO conversation_state(conversation_id, draft, updated_at) VALUES(?, ?, ?) " +
                    "ON CONFLICT(conversation_id) DO UPDATE SET draft = excluded.draft, updated_at = excluded.updated_at",
            ).use { ps ->
                ps.setString(1, conversationId); ps.setString(2, draft); ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    /** Holds an event that arrived before its roster, bounded so a hostile sender cannot fill the disk. */
    fun holdGroupEvent(owner: String, sharedId: String, sender: String, body: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    "INSERT INTO group_pending(owner, shared_id, sender, body, received_at) VALUES(?, ?, ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, owner); ps.setString(2, sharedId); ps.setString(3, sender); ps.setString(4, body); ps.setLong(5, now)
                    ps.executeUpdate()
                }
                // Same limits as the phone's SharedGroups: 100 per group, 500 total, 7 days.
                connection.prepareStatement(
                    "DELETE FROM group_pending WHERE row IN (SELECT row FROM group_pending WHERE owner = ? AND shared_id = ? " +
                        "ORDER BY row DESC LIMIT -1 OFFSET $PENDING_PER_GROUP)",
                ).use { ps -> ps.setString(1, owner); ps.setString(2, sharedId); ps.executeUpdate() }
                connection.prepareStatement(
                    "DELETE FROM group_pending WHERE row IN (SELECT row FROM group_pending ORDER BY row DESC LIMIT -1 OFFSET $PENDING_TOTAL)",
                ).use { it.executeUpdate() }
                connection.prepareStatement("DELETE FROM group_pending WHERE received_at < ?").use { ps ->
                    ps.setLong(1, now - PENDING_TTL_MS); ps.executeUpdate()
                }
                connection.commit()
                secureFiles()
            } catch (e: Exception) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun pendingGroupEvents(owner: String, sharedId: String): List<PendingGroupEvent> = synchronized(lock) {
        connection.prepareStatement(
            "SELECT row, sender, body, received_at FROM group_pending WHERE owner = ? AND shared_id = ? ORDER BY row",
        ).use { ps ->
            ps.setString(1, owner); ps.setString(2, sharedId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(PendingGroupEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
                }
            }
        }
    }

    fun pendingGroupEventCount(): Int = synchronized(lock) {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM group_pending").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    fun deletePendingGroupEvent(row: Long) {
        synchronized(lock) {
            connection.prepareStatement("DELETE FROM group_pending WHERE row = ?").use { ps ->
                ps.setLong(1, row); ps.executeUpdate()
            }
        }
    }

    fun meta(name: String): String? = synchronized(lock) {
        connection.prepareStatement("SELECT value FROM meta WHERE name = ?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** 7-day desktop retention (LP-D08): deletes decrypted messages older than [cutoff]. */
    fun purgeOlderThan(cutoff: Long): Int = synchronized(lock) {
        connection.prepareStatement("DELETE FROM messages WHERE ts < ?").use { ps ->
            ps.setLong(1, cutoff)
            ps.executeUpdate()
        }.also { secureFiles() }
    }

    /**
     * The SQLite driver creates the database and its WAL/SHM sidecars with the process umask
     * (usually 0644). The containing directory is already 0700, but WP06 requires owner-only file
     * modes too, so enforce 0600 after the connection opens and after every write that can create
     * or replace a sidecar.
     */
    private fun secureFiles() {
        DesktopPaths.setPrivate(databaseFile)
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = databaseFile.resolveSibling(databaseFile.fileName.toString() + suffix)
            if (java.nio.file.Files.exists(sidecar)) DesktopPaths.setPrivate(sidecar)
        }
    }

    override fun close() {
        synchronized(lock) { runCatching { connection.close() } }
    }

    /** Additive and idempotent (no `user_version` bump): the tombstone flag of a synced group roster. */
    private fun ensureGroupHiddenColumn() {
        synchronized(lock) {
            val has = connection.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(group_rosters)").use { rs ->
                    var found = false
                    while (rs.next()) if (rs.getString("name") == "hidden") found = true
                    found
                }
            }
            if (!has) connection.createStatement().use { it.execute("ALTER TABLE group_rosters ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0") }
        }
    }

    private fun migrate() {
        synchronized(lock) {
            val version = schemaVersion()
            if (version < 1) {
                connection.autoCommit = false
                try {
                    connection.createStatement().use { st ->
                        st.execute(
                            """
                            CREATE TABLE IF NOT EXISTS conversations(
                                id TEXT PRIMARY KEY,
                                peer TEXT NOT NULL,
                                title TEXT NOT NULL,
                                updated_at INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        st.execute(
                            """
                            CREATE TABLE IF NOT EXISTS messages(
                                id TEXT PRIMARY KEY,
                                conversation_id TEXT NOT NULL,
                                sender TEXT NOT NULL,
                                body TEXT NOT NULL,
                                ts INTEGER NOT NULL,
                                outgoing INTEGER NOT NULL,
                                FOREIGN KEY(conversation_id) REFERENCES conversations(id)
                            )
                            """.trimIndent(),
                        )
                        st.execute("CREATE INDEX IF NOT EXISTS messages_by_conversation ON messages(conversation_id, ts)")
                        st.execute(
                            """
                            CREATE TABLE IF NOT EXISTS mailbox_raw(
                                seq INTEGER PRIMARY KEY,
                                envelope TEXT NOT NULL
                            )
                            """.trimIndent(),
                        )
                        st.execute("CREATE TABLE IF NOT EXISTS meta(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                        st.execute("PRAGMA user_version = 1")
                    }
                    connection.commit()
                } catch (e: Exception) {
                    runCatching { connection.rollback() }
                    throw e
                } finally {
                    connection.autoCommit = true
                }
                migrationDone(3)
                return
            }
            if (version < 3) {
                migrationDone(3)
            }
        }
    }

    /** Applies the schema-2/3 tables (link import, groups, profiles, read state) and bumps `user_version`. */
    private fun migrationDone(target: Int) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS contacts(
                        key TEXT PRIMARY KEY,
                        nostr_pubkey TEXT,
                        name TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        has_relay INTEGER NOT NULL,
                        trust_level TEXT NOT NULL,
                        public_key TEXT,
                        pending_public_key TEXT,
                        signing_public_key TEXT,
                        relay_hints TEXT NOT NULL,
                        qr_verified INTEGER NOT NULL,
                        deleted_at INTEGER,
                        original_name TEXT,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS link_groups(
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        shared_owner TEXT,
                        members TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // WP07: group rosters as GroupProtocol states, owner-authenticated group avatars,
                // peer/own profiles and per-conversation read/draft state. All additive.
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS group_rosters(
                        conversation_id TEXT PRIMARY KEY,
                        owner TEXT NOT NULL,
                        shared_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        members TEXT NOT NULL,
                        avatar BLOB,
                        avatar_revision INTEGER NOT NULL DEFAULT -1,
                        updated_at INTEGER NOT NULL,
                        UNIQUE(owner, shared_id)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS profiles(
                        pubkey TEXT PRIMARY KEY,
                        name TEXT,
                        avatar BLOB,
                        name_revision INTEGER NOT NULL DEFAULT -1,
                        avatar_revision INTEGER NOT NULL DEFAULT -1,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_state(
                        conversation_id TEXT PRIMARY KEY,
                        read_through INTEGER NOT NULL DEFAULT 0,
                        draft TEXT NOT NULL DEFAULT '',
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS group_pending(
                        row INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner TEXT NOT NULL,
                        shared_id TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        body TEXT NOT NULL,
                        received_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX IF NOT EXISTS group_pending_by_group ON group_pending(owner, shared_id, row)")
                st.execute("PRAGMA user_version = $target")
            }
            connection.commit()
        } catch (e: Exception) {
            runCatching { connection.rollback() }
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    companion object {
        private const val DATABASE_KEY_ID = "database"
        private const val SYNC_CURSOR = "sync.cursor"
        private const val LINK_SETTINGS = "link.settings"
        private const val LINK_SETTINGS_AT = "link.settings.updatedAt"
        private const val PENDING_PER_GROUP = 100
        private const val MAX_DRAFT_LENGTH = 16_384
        private const val MAX_CONVERSATION_ID_LENGTH = 256
        private const val PENDING_TOTAL = 500
        private const val PENDING_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val CONTACT_COLUMNS =
            "key, nostr_pubkey, name, phone, has_relay, trust_level, public_key, pending_public_key, " +
                "signing_public_key, relay_hints, qr_verified, deleted_at, original_name, updated_at"
    }
}

/** One imported contact row (a mirror of the phone's contact, not a local edit target yet). */
data class DesktopContact(
    val key: String,
    val nostrPubkey: String?,
    val name: String,
    val phone: String,
    val hasRelay: Boolean,
    val trustLevel: String,
    val publicKey: String?,
    val pendingPublicKey: String?,
    val signingPublicKey: String?,
    val relayHints: List<String>,
    val qrVerified: Boolean,
    val deletedAt: Long?,
    val originalName: String?,
    val updatedAt: Long,
)

/** One imported group roster entry, keyed by the protocol UUID (not a local row id). */
data class DesktopGroup(
    val id: String,
    val name: String,
    val sharedOwner: String?,
    val members: List<String>,
    val updatedAt: Long,
)

/** What [DesktopMessageStore.importLinkBundle] changed, for the importer report and tests. */
data class LinkImportCounts(
    val inserted: Int,
    val updated: Int,
    val unchanged: Int,
    val groups: Int,
    val settingsApplied: Boolean,
)

/** `group:<owner pubkey>:<protocol uuid>` — the conversation id a group shares with SENT sync. */
fun groupConversationId(owner: String, sharedId: String): String = "group:$owner:$sharedId"

fun isGroupConversation(id: String): Boolean = id.startsWith("group:")

/** A roster as this desktop stores it: the protocol state plus the owner-authenticated avatar. */
data class StoredGroup(
    val conversationId: String,
    val owner: String,
    val sharedId: String,
    val name: String,
    val revision: Long,
    val members: List<com.relay.app.groups.GroupProtocol.Member>,
    val avatar: ByteArray?,
    val avatarRevision: Long,
    val updatedAt: Long,
    /** Tombstoned by sync (deleted, removed or left on the phone): kept for history, hidden from the rail. */
    val hidden: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is StoredGroup && hidden == other.hidden &&
        conversationId == other.conversationId && owner == other.owner && sharedId == other.sharedId &&
        name == other.name && revision == other.revision && members == other.members &&
        avatarRevision == other.avatarRevision && updatedAt == other.updatedAt &&
        (avatar?.contentEquals(other.avatar ?: ByteArray(0)) ?: (other.avatar == null)))

    override fun hashCode(): Int = conversationId.hashCode() * 31 + revision.hashCode()
}

/** A display name and/or avatar learned from a peer, or this install's own profile. */
data class DesktopProfile(
    val pubkey: String,
    val name: String?,
    val avatar: ByteArray?,
    val nameRevision: Long,
    val avatarRevision: Long,
    val updatedAt: Long,
)

/** A group event held until its roster arrives (`GroupProtocol.Verdict.LATER`). */
data class PendingGroupEvent(val row: Long, val sender: String, val body: String, val receivedAt: Long)

