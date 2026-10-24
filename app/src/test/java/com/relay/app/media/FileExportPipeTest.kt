package com.relay.app.media

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class FileExportPipeTest {
    @Test fun verificationPrecedesOpeningExternalSink() {
        var opened=false;var deleted=false
        assertThrows(IOException::class.java) {FileExportPipe.copy({throw IOException("invalid source")},{opened=true;ByteArrayOutputStream()},{deleted=true;true})}
        assertFalse(opened);assertTrue(deleted)
    }
    @Test fun failedExternalOutputIsDeletedAndReported() {
        var deleted=false
        val e=assertThrows(IOException::class.java) {FileExportPipe.copy({ByteArrayInputStream(byteArrayOf(1))},{object:OutputStream(){override fun write(b:Int){throw IOException("full")}}},{deleted=true;true})}
        assertTrue(deleted);assertTrue(e.message!!.contains("removed"))
    }
    @Test fun deletionFailureIsNotHidden() {
        val e=assertThrows(IOException::class.java) {FileExportPipe.copy({throw IOException("bad")},{ByteArrayOutputStream()},{false})}
        assertTrue(e.message!!.contains("could not be removed"))
    }
    @Test fun streamsAreBoundedAndClosed() {
        var sourceClosed=false;var sinkClosed=false;var max=0;var total=0
        val source=object:ByteArrayInputStream(ByteArray(200000)) {override fun close(){sourceClosed=true}}
        val sink=object:OutputStream(){override fun write(b:Int){total++};override fun write(b:ByteArray,o:Int,n:Int){max=maxOf(max,n);total+=n};override fun close(){sinkClosed=true}}
        FileExportPipe.copy({source},{sink},{fail("Successful save must not delete output");false})
        assertEquals(200000,total);assertTrue(max<=65536);assertTrue(sourceClosed);assertTrue(sinkClosed)
    }
}
