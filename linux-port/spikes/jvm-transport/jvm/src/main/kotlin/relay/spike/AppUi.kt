package relay.spike

import org.json.JSONObject
import rust.nostr.sdk.Keys
import java.io.File
import java.util.UUID

/** Actual-app UI interop commands. Private key material remains on the host in ignored fixtures. */
fun appUiCommand(command: String, dir: File) {
    when(command) {
        "prepare-ui" -> {
            val keys=Keys.generate(); val hpke=Interop.fresh(); val token=UUID.randomUUID().toString().take(8)
            val public=JSONObject().put("jvm_pub",keys.publicKey().toHex()).put("jvm_hpke_public",Interop.publicKey(hpke))
                .put("challenge","JVM received in actual app $token").put("reply","Actual app UI reply $token")
            File(dir,"ui-input.json").writeText(public.toString())
            val secret=File(dir,"ui-private.json")
            secret.writeText(JSONObject().put("nsec",keys.secretKey().toHex()).put("hpke",Interop.privateKey(hpke)).toString())
            check(secret.setReadable(false,false) && secret.setReadable(true,true))
            check(secret.setWritable(false,false) && secret.setWritable(true,true))
            println("PREPARED actual-app synthetic peer (private values not printed)")
        }
        "send-ui" -> {
            val input=JSONObject(File(dir,"ui-input.json").readText())
            val private=JSONObject(File(dir,"ui-private.json").readText())
            val android=JSONObject(File(dir,"ui-ready.json").readText())
            val keys=Keys.parse(private.getString("nsec"))
            val envelope=Interop.wrap(keys,android.getString("android_pub"),Interop.encrypt(android.getString("android_hpke_public"),input.getString("challenge")))
            Mailbox("http://127.0.0.1:8787",keys).use { m ->
                val result=m.send(android.getString("android_pub"),UUID.randomUUID().toString(),envelope)
                check(result.toString().contains("stored")) { "Message not accepted" }
            }
            println("PASS JVM encrypted send to actual Android app identity")
        }
        "verify-ui" -> {
            val input=JSONObject(File(dir,"ui-input.json").readText())
            val private=JSONObject(File(dir,"ui-private.json").readText())
            val android=JSONObject(File(dir,"ui-ready.json").readText())
            val keys=Keys.parse(private.getString("nsec")); val hpke=Interop.importKey(private.getString("hpke"))
            val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(100)
            var verified=false
            Mailbox("http://127.0.0.1:8787",keys).use { m ->
                while(!verified && System.nanoTime()<deadline) {
                    val messages=m.request("GET","/v1/inbox?after=0&limit=100").getJSONArray("messages")
                    for(i in 0 until messages.length()) {
                        val event=messages.getJSONObject(i)
                        val p=Interop.unwrap(keys,event.getString("envelope"),android.getString("android_pub"))
                        if(p.body.startsWith("TYPE:ENC|CT:") && Interop.decrypt(hpke,p.body)==input.getString("reply")) {
                            File(dir,"ui-received.json").outputStream().use { out -> out.write(event.toString().toByteArray()); out.fd.sync() }
                            m.request("POST","/v1/ack",JSONObject().put("v",1).put("through",event.getLong("seq")))
                            verified=true
                            break
                        }
                    }
                    if(!verified) Thread.sleep(200)
                }
            }
            check(verified) { "Actual app UI reply not received/decrypted before deadline" }
            println("PASS actual app UI→production outbox→MailboxTransport→local Worker→JVM NIP-17/HPKE decrypt")
        }
        else -> error("Unknown actual-app UI command")
    }
}
