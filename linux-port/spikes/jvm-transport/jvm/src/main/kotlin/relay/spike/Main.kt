package relay.spike

import org.json.JSONObject
import rust.nostr.sdk.Keys
import java.io.File
import kotlin.system.measureTimeMillis

fun main(args: Array<String>) {
    require(args.size == 2) { "usage: prepare|verify PRIVATE_FIXTURE_DIRECTORY" }
    val dir = File(args[1]).apply { mkdirs() }
    val elapsed = measureTimeMillis {
        Interop.init()
        when (args[0]) {
            "prepare" -> {
                val a = Keys.generate(); val b = Keys.generate()
                val h = Interop.fresh(); val dh = Interop.fresh()
                val challenge = "JVM to Android: Živjo 📨 ${java.util.UUID.randomUUID()}"
                val f = JSONObject().put("jvm_nsec",a.secretKey().toHex()).put("android_nsec",b.secretKey().toHex())
                    .put("jvm_hpke",Interop.privateKey(h)).put("android_hpke",Interop.privateKey(dh))
                    .put("challenge",challenge).put("jvm_pub",a.publicKey().toHex()).put("android_pub",b.publicKey().toHex())
                    .put("envelope",Interop.wrap(a,b.publicKey().toHex(),Interop.encrypt(Interop.publicKey(dh),challenge)))
                val path = File(dir,"input.json"); path.writeText(f.toString())
                check(path.setReadable(false,false) && path.setReadable(true,true) && path.setWritable(false,false) && path.setWritable(true,true))
                File(dir,"allowlist.txt").writeText(a.publicKey().toHex()+","+b.publicKey().toHex())
                println("PREPARED synthetic identities and private fixtures (not printed)")
            }
            "send" -> {
                val f=JSONObject(File(dir,"input.json").readText())
                Mailbox("http://127.0.0.1:8787",Keys.parse(f.getString("jvm_nsec"))).use { m ->
                    val result=m.send(f.getString("android_pub"),"jvm-fixture",f.getString("envelope"))
                    check(result.toString().contains("stored") || result.toString().contains("duplicate"))
                    println("PASS JVM→local Worker signed NIP-98 send")
                }
            }
            "verify" -> {
                val f=JSONObject(File(dir,"input.json").readText())
                val out=JSONObject(File(dir,"output.json").readText())
                check(out.getBoolean("android_opened_jvm"))
                val a=Keys.parse(f.getString("jvm_nsec"))
                val h=Interop.importKey(out.getString("exported_android_hpke"))
                check(Interop.decrypt(h, out.getString("export_ciphertext")) == "Android private key export works")
                val p=Interop.unwrap(a,out.getString("envelope"),f.getString("android_pub"))
                check(Interop.decrypt(Interop.importKey(f.getString("jvm_hpke")),p.body)=="Android to JVM: Živjo 📨")
                val jh=Interop.importKey(f.getString("jvm_hpke"))
                check(Interop.decrypt(jh,Interop.encrypt(Interop.publicKey(jh),"roundtrip"))=="roundtrip")
                println("PASS Android→JVM NIP-17, inner HPKE, AndroidKeysetManager private export/import, JVM→Android NIP-17/HPKE")
                if (out.has("mailbox_verified")) {
                    Mailbox("http://127.0.0.1:8787",a).use { m ->
                        m.checkSocket()
                        val list=m.request("GET","/v1/inbox?after=0&limit=10").getJSONArray("messages")
                        check(list.length()==1)
                        val event=list.getJSONObject(0)
                        val payload=Interop.unwrap(a,event.getString("envelope"),f.getString("android_pub"))
                        check(Interop.decrypt(jh,payload.body)=="Android to JVM: Živjo 📨")
                        val target=File(dir,"received.json")
                        target.outputStream().use { stream -> stream.write(event.toString().toByteArray()); stream.fd.sync() }
                        m.request("POST","/v1/ack",JSONObject().put("v",1).put("through",event.getLong("seq")))
                        check(m.request("GET","/v1/inbox?after=0&limit=10").getJSONArray("messages").length()==0)
                    }
                    println("PASS JVM↔Android via actual local Worker, WebSocket auth/sync, store-before-ack and delete-on-ack")
                }
            }
            else -> error("unknown command")
        }
    }
    val nativeClass=Class.forName("com.sun.jna.NativeLibrary")
    val loaded=nativeClass.getMethod("getInstance",String::class.java).invoke(null,"nostr_sdk_ffi")
    println("NATIVE="+(nativeClass.getMethod("getFile").invoke(loaded) as File).absolutePath)
    println("OPERATION_MS=$elapsed")
}
