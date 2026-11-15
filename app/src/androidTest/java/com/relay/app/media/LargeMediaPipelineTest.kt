package com.relay.app.media

import android.media.MediaMetadataRetriever
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.mms.MediaCompressor
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Local-only fixtures: the caller supplies a real >=180 s, >12 MiB MP4 in the test app cache. */
class LargeMediaPipelineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun longVideoPreparesStreamsToEncryptedStorageAndSeeksWithoutAFullViewCopy() {
        val fixture = File(InstrumentationRegistry.getArguments().getString("videoFixture")
            ?: File(context.cacheDir,"relay-long-video.mp4").absolutePath)
        assumeTrue("A real long-video fixture is required",fixture.isFile)
        assertTrue(fixture.length() > 12L*1024*1024)
        val inputHash = MediaCrypto.sha256Hex(fixture)
        val result = MediaCompressor.prepareVideo(context,Uri.fromFile(fixture))
        assertTrue("Long video must be accepted: $result",result is MediaCompressor.Result.Success)
        val prepared = (result as MediaCompressor.Result.Success).file
        val encrypted = File(context.filesDir,"long-video-${UUID.randomUUID()}.enc")
        val view = File(context.cacheDir,"long-video-${UUID.randomUUID()}.mp4")
        try {
            assertEquals(fixture.length(),prepared.length())
            assertEquals(inputHash,MediaCrypto.sha256Hex(prepared))
            prepared.inputStream().use { RelayFileCrypto.writeEncrypted(context,encrypted,it,prepared.length()) }
            assertEquals(prepared.length(),RelayFileCrypto.plainSize(context,encrypted))
            RelayFileCrypto.mediaDataSource(context,encrypted).use { data ->
                assertEquals(prepared.length(),data.size)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(data)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("Video must retain at least 180 seconds",duration >= 180_000)
                    val frame = if(android.os.Build.VERSION.SDK_INT >= 27)
                        retriever.getScaledFrameAtTime(170_000_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,256,256)
                        else retriever.getFrameAtTime(170_000_000)
                    assertNotNull("Random-access authenticated video thumbnail",frame)
                    frame?.recycle()
                } finally { retriever.release() }
            }
            assertFalse("Thumbnail reads must not create a plaintext view",view.exists())
            RelayFileCrypto.copyForView(context,encrypted,view)
            assertEquals(inputHash,MediaCrypto.sha256Hex(view))
        } finally { prepared.delete(); encrypted.delete(); view.delete() }
    }

    @Test fun localStreamingRejectsTamperingTruncationAndMovedFilesAndReadsLegacyEncryption() {
        val bytes = ByteArray(2*MediaCrypto.PART_BYTES+47) { (it%251).toByte() }
        val file = File(context.filesDir,"crypto-fixture-${UUID.randomUUID()}.enc")
        val moved = File(context.filesDir,"moved-${UUID.randomUUID()}.enc")
        try {
            RelayFileCrypto.writeEncrypted(context,file,bytes)
            val cipher = file.readBytes()
            assertArrayEquals(bytes,RelayFileCrypto.readDecrypted(context,file))
            moved.writeBytes(cipher)
            assertThrows(Exception::class.java) { RelayFileCrypto.readDecrypted(context,moved) }
            for(index in listOf(0,20,MediaCrypto.PART_BYTES,cipher.lastIndex)) {
                file.writeBytes(cipher.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() })
                assertThrows(Exception::class.java) { RelayFileCrypto.readDecrypted(context,file) }
            }
            file.writeBytes(cipher.copyOf(cipher.size-1))
            assertThrows(Exception::class.java) { RelayFileCrypto.readDecrypted(context,file) }
            val legacy = AndroidKeysetManager.Builder().withSharedPref(context.applicationContext,"relay_media_keyset","relay_media_keyset_prefs")
                .withKeyTemplate(KeyTemplates.get("AES256_GCM")).withMasterKeyUri("android-keystore://relay_media_master_key")
                .build().keysetHandle.getPrimitive(Aead::class.java)
            file.writeBytes(legacy.encrypt(bytes,file.name.toByteArray(Charsets.UTF_8)))
            assertArrayEquals(bytes,RelayFileCrypto.readDecrypted(context,file))
        } finally { file.delete(); moved.delete() }
    }

    @Test fun databaseTwentyFourMigrationAddsDurableTransferTablesAndKeepsExistingData() {
        val dir = File(context.cacheDir,"migration-${UUID.randomUUID()}").also { it.mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(dir,name)
        }
        try {
            val before = com.relay.app.data.db.RelayDbHelper(isolated)
            try {
                val db = before.writableDatabase
                db.execSQL("CREATE TABLE migration_sentinel (value TEXT NOT NULL)")
                db.execSQL("INSERT INTO migration_sentinel VALUES ('kept')")
                db.execSQL("DROP TABLE attachment_progress")
                db.execSQL("DROP TABLE pending_media_receives")
                db.version = 24
            } finally { before.close() }
            val after = com.relay.app.data.db.RelayDbHelper(isolated)
            try {
                val db = after.readableDatabase
                assertEquals(25,db.version)
                assertEquals("kept",db.rawQuery("SELECT value FROM migration_sentinel",null).use { it.moveToFirst();it.getString(0) })
                assertEquals(0,db.rawQuery("SELECT COUNT(*) FROM attachment_progress",null).use { it.moveToFirst();it.getInt(0) })
                assertEquals(0,db.rawQuery("SELECT COUNT(*) FROM pending_media_receives",null).use { it.moveToFirst();it.getInt(0) })
            } finally { after.close() }
        } finally { dir.deleteRecursively() }
    }
}
