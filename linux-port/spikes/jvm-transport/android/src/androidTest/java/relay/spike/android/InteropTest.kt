package relay.spike.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.KeyTemplates
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import relay.spike.*
import rust.nostr.sdk.Keys
import java.io.File

@RunWith(AndroidJUnit4::class)
class InteropTest {
    @Test fun androidJVMInterop() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        Interop.init()
        val input=JSONObject(File(context.filesDir,"input.json").readText())
        val a=Keys.parse(input.getString("jvm_nsec"))
        val b=Keys.parse(input.getString("android_nsec"))
        val h=Interop.importKey(input.getString("android_hpke"))
        val p=Interop.unwrap(b,input.getString("envelope"),input.getString("jvm_pub"))
        assertEquals(input.getString("challenge"),Interop.decrypt(h,p.body))
        // Real AndroidKeysetManager: software HPKE private key sealed at rest by Android Keystore.
        val manager=AndroidKeysetManager.Builder().withSharedPref(context,"synthetic_key","spike_keys")
            .withKeyTemplate(KeyTemplates.get(Interop.TEMPLATE)).withMasterKeyUri("android-keystore://relay_spike_only").build()
        val exported=Interop.privateKey(manager.keysetHandle)
        val output=JSONObject().put("android_opened_jvm",true)
            .put("exported_android_hpke",exported)
            .put("export_ciphertext",Interop.encrypt(Interop.publicKey(manager.keysetHandle),"Android private key export works"))
            .put("envelope",Interop.wrap(b,a.publicKey().toHex(),Interop.encrypt(Interop.publicKey(Interop.importKey(input.getString("jvm_hpke"))),"Android to JVM: Živjo 📨")))
        if(InstrumentationRegistry.getArguments().getString("localWorker")=="1") {
            Mailbox("http://127.0.0.1:8787",b).use { m ->
                m.checkSocket()
                val messages=m.request("GET","/v1/inbox?after=0&limit=10").getJSONArray("messages")
                assertTrue(messages.length()>0)
                val event=messages.getJSONObject(0)
                val got=Interop.unwrap(b,event.getString("envelope"),a.publicKey().toHex())
                assertEquals(input.getString("challenge"),Interop.decrypt(h,got.body))
                // Private local evidence must be durable before acknowledging the queue.
                val saved=File(context.filesDir,"received.json"); saved.outputStream().use { o -> o.write(event.toString().toByteArray()); o.fd.sync() }
                m.request("POST","/v1/ack",JSONObject().put("v",1).put("through",event.getLong("seq")))
                assertEquals(0,m.request("GET","/v1/inbox?after=0&limit=10").getJSONArray("messages").length())
                m.send(a.publicKey().toHex(),"android-fixture",output.getString("envelope"))
                output.put("mailbox_verified",true)
            }
        }
        File(context.filesDir,"output.json").writeText(output.toString())
    }
}
