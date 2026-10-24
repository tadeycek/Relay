package com.relay.app.desktop

import com.relay.app.transport.mailbox.MailboxMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.sql.SQLException

class DesktopMessageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun paths(): DesktopPaths {
        val config = tmp.newFolder("config").toPath()
        val data = tmp.newFolder("data").toPath()
        return DesktopPaths(config, data)
    }

    @Test
    fun `round-trips messages through the encrypted database and survives reopen`() {
        val paths = paths()
        val secrets = FileSecretStore(paths.secretFile)
        DesktopMessageStore(paths.databaseFile, secrets).use { store ->
            assertEquals(1, store.schemaVersion())
            store.insertMessage(DesktopMessage("m1", "peer-a", "peer-a", "hello", 1_000L, outgoing = false))
            store.insertMessage(DesktopMessage("m2", "peer-a", "me", "hi back", 2_000L, outgoing = true))
            assertEquals(2, store.messages("peer-a").size)
            assertEquals("hello", store.messages("peer-a").first().body)
            assertEquals(1, store.conversations().size)
        }
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { reopened ->
            assertEquals(2, reopened.messages("peer-a").size)
            assertEquals("hi back", reopened.messages("peer-a").last().body)
        }
    }

    @Test
    fun `database file is not plain SQLite and wrong key fails`() {
        val paths = paths()
        val secrets = FileSecretStore(paths.secretFile)
        DesktopMessageStore(paths.databaseFile, secrets).use { store ->
            store.insertMessage(DesktopMessage("m1", "peer-a", "peer-a", "top secret text", 1_000L, false))
        }
        val header = ByteArray(16)
        Files.newInputStream(paths.databaseFile).use { it.read(header) }
        assertFalse(String(header, Charsets.US_ASCII).startsWith("SQLite format 3"))

        // WP06 acceptance: the on-disk database must not contain a recognisable plaintext message.
        val onDisk = Files.readAllBytes(paths.databaseFile)
        assertFalse(
            "database file must not contain message plaintext",
            String(onDisk, Charsets.ISO_8859_1).contains("top secret text"),
        )

        val wrongKey = FileSecretStore(tmp.newFolder("wrong").toPath().resolve("secret.key"))
        assertThrows(SQLException::class.java) {
            DesktopMessageStore(paths.databaseFile, wrongKey).close()
        }
    }

    @Test
    fun `database file is owner-only 0600`() {
        val paths = paths()
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            store.insertMessage(DesktopMessage("m1", "peer-a", "peer-a", "private", 1_000L, false))
            assertTrue("database file must be 0600", DesktopPaths.isPrivate(paths.databaseFile))
            for (suffix in listOf("-wal", "-shm")) {
                val extra = paths.databaseFile.resolveSibling(paths.databaseFile.fileName.toString() + suffix)
                if (Files.exists(extra)) {
                    assertTrue("$extra must be 0600", DesktopPaths.isPrivate(extra))
                }
            }
        }
    }

    @Test
    fun `raw packets are stored once and delete only when handled`() {
        val paths = paths()
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            val messages = listOf(MailboxMessage(1, "env-1"), MailboxMessage(2, "env-2"))
            assertEquals(listOf(1L, 2L), store.store(messages))
            // Redelivery of the same seqs must not duplicate.
            assertEquals(emptyList<Long>(), store.store(messages))
            assertEquals(2, store.pending().size)
            assertTrue(store.delete(1))
            assertEquals(listOf(2L), store.pending().map { it.seq })
            assertFalse(store.delete(1))
        }
    }

    @Test
    fun `retention removes messages older than the cutoff`() {
        val paths = paths()
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            store.insertMessage(DesktopMessage("old", "peer", "peer", "old", 1_000L, false))
            store.insertMessage(DesktopMessage("new", "peer", "peer", "new", 9_000L, false))
            assertEquals(1, store.purgeOlderThan(5_000L))
            assertEquals(listOf("new"), store.messages("peer").map { it.body })
        }
    }

    @Test
    fun `wal file does not contain message plaintext`() {
        val paths = paths()
        DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile)).use { store ->
            store.insertMessage(DesktopMessage("m1", "peer", "peer", "PLAINTEXT-SENTINEL-12345", 1_000L, false))
            val wal = paths.databaseFile.resolveSibling(paths.databaseFile.fileName.toString() + "-wal")
            if (Files.exists(wal)) {
                val bytes = Files.readAllBytes(wal)
                assertFalse("WAL must be encrypted", String(bytes, Charsets.ISO_8859_1).contains("PLAINTEXT-SENTINEL-12345"))
            }
        }
    }
}
