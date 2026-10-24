package com.relay.app.media

import com.relay.app.files.*
import com.relay.app.transport.RelayPayload
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class PhoneFileTransferTest {
    private val base = "https://relay.example/v1/media/"
    private class Store : PhoneFileStore {
        val rows = linkedMapOf<String, PhoneFileJob>()
        var allowConfirm = true
        override fun all() = rows.values.toList()
        override fun get(token: String) = rows[token]
        override fun insert(job: PhoneFileJob): Boolean {
            if(rows.values.any { it.contact == job.contact && it.id == job.id }) return false
            rows[job.token] = job; return true
        }
        override fun update(job: PhoneFileJob) { if(rows[job.token]?.cancelled != true) rows[job.token] = job }
        override fun enqueue(job: PhoneFileJob) { update(job.copy(queued = true)) }
        override fun confirmationAllowed(job: PhoneFileJob) = allowConfirm
    }
    private class Net : PhoneFileNetwork {
        val blobs = mutableMapOf<String, ByteArray>()
        var uploads = 0; var downloads = 0; var confirms = 0
        var failDownload = false; var failConfirm = false
        var onUpload: (() -> Unit)? = null
        override fun upload(file: File, part: FilePart, recipients: List<String>) {
            uploads++; onUpload?.invoke(); blobs[part.sha256Hex] = file.readBytes()
        }
        override fun download(url: String, part: FilePart, target: File) {
            downloads++
            if(failDownload) throw IOException("offline")
            target.writeBytes(blobs.getValue(part.sha256Hex))
        }
        override fun confirm(url: String): Boolean { confirms++; return !failConfirm }
    }
    private fun fixture(block: (PrivateFileSpace, Store, Net, PhoneFileEngine) -> Unit) {
        val root = Files.createTempDirectory("phone-file-test").toFile()
        try { val space = PrivateFileSpace(root); val store = Store(); val net = Net()
            block(space, store, net, PhoneFileEngine(space, store, net, { it == base }))
        } finally { root.deleteRecursively() }
    }
    private fun prepare(engine: PhoneFileEngine, input: InputStream = ByteArrayInputStream("hello".toByteArray())) =
        engine.prepare(input, 7, "original-file-id", 123, "document.bin", "application/octet-stream", base, listOf("a".repeat(64), "b".repeat(64)))

    @Test fun unknownSizeNonseekableInputReadOnceThenRetryUsesIdenticalCiphertext() = fixture { space, store, net, engine ->
        val input = object : InputStream() { var pos = 0; override fun read(): Int = if(pos++ < 100000) 42 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int { assertTrue(len <= 65536); return super.read(b,off,len) } }
        val job = prepare(engine, input)
        assertEquals(0, net.uploads); assertFalse(store.get(job.token)!!.queued)
        val encrypted = space.file(job.token, "part0").readBytes()
        net.onUpload = { throw IOException("offline") }; engine.tick()
        assertNotNull(store.get(job.token)!!.error)
        engine.retry(job.token); net.onUpload = null; engine.tick()
        assertArrayEquals(encrypted, net.blobs.values.single()); assertTrue(store.get(job.token)!!.queued)
        assertEquals(100000L, engine.verifiedSource(job.token).length())
    }
    @Test fun filePreviewDoesNotExposeManifestKeysOrOpaqueTypeFields() = fixture {_,_,_,engine ->
        val job=prepare(engine)
        val preview=com.relay.app.data.conversation.ConversationFormat.preview(com.relay.app.data.model.MessageType.TEXT,job.wire,false)
        assertEquals("File · document.bin",preview);assertFalse(preview.contains("KEY:"));assertFalse(preview.contains("TYPE:"))
    }
    @Test fun emptyFileStillHasOnePart() = fixture { _, store, net, engine ->
        val job = prepare(engine, ByteArrayInputStream(byteArrayOf())); engine.tick()
        assertEquals(33, net.blobs.values.single().size)
        assertEquals(0, engine.verifiedSource(job.token).length()); assertTrue(store.get(job.token)!!.ready)
    }
    @Test fun senderConfirmationWaitsForDurableSentGrantPublication() = fixture { _, store, net, engine ->
        val job = prepare(engine); store.allowConfirm = false; engine.tick()
        assertTrue(store.get(job.token)!!.queued); assertEquals(0, net.confirms)
        store.allowConfirm = true; engine.tick(); assertEquals(1, net.confirms)
    }
    @Test fun cancellationDuringUploadCannotQueueOrResurrect() = fixture { _, store, net, engine ->
        val job = prepare(engine)
        net.onUpload = { store.update(store.get(job.token)!!.copy(cancelled = true)) }
        engine.tick(); engine.retry(job.token); engine.tick()
        assertTrue(store.get(job.token)!!.cancelled); assertFalse(store.get(job.token)!!.queued); assertEquals(0, net.confirms)
    }
    @Test fun quotaFailureDoesNotCreateJob() {
        val root = Files.createTempDirectory("phone-quota").toFile()
        try { val store = Store(); val e = PhoneFileEngine(PrivateFileSpace(root, 1), store, Net(), { true })
            assertThrows(IOException::class.java) { prepare(e) }; assertTrue(store.all().isEmpty()); assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun sourceReadErrorCleansOrphansWithoutNetwork() = fixture { space, store, net, engine ->
        assertThrows(IOException::class.java) { prepare(engine, object: InputStream() { override fun read(): Int = throw IOException("disk") }) }
        assertTrue(store.all().isEmpty()); assertEquals(0, net.uploads); assertTrue(space.root.listFiles()!!.isEmpty())
    }
    @Test fun sentDownloadBindsOriginalIdAndPersistsBeforeConfirm() = fixture { _, senderStore, net, sender ->
        val sent = prepare(sender); sender.tick()
        val root = Files.createTempDirectory("phone-recipient").toFile()
        try {
            val store = Store(); val engine = PhoneFileEngine(PrivateFileSpace(root), store, net, { it == base })
            assertNull(engine.receive(RelayPayload(id="outer-sync-id", ts=123, body=sent.wire),7,true))
            val received = engine.receive(RelayPayload(id=sent.id,ts=123,body=sent.wire),7,true)!!
            net.failConfirm = true; val previous = net.confirms; engine.tick()
            assertTrue(store.get(received.token)!!.ready); assertArrayEquals("hello".toByteArray(),engine.verifiedSource(received.token).readBytes())
            assertEquals(previous+1,net.confirms); assertEquals(1,net.downloads)
            val restarted = PhoneFileEngine(PrivateFileSpace(root),store,net,{it == base})
            net.failConfirm = false; restarted.tick(); assertEquals(1,net.downloads); assertEquals(1,store.get(received.token)!!.confirmed)
            assertTrue(senderStore.get(sent.token)!!.ready)
        } finally { root.deleteRecursively() }
    }
    @Test fun wrongWholeDigestNeverPublishesOrConfirms() = fixture { _, _, net, sender ->
        val sent = prepare(sender); sender.tick()
        val root = Files.createTempDirectory("phone-bad-digest").toFile()
        try { val store = Store(); val space = PrivateFileSpace(root); val e = PhoneFileEngine(space,store,net,{it == base})
            val body = (FileBody.parse(sent.wire) {true} as FileBodyResult.Ok).body
            val wire = FileBody.encode(body.copy(sha256Hex="0".repeat(64)))
            val received = e.receive(RelayPayload(id=sent.id,ts=123,body=wire),7)!!
            val before = net.confirms; e.tick(); assertFalse(store.get(received.token)!!.ready); assertEquals(before,net.confirms)
            assertThrows(IllegalArgumentException::class.java) { e.verifiedSource(received.token) }
        } finally { root.deleteRecursively() }
    }
    @Test fun changedManifestCannotReplaceDurableTask() = fixture { _, _, _, engine ->
        val job = prepare(engine)
        val body = (FileBody.parse(job.wire) {true} as FileBodyResult.Ok).body
        assertThrows(IllegalArgumentException::class.java) { engine.receive(RelayPayload(id=job.id,ts=123,body=FileBody.encode(body.copy(name="different.bin"))),7) }
    }
    @Test fun missingPersistedPartIsFetchedAgainAfterRestart() = fixture {_,_,net,sender ->
        val sent=prepare(sender);sender.tick()
        val root=Files.createTempDirectory("phone-missing-part").toFile()
        try {
            val space=PrivateFileSpace(root);val store=Store();val e=PhoneFileEngine(space,store,net,{it==base})
            val job=e.receive(RelayPayload(id=sent.id,ts=123,body=sent.wire),7)!!
            store.update(job.copy(transferred=1))
            e.tick()
            assertTrue("Missing durable part must be fetched rather than trusted by SQL bit",store.get(job.token)!!.ready)
            assertEquals(1,net.downloads)
        } finally {root.deleteRecursively()}
    }
    @Test fun quotaRejectionClosesSourceBeforeAnyRead() {
        val root=Files.createTempDirectory("phone-close").toFile()
        try {
            var closed=false
            val source=object:InputStream() {override fun read():Int {fail("Quota rejected source must not be read");return -1};override fun close(){closed=true}}
            val e=PhoneFileEngine(PrivateFileSpace(root,1),Store(),Net(),{true})
            assertThrows(IOException::class.java) {prepare(e,source)}
            assertTrue("Rejected owned stream must close",closed)
        } finally {root.deleteRecursively()}
    }
    @Test fun oversizedUnknownSourceStopsAtCapPlusOneAndCleansPrivateCopy() = fixture { space, store, net, engine ->
        var count=0L
        val input=object:InputStream() {
            override fun read():Int {count++;return 0}
            override fun read(b:ByteArray,o:Int,n:Int):Int {assertTrue(n<=65536);java.util.Arrays.fill(b,o,o+n,0.toByte());count+=n;return n}
        }
        assertThrows(IOException::class.java) {prepare(engine,input)}
        assertEquals(PhoneFileEngine.MAX_BYTES+1,count);assertTrue(store.all().isEmpty());assertEquals(0,net.uploads);assertTrue(space.root.listFiles()!!.isEmpty())
    }
    @Test fun partialMultipartDownloadRestartsAtOnlyMissingPart() = fixture { _, _, net, sender ->
        val size=26212814+17
        var left=size
        val source=object:InputStream() {override fun read():Int=if(left-->0) 7 else -1
            override fun read(b:ByteArray,o:Int,n:Int):Int {if(left<=0)return -1;val take=minOf(n,left);java.util.Arrays.fill(b,o,o+take,7.toByte());left-=take;return take}}
        val sent=prepare(sender,source);sender.tick()
        val root=Files.createTempDirectory("phone-resume").toFile()
        try {
            val store=Store();val space=PrivateFileSpace(root)
            var calls=0
            val interrupted=object:PhoneFileNetwork {
                override fun upload(file:File,part:FilePart,recipients:List<String>)=Unit
                override fun download(url:String,part:FilePart,target:File) {calls++;if(calls==2)throw IOException("disconnect");net.download(url,part,target)}
                override fun confirm(url:String)=net.confirm(url)
            }
            val e=PhoneFileEngine(space,store,interrupted,{it==base})
            val job=e.receive(RelayPayload(id=sent.id,ts=123,body=sent.wire),7)!!
            e.tick();assertEquals(1,store.get(job.token)!!.transferred);assertFalse(store.get(job.token)!!.ready)
            val next=PhoneFileEngine(space,store,net,{it==base});next.retry(job.token);next.tick()
            assertTrue(store.get(job.token)!!.ready);assertEquals(size.toLong(),next.verifiedSource(job.token).length());assertEquals(2,net.downloads)
        } finally {root.deleteRecursively()}
    }
    @Test fun privatePartFilesAndRootHaveOwnerOnlyPermissions() = fixture {space,_,_,engine ->
        val job=prepare(engine)
        assertEquals("rwx------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(space.root.toPath())))
        assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(space.file(job.token,"part0").toPath())))
        engine.cancel(job.token);engine.tick();assertTrue(space.root.listFiles()!!.isEmpty())
    }
    @Test fun symlinksAndExistingCiphertextAreNeverOverwritten() = fixture { space, _, _, _ ->
        val outside = Files.createTempFile("phone-outside", ".txt")
        try { val token = "00000000-0000-0000-0000-000000000000"
            Files.createSymbolicLink(File(space.root,"$token.source").toPath(), outside)
            assertThrows(IllegalArgumentException::class.java) { space.file(token,"source") }
            assertThrows(IllegalArgumentException::class.java) { space.reserve(1) }
        } finally { Files.delete(outside) }
    }
}
