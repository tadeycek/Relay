package relay.spike

import org.junit.Test
import org.junit.Assert.*
import rust.nostr.sdk.Keys
import org.json.JSONObject
import com.relay.app.transport.PayloadCodec

class InteropTest {
    init { Interop.init() }
    @Test fun privateKeyExportRoundtripAndWrongKeyFailure() {
        val h=Interop.fresh()
        val loaded=Interop.importKey(Interop.privateKey(h))
        val encrypted=Interop.encrypt(Interop.publicKey(h),"hello Živjo 📨")
        assertEquals("hello Živjo 📨",Interop.decrypt(loaded,encrypted))
        assertThrows(Exception::class.java) { Interop.decrypt(Interop.fresh(),encrypted) }
    }
    @Test fun giftWrapVerifiesSenderAndRecipient() {
        val a=Keys.generate(); val b=Keys.generate(); val attacker=Keys.generate()
        val w=Interop.wrap(a,b.publicKey().toHex(),"TYPE:ENC|CT:fixture")
        assertEquals("TYPE:ENC|CT:fixture",Interop.unwrap(b,w,a.publicKey().toHex()).body)
        assertThrows(Exception::class.java) { Interop.unwrap(b,w,attacker.publicKey().toHex()) }
        assertThrows(Exception::class.java) { Interop.unwrap(attacker,w,a.publicKey().toHex()) }
    }
    @Test fun authBindsExactUrlMethodBodyAndNonce() {
        val k=Keys.generate(); val url="https://example.invalid/v1/send?device=primary"
        val h=Interop.header(k,"POST",url,"{}".toByteArray())
        val event=JSONObject(String(java.util.Base64.getDecoder().decode(h.removePrefix("Nostr "))))
        val tags=event.getJSONArray("tags").toString()
        assertTrue(tags.contains(url)); assertTrue(tags.contains("POST")); assertTrue(tags.contains("payload"))
        assertNotEquals(h,Interop.header(k,"POST",url,"{}".toByteArray()))
    }
    @Test fun productionCodecRejectsMalformedPayload() {
        assertNull(PayloadCodec.decode("{}"))
        assertNull(PayloadCodec.decode("{\"v\":99,\"id\":\"x\",\"ts\":1,\"body\":\"x\"}"))
    }
    @Test fun nonLoopbackHttpRefused() {
        assertThrows(IllegalArgumentException::class.java) { Mailbox("http://example.com",Keys.generate()) }
    }
}
