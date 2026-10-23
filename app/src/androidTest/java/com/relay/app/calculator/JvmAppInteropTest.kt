package com.relay.app.calculator

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.relay.app.MainActivity
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.MessagingRuntime
import com.relay.app.transport.Transports
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.mailbox.MailboxTransport
import com.relay.app.transport.nostr.NostrIdentity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in actual production UI/services with a test-only local endpoint and a seeded synthetic contact. */
class JvmAppInteropTest {
    @Test fun productionChatReceivesAndSendsJvmEncryptedText() {
        assumeTrue("Disposable-emulator harness only", InstrumentationRegistry.getArguments().getString("jvmAppInterop")=="1")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        Configurator.getInstance().waitForIdleTimeout=500
        val dir=File(context.filesDir,"jvm-ui").apply { mkdirs() }
        val input=JSONObject(File(dir,"input.json").readText())
        val received=input.getString("challenge")
        val reply=input.getString("reply")
        // The host refuses already-installed com.relay.app to avoid touching personal app state.
        check(!com.relay.app.transport.mailbox.MailboxConfig.available) { "Test artifact must have no live server URL" }
        val keys=NostrIdentity.keys(context)
        val local=Transports.get(context) as? MailboxTransport ?: error("Runner did not install the isolated mailbox transport")
        // UI automation may resume a retained activity; install the local transport first.
        device.setOrientationNatural()
        if(android.os.Build.VERSION.SDK_INT>=33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.POST_NOTIFICATIONS)
        val repo=ContactRepository(MessagingDb.get(context))
        val contact=repo.findOrCreateByNostrSync(input.getString("jvm_pub"),"JVM Spike")
        repo.setPublicKeySync(contact.id,input.getString("jvm_hpke_public"))
        repo.markQrVerifiedSync(contact.id)
        fun atomic(name: String, value: String) {
            val temp=File(dir,"$name.tmp")
            temp.outputStream().use { it.write(value.toByteArray()); it.fd.sync() }
            check(temp.renameTo(File(dir,name)))
        }
        atomic("ready.json",JSONObject().put("android_pub",keys.publicKey().toHex())
            .put("android_hpke_public",checkNotNull(RelayCrypto.myPublicKeyBase64(context))).toString())
        fun waitFor(label: String, timeout: Long=120_000, condition: ()->Boolean) {
            val until=SystemClock.elapsedRealtime()+timeout
            while(!condition()) { check(SystemClock.elapsedRealtime()<until) { "Timeout: $label" }; SystemClock.sleep(100) }
        }
        fun tap(text: String) {
            val view=device.findObject(UiSelector().text(text))
            assertTrue("Visible $text",view.waitForExists(15_000)); view.click()
        }
        try {
            waitFor("local Worker ready") { File(dir,"worker-ready").exists() }
            MessagingRuntime.ensureStarted(context)
            waitFor("production MailboxTransport online") { local.status.value==TransportStatus.ONLINE }
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            tap("C"); listOf("4","8","2","6","+").forEach(::tap)
            assertTrue("Unlocked app",device.findObject(UiSelector().text("Account")).waitForExists(15_000))
            device.findObjects(By.text("People")).maxByOrNull { it.visibleBounds.centerY() }!!.click()
            val contacts=UiScrollable(UiSelector().scrollable(true))
            if(!device.findObject(UiSelector().text("JVM Spike")).waitForExists(1000))
                assertTrue(contacts.scrollIntoView(UiSelector().text("JVM Spike")))
            tap("JVM Spike"); tap("Message")
            assertTrue("Real chat composer",device.findObject(UiSelector().description("Send")).waitForExists(10_000))
            assertTrue("JVM message rendered by production conversation UI",device.wait(Until.hasObject(By.text(received)),60_000))
            val incoming=runBlocking { MessageRepository(MessagingDb.get(context)).getMessages(contact.id) }
            assertTrue("Incoming plaintext in encrypted app DB",incoming.any { !it.isSent && it.body==received })
            val entry=device.findObject(UiSelector().className("android.widget.EditText"))
            assertTrue(entry.waitForExists(5000)); entry.setText(reply)
            device.findObject(UiSelector().description("Send")).click()
            assertTrue("Outgoing text rendered",device.wait(Until.hasObject(By.text(reply)),15_000))
            assertTrue("Screenshot",device.takeScreenshot(File(dir,"chat.png")))
            atomic("ui-sent.json",JSONObject().put("received_rendered",true).put("outgoing_rendered",true)
                .put("transport",local::class.java.simpleName).put("contact_seeded",true).toString())
            waitFor("JVM verified UI-sent message") { File(dir,"host-verified").exists() }
            atomic("passed.json",JSONObject().put("passed",true).put("live_cloudflare",false).put("real_phone",false).toString())
        } catch(failure: Throwable) {
            device.takeScreenshot(File(dir,"failure.png"))
            device.dumpWindowHierarchy(File(dir,"failure.xml"))
            throw failure
        } finally {
            device.unfreezeRotation()
            runBlocking { local.stop() }
        }
    }
}
