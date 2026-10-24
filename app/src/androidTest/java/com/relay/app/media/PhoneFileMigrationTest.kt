package com.relay.app.media

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.data.db.RelayDbHelper
import net.sqlcipher.database.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit runner for disposable file fixtures; never starts RelayApplication or any network service. */
class PhoneFileTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl:ClassLoader,className:String,context:Context):Application =
        super.newApplication(cl,Application::class.java.name,context)
}

class PhoneFileMigrationTest {
    @Test fun androidProviderStreamsAndAuthenticatesPrivateRawParts() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"wp09-provider-${UUID.randomUUID()}")
        val rows=linkedMapOf<String,PhoneFileJob>()
        val store=object:PhoneFileStore {
            override fun all()=rows.values.toList()
            override fun get(token:String)=rows[token]
            override fun insert(job:PhoneFileJob):Boolean {rows[job.token]=job;return true}
            override fun update(job:PhoneFileJob) {rows[job.token]=job}
            override fun enqueue(job:PhoneFileJob) {update(job.copy(queued=true))}
            override fun confirmationAllowed(job:PhoneFileJob)=true
        }
        val blobs=mutableMapOf<String,ByteArray>()
        val net=object:PhoneFileNetwork {
            override fun upload(file:File,part:com.relay.app.files.FilePart,recipients:List<String>){blobs[part.sha256Hex]=file.readBytes()}
            override fun download(url:String,part:com.relay.app.files.FilePart,target:File){target.writeBytes(blobs.getValue(part.sha256Hex))}
            override fun confirm(url:String)=true
        }
        try {
            val e=PhoneFileEngine(PrivateFileSpace(root),store,net,{true})
            val original=ByteArray(131073){(it%251).toByte()}
            val job=e.prepare(original.inputStream(),1,"android-original-id",123,"test.bin","application/octet-stream","https://relay.example/v1/media/",listOf("a".repeat(64)))
            e.tick();assertTrue(rows.getValue(job.token).ready)
            val incoming=e.receive(com.relay.app.transport.RelayPayload(id=job.id,ts=123,body=job.wire),2,true)!!
            e.tick();assertTrue(rows.getValue(incoming.token).ready)
            assertArrayEquals(original,e.verifiedSource(incoming.token).readBytes())
            assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(e.verifiedSource(incoming.token).toPath())))
            e.cancel(incoming.token);assertTrue(rows.getValue(incoming.token).cancelled)
        } finally {root.deleteRecursively()}
    }
    @Test fun sqlCipherV25ToV26PreservesMessagesAndAddsImmutableTransferIdentity() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.loadLibs(context)
        val file=File(context.cacheDir,"wp09-migration-${UUID.randomUUID()}.db")
        val helper=RelayDbHelper(context)
        try {
            SQLiteDatabase.openOrCreateDatabase(file,"disposable-wp09-test",null).use {sql ->
                sql.execSQL("CREATE TABLE contacts(_id INTEGER PRIMARY KEY)")
                sql.execSQL("CREATE TABLE messages(_id INTEGER PRIMARY KEY,contact_id INTEGER,msg_id TEXT,body TEXT)")
                sql.execSQL("INSERT INTO contacts(_id) VALUES(1)")
                sql.execSQL("INSERT INTO messages(_id,contact_id,msg_id,body) VALUES(1,1,'preserved-id','preserved message')")
                helper.onUpgrade(sql,25,26)
                assertEquals("preserved message",sql.rawQuery("SELECT body FROM messages WHERE _id=1",emptyArray<String>()).use {it.moveToFirst();it.getString(0)})
                sql.execSQL("INSERT INTO phone_files(token,contact_id,payload_id,timestamp,wire,outgoing,recipients) VALUES('token',1,'original-id',123,'manifest',0,'')")
                assertThrows(android.database.SQLException::class.java) {sql.execSQL("INSERT INTO phone_files(token,contact_id,payload_id,timestamp,wire,outgoing,recipients) VALUES('other',1,'original-id',123,'changed',0,'')")}
                sql.execSQL("INSERT INTO file_media_refs(payload_id,digests,created_at) VALUES('original-id','digest',123)")
                assertEquals(0,sql.rawQuery("SELECT sync_granted FROM file_media_refs",emptyArray<String>()).use {it.moveToFirst();it.getInt(0)})
            }
            assertThrows(Exception::class.java) {SQLiteDatabase.openOrCreateDatabase(file,"wrong-key",null).close()}
            assertFalse(String(file.readBytes(),Charsets.ISO_8859_1).contains("preserved message"))
        } finally {helper.close();file.delete();File(file.path+"-journal").delete();File(file.path+"-wal").delete();File(file.path+"-shm").delete()}
    }
}
