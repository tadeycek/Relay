package com.relay.app.sync

import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.linking.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.Base64

class FileSentSyncTest {
    private class Queue : SyncOutbox {
        val rows=mutableMapOf<String,SyncOutboxItem>(); var granted=false
        override fun enqueue(item:SyncOutboxItem)=rows.putIfAbsent(item.payloadId,item)==null
        override fun confirm(payloadId:String) {rows[payloadId]?.let {rows[payloadId]=it.copy(confirmed=true)}}
        override fun discard(payloadId:String) {rows.remove(payloadId)}
        override fun due(now:Long,limit:Int)=rows.values.filter {it.confirmed && it.nextAttemptAt<=now}.take(limit)
        override fun recordAttempt(payloadId:String,attempts:Int,nextAttemptAt:Long){rows[payloadId]?.let {rows[payloadId]=it.copy(attempts=attempts,nextAttemptAt=nextAttemptAt)}}
        override fun remove(payloadId:String) {rows.remove(payloadId)}
        override fun published(payloadId:String){granted=true;remove(payloadId)}
        override fun purgeStale(olderThan:Long)=0
        override fun clear(){rows.clear()}
    }
    private class Net : SyncNetwork {
        var outcome:PostResult=PostResult.Ok;val posted=mutableListOf<String>();var deviceCalls=0
        override suspend fun listDevices():DevicesResult {deviceCalls++;return DevicesResult.Ok(0)}
        override suspend fun post(envelopeJson:String):PostResult {posted+=envelopeJson;return outcome}
        override suspend fun list(after:Long,limit:Int)=ListResult.Ok(emptyList(),false)
        override suspend fun ack(through:Long)=true
    }
    private val hint=object:LinkedHint {override fun get()=false;override fun set(value:Boolean){}}
    private fun item()=SyncOutboxItem("original-file-id","a".repeat(64),PayloadCodec.encode(RelayPayload(id="original-file-id",ts=123,body="SEALED:manifest")),123,
        confirmed=true,mediaDigests=listOf("b".repeat(64),"c".repeat(64)))
    @Test fun fileIsMirroredWithoutTurningUnknownTypesIntoMessages() {
        assertTrue(SentSyncWire.mirrored("TYPE:FILE|V:1"));assertFalse(SentSyncWire.mirrored("TYPE:FUTURE|SECRET:value"))
    }
    @Test fun grantsAreAuthenticatedTopLevelMetadataAndKeepOriginalPayloadId()=runBlocking<Unit> {
        val q=Queue();q.enqueue(item());val n=Net()
        val result=SentSyncPublisher(n,q,hint,clock={1234}).flush()
        assertEquals(1,result.items);assertEquals(0,n.deviceCalls);assertTrue(q.granted)
        val json=JSONObject(n.posted.single());assertEquals("b".repeat(64),json.getJSONArray("media").getString(0))
        val wire=String(Base64.getDecoder().decode(json.getString("data")))
        val sent=(SyncCodec.decode(wire) as SyncResult.Ok).body as SyncBody.Sent
        val payload=PayloadCodec.decode(String(Base64.getDecoder().decode(sent.payloadBase64)))!!
        assertEquals("original-file-id",payload.id);assertEquals(item().payloadJson,PayloadCodec.encode(payload))
    }
    @Test fun retryDoesNotGrantOrChangeMediaReferences()=runBlocking<Unit> {
        val q=Queue();q.enqueue(item());val n=Net();n.outcome=PostResult.Retry("offline")
        SentSyncPublisher(n,q,hint,clock={1234}).flush()
        assertFalse(q.granted);assertEquals(item().mediaDigests,q.rows.values.single().mediaDigests)
        q.rows[item().payloadId]=q.rows.values.single().copy(nextAttemptAt=0)
        n.outcome=PostResult.Ok;SentSyncPublisher(n,q,hint,clock={1234}).flush()
        assertTrue(q.granted);assertEquals(n.posted[0],n.posted[1])
    }
    @Test fun permanentFailureNeverBecomesGrantReceipt()=runBlocking<Unit> {
        val q=Queue();q.enqueue(item());val n=Net();n.outcome=PostResult.Permanent("refused")
        SentSyncPublisher(n,q,hint,clock={1234}).flush();assertFalse(q.granted)
    }
    @Test fun completionCarriesReferencesForDeviceEnrolledAfterUpload()=runBlocking<Unit> {
        val ownDevices=linkedSetOf("primary")
        val putSnapshot=ownDevices.toSet()
        ownDevices += "late-desktop"
        var publicationSnapshot=setOf<String>()
        val server=object:SyncNetwork {
            override suspend fun listDevices()=DevicesResult.Ok(1)
            override suspend fun post(envelopeJson:String):PostResult {
                val refs=JSONObject(envelopeJson).getJSONArray("media")
                assertEquals(item().mediaDigests.size,refs.length())
                publicationSnapshot=ownDevices.toSet()
                return PostResult.Ok
            }
            override suspend fun list(after:Long,limit:Int)=ListResult.Ok(emptyList(),false)
            override suspend fun ack(through:Long)=true
        }
        val q=Queue();q.enqueue(item())
        assertFalse(putSnapshot.contains("late-desktop"))
        SentSyncPublisher(server,q,hint,clock={1234}).flush()
        assertTrue(q.granted);assertTrue(publicationSnapshot.contains("late-desktop"))
    }
    @Test fun noMediaRetainsLegacyEnvelopeBytes() {
        assertEquals(SentSyncWire.envelope("text","a".repeat(32),1,10),SentSyncWire.envelope("text","a".repeat(32),1,10,emptyList()))
        assertFalse(JSONObject(SentSyncWire.envelope("text","a".repeat(32),1,10)).has("media"))
        assertThrows(IllegalArgumentException::class.java) {SentSyncWire.envelope("text","a".repeat(32),1,10,listOf("bad"))}
    }
}
