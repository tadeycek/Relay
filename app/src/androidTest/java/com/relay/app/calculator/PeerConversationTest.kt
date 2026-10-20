package com.relay.app.calculator

import android.Manifest
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.BitmapFactory
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.relay.app.MainActivity
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.media.MediaCrypto
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.messaging.MessagingDb
import com.relay.app.nfc.Type4Tag
import com.relay.app.pairing.PairingCoordinator
import com.relay.app.transport.Transports
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.QrContactCode
import com.relay.app.util.RelayPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in two-emulator harness. Commands and fixture results stay in the test app's private files. */
class PeerConversationTest {
    @Test fun realQrPairingAndConversation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val role = InstrumentationRegistry.getArguments().getString("peerRole")
        assumeTrue("Run explicitly with -e peerRole Alice/Bob", role == "Alice" || role == "Bob")
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        Configurator.getInstance().waitForIdleTimeout = 500
        val directory = File(context.filesDir, "peer-test").apply { mkdirs() }
        val commandFile = File(directory, "command.json")
        val resultFile = File(directory, "result.json")
        commandFile.delete()
        fun result(value: JSONObject) {
            val temp = File(directory, "result.tmp")
            temp.writeText(value.toString())
            check(temp.renameTo(resultFile))
        }
        fun tap(text: String) {
            val view = device.findObject(UiSelector().text(text))
            assertTrue("Visible control: $text", view.waitForExists(10_000))
            view.click()
        }
        fun unlockRoot() {
            device.pressHome()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue("App finishes drawing before unlock/navigation", device.findObject(
                UiSelector().packageName(context.packageName).className("android.widget.TextView")).waitForExists(15_000))
            if (device.findObject(UiSelector().text("C")).exists()) {
                tap("C")
                for (key in listOf("4", "8", "2", "6", "+")) tap(key)
            }
            for (attempt in 0 until 5) {
                if (device.findObject(UiSelector().text("Account")).waitForExists(1_000)) break
                device.pressBack()
            }
            assertTrue(device.findObject(UiSelector().text("Account")).waitForExists(5_000))
        }
        val contacts = ContactRepository(MessagingDb.get(context))
        fun groupId(name: String): Long = runBlocking {
            com.relay.app.data.repository.GroupRepository(MessagingDb.get(context)).getAllGroups().first { it.name == name }.id
        }
        fun status(): JSONObject = runBlocking {
            val records = contacts.getAllContacts()
            JSONObject().put("role", role).put("transport", Transports.get(context).status.value.name)
                .put("dbVersion", MessagingDb.get(context).readableDatabase.version)
                .put("route", Transports.get(context)::class.simpleName)
                .put("typing", JSONArray(com.relay.app.messaging.TypingSignals.entries.value.values.map { it.name }))
                .put("activity", JSONObject().apply {
                    com.relay.app.messaging.PresenceSignals.observed.value.forEach { (key, value) ->
                        put(key, JSONObject().put("time", value.time).put("active", value.active))
                    }
                })
                .put("groups", JSONArray().apply {
                    val own = NostrIdentity.publicKeyHex(context)
                    val db = MessagingDb.get(context)
                    for (group in com.relay.app.data.repository.GroupRepository(db).getAllGroups()) {
                        put(JSONObject().put("name", group.name).put("id", group.id).put("messages", JSONArray().apply {
                            for (m in com.relay.app.data.repository.GroupMessageRepository(db).getMessages(group.id)) {
                                put(JSONObject().put("body", m.body).put("sent", m.senderKey == null || m.senderKey == own)
                                    .put("type", m.type.name)
                                    .put("event", m.eventId).put("readAt", m.readAt ?: JSONObject.NULL)
                                    .put("readCount", m.readCount).put("readerCount", m.readerCount)
                                    .put("delivery", m.deliveryState)
                                    .put("mediaHash", m.mediaUri?.let { path -> runCatching {
                                        MediaCrypto.sha256Hex(RelayFileCrypto.readDecrypted(context, File(path)))
                                    }.getOrNull() } ?: JSONObject.NULL))
                            }
                        }))
                    }
                })
                .put("pubkey", NostrIdentity.publicKeyHex(context))
                .put("incomingPair", PairingCoordinator.incoming.value?.name ?: JSONObject.NULL)
                .put("selfPhotoHash", com.relay.app.media.ContactPhotos.selfUpdate(context).jpeg?.let { MediaCrypto.sha256Hex(it) } ?: JSONObject.NULL)
                .put("outgoingPair", PairingCoordinator.outgoing.value?.name ?: JSONObject.NULL)
                .put("contacts", JSONArray().apply {
                    for (contact in records) put(JSONObject().put("name", contact.name).put("id", contact.id).put("pubkey", contact.nostrPubkey ?: JSONObject.NULL)
                        .put("verified", contact.qrVerified).put("hasKey", contact.publicKey != null)
                        .put("photoHash", runCatching {
                            MediaCrypto.sha256Hex(RelayFileCrypto.readDecrypted(context,
                                File(context.filesDir, "contact-photos/${contact.id}.enc")))
                        }.getOrNull() ?: JSONObject.NULL)
                        .put("messages", JSONArray().apply {
                            for (message in MessageRepository(MessagingDb.get(context)).getMessages(contact.id)) {
                                put(JSONObject().put("body", message.body).put("sent", message.isSent)
                                    .put("delivery", message.deliveryState).put("type", message.type.name)
                                    .put("readAt", message.readAt ?: JSONObject.NULL)
                                    .put("mediaHash", message.mediaUri?.let { path -> runCatching {
                                        val file = File(path)
                                        val bytes = runCatching { RelayFileCrypto.readDecrypted(context, file) }.getOrElse { file.readBytes() }
                                        check(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null)
                                        MediaCrypto.sha256Hex(bytes)
                                    }.getOrNull() } ?: JSONObject.NULL))
                            }
                        }))
                })
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            RelayPreferences(context).myName = role!!
            device.setOrientationNatural()
            unlockRoot()
            result(status().put("id", "ready").put("ok", true))
            val deadline = SystemClock.elapsedRealtime() + 20 * 60_000
            var lastId = ""
            while (SystemClock.elapsedRealtime() < deadline) {
                val command = runCatching { JSONObject(commandFile.readText()) }.getOrNull()
                val id = command?.optString("id").orEmpty()
                if (id.isEmpty() || id == lastId) { SystemClock.sleep(200); continue }
                lastId = id
                try {
                    val measurements = JSONObject()
                    when (command!!.getString("action")) {
                        "measure_history" -> {
                            unlockRoot(); tap("Messages")
                            val name = command.getString("name")
                            val conversation = device.findObject(UiSelector().text(name))
                            if (!conversation.waitForExists(1_000)) {
                                assertTrue("Conversation is reachable", UiScrollable(UiSelector().scrollable(true))
                                    .scrollIntoView(UiSelector().text(name)))
                            }
                            val started = SystemClock.elapsedRealtime()
                            conversation.click()
                            assertTrue("Conversation composer appears", device.wait(Until.hasObject(By.desc("Send")), 20_000))
                            assertTrue("Existing exact message appears in the conversation", device.wait(
                                Until.hasObject(By.text(command.getString("body")).clazz("android.widget.TextView")), 20_000))
                            measurements.put("tapToHistoryMs", SystemClock.elapsedRealtime() - started)
                            assertFalse("Existing history is not falsely empty", device.hasObject(By.text("Say hello")))
                        }
                        "burst" -> {
                            val entry = device.findObject(UiSelector().className("android.widget.EditText"))
                            assertTrue(entry.waitForExists(5_000))
                            assertTrue("Refuse to overwrite an existing draft", entry.text.isNullOrEmpty())
                            val timings = JSONArray()
                            val texts = command.getJSONArray("texts")
                            for (index in 0 until texts.length()) {
                                val text = texts.getString(index)
                                entry.setText(text)
                                val started = SystemClock.elapsedRealtime()
                                device.findObject(UiSelector().description("Send")).click()
                                assertTrue("Durable own bubble appears", device.wait(
                                    Until.hasObject(By.text(text).clazz("android.widget.TextView")), 10_000))
                                timings.put(SystemClock.elapsedRealtime() - started)
                                assertTrue("Accepted draft clears", entry.text.isNullOrEmpty())
                            }
                            measurements.put("tapToOwnBubbleMs", timings)
                        }
                        "typing_ui" -> {
                            val entry = device.findObject(UiSelector().className("android.widget.EditText"))
                            assertTrue(entry.waitForExists(5_000))
                            assertTrue("Refuse to overwrite a draft", entry.text.isNullOrEmpty())
                            val text = command.getString("text")
                            require(text.startsWith("QA-TYPING-"))
                            entry.setText(text)
                        }
                        "clear_test_draft" -> {
                            val entry = device.findObject(UiSelector().className("android.widget.EditText"))
                            assertTrue(entry.waitForExists(5_000))
                            assertTrue("Only clear this run's synthetic typing draft", entry.text.startsWith("QA-TYPING-"))
                            entry.clearTextField()
                        }
                        "show_qr" -> {
                            unlockRoot(); tap("Account"); tap("Show my code")
                            assertTrue(device.findObject(UiSelector().description("Your code")).waitForExists(20_000))
                            val invitation = PairingCoordinator.shownCode.value!!
                            assertTrue(invitation.expiresAtMs > System.currentTimeMillis() + 29 * 60_000)
                        }
                        "scan_image" -> {
                            val bitmap = BitmapFactory.decodeFile(File(directory, "peer-qr.png").path)
                            assertNotNull("Peer QR screenshot is present", bitmap)
                            val scanner = BarcodeScanning.getClient()
                            val raw = try {
                                Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, 0)), 25, TimeUnit.SECONDS)
                                    .firstNotNullOfOrNull { it.rawValue }
                            } finally { scanner.close() }
                            assertNotNull("Production ML Kit decoder reads the displayed QR", raw)
                            val decoded = QrContactCode.decode(raw!!)!!
                            assertTrue("Scanned invitation is usable", QrContactCode.isUsableInvitation(decoded))
                            unlockRoot()
                            val intent = Intent(context, MainActivity::class.java).apply {
                                action = NfcAdapter.ACTION_NDEF_DISCOVERED
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(NdefMessage(
                                    NdefRecord.createMime(Type4Tag.MIME_TYPE, raw.toByteArray(Charsets.UTF_8)))))
                            }
                            // Feed the real image decoder's result through the production QR/NFC confirmation path.
                            context.startActivity(intent)
                            assertTrue(device.findObject(UiSelector().text("Add ${decoded.name}?")).waitForExists(10_000))
                        }
                        "confirm_add" -> tap("Add ${command.getString("peer")}")
                        "accept_incoming" -> tap("Add ${command.getString("peer")}")
                        "chat" -> {
                            unlockRoot()
                            val peer = command.getString("peer")
                            // Always through People: a group row in Messages can mention the peer too.
                            val peopleTab = device.findObjects(By.text("People")).maxByOrNull { it.visibleBounds.centerY() }
                            assertNotNull("People navigation tab is visible", peopleTab)
                            peopleTab!!.click()
                            assertTrue("People screen is open", device.findObject(
                                UiSelector().description("Add a person by scanning their code")).waitForExists(10_000))
                            val peopleList = UiScrollable(UiSelector().scrollable(true))
                            if (!device.findObject(UiSelector().text("Contacts")).waitForExists(1_000)) {
                                assertTrue("People list is visible", peopleList.waitForExists(10_000))
                                assertTrue("Contacts section is reachable", peopleList.scrollIntoView(UiSelector().text("Contacts")))
                            }
                            if (!device.findObject(UiSelector().text(peer)).waitForExists(1_000)) {
                                assertTrue("Contact $peer is reachable", peopleList.scrollIntoView(UiSelector().text(peer)))
                            }
                            tap(peer); tap("Message")
                            assertTrue(device.findObject(UiSelector().description("Send")).waitForExists(10_000))
                            // Lists can reorder under the tap (e.g. presence changing); make sure the chat is the peer's.
                            assertTrue("Opened the chat with $peer", device.findObject(UiSelector().text(peer)).waitForExists(3_000))
                        }
                        "photo" -> {
                            val fixture = File(directory, "fixture-photo.png")
                            assertTrue(fixture.exists())
                            val displayName = "qa-${role}-${System.currentTimeMillis()}.png"
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Calculator QA")
                            }
                            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                            context.contentResolver.openOutputStream(uri)!!.use { output -> fixture.inputStream().use { it.copyTo(output) } }
                            val attach = device.findObject(UiSelector().description("Attach a photo or video"))
                            assertTrue("Attachment works without a direct connection", attach.waitForExists(5_000))
                            attach.click(); tap("Photo from gallery")
                            var image = device.findObject(UiSelector().textContains(displayName.removeSuffix(".png")))
                            if (!image.waitForExists(4_000)) image = device.findObject(UiSelector().descriptionContains(displayName.removeSuffix(".png")))
                            // Android's Photo Picker describes thumbnails by date, not filename.
                            // Our just-inserted fixture is the newest item on these dedicated emulators.
                            if (!image.waitForExists(4_000)) image = device.findObject(UiSelector().descriptionStartsWith("Photo taken on").instance(0))
                            if (!image.waitForExists(4_000)) device.dumpWindowHierarchy(File(directory, "picker.xml"))
                            assertTrue("Gallery displays the inserted photo", image.exists())
                            image.click()
                            if (device.findObject(UiSelector().text("C")).waitForExists(800)) {
                                for (key in listOf("4", "8", "2", "6", "+")) tap(key)
                            }
                            assertTrue("Chosen attachment survives picker return", device.findObject(UiSelector().description("Attachment preview")).waitForExists(10_000))
                            val entry = device.findObject(UiSelector().className("android.widget.EditText"))
                            entry.setText(command.getString("text"))
                            device.findObject(UiSelector().description("Send")).click()
                        }
                        "set_profile" -> {
                            val fixture = File(directory, "fixture-photo.png")
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, "profile-${role}-${System.currentTimeMillis()}.png")
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Calculator QA")
                            }
                            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                            context.contentResolver.openOutputStream(uri)!!.use { output -> fixture.inputStream().use { it.copyTo(output) } }
                            unlockRoot(); tap("Account"); tap("Profile photo"); tap("Choose photo and share")
                            val image = device.findObject(UiSelector().descriptionStartsWith("Photo taken on").instance(0))
                            assertTrue("Profile picker shows the fixture", image.waitForExists(10_000))
                            image.click()
                            val end = SystemClock.elapsedRealtime() + 10_000
                            while (!com.relay.app.media.ContactPhotos.hasPhoto(context, 0) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100)
                            assertTrue("Own profile photo is saved", com.relay.app.media.ContactPhotos.hasPhoto(context, 0))
                        }
                        "remove_profile" -> {
                            unlockRoot(); tap("Account"); tap("Profile photo"); tap("Remove profile photo")
                            val end = SystemClock.elapsedRealtime() + 5_000
                            while (com.relay.app.media.ContactPhotos.hasPhoto(context, 0) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
                            assertFalse(com.relay.app.media.ContactPhotos.hasPhoto(context, 0))
                        }
                        "send" -> {
                            val entry = device.findObject(UiSelector().className("android.widget.EditText"))
                            assertTrue(entry.waitForExists(5_000))
                            entry.setText(command.getString("text"))
                            device.findObject(UiSelector().description("Send")).click()
                            assertTrue(device.findObject(UiSelector().text(command.getString("text"))).waitForExists(5_000))
                        }
                        // Group and typing traffic, driven through the app's own functions rather than the UI,
                        // so the delivery path is what's under test.
                        "create_group" -> {
                            val peer = runBlocking { contacts.getAllContacts() }.first { it.name == command.getString("peer") }
                            com.relay.app.groups.SharedGroups.create(context, command.getString("name"), listOf(peer.id))
                        }
                        "group_send" -> com.relay.app.groups.SharedGroups.send(context, groupId(command.getString("group")), command.getString("text"))
                        "group_photo" -> {
                            val copy = File(context.cacheDir, "group-fixture.png")
                            File(directory, "fixture-photo.png").copyTo(copy, overwrite = true)
                            com.relay.app.media.PendingAttachments.stage(context, groupId(command.getString("group")), true, copy, "image/png", command.getString("text"))
                            com.relay.app.messaging.MessagingRuntime.kick()
                        }
                        "typing" -> {
                            val group = command.optString("group")
                            if (group.isNotEmpty()) com.relay.app.messaging.TypingSignals.send(context, groupId = groupId(group), typing = true)
                            else com.relay.app.messaging.TypingSignals.send(context,
                                contactId = runBlocking { contacts.getAllContacts() }.first { it.name == command.getString("peer") }.id, typing = true)
                        }
                        "reconnect" -> runBlocking { Transports.get(context).reconnect() }
                        // A peer that wiped its app comes back with a new key; its old contact must go.
                        "forget_stale" -> runBlocking {
                            for (c in contacts.getAllContacts()) {
                                if (c.name == command.getString("peer") && c.nostrPubkey != command.getString("keep")) contacts.deleteContact(c.id)
                            }
                            // Lists reload on this broadcast; without it the deleted chat stays on screen.
                            context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").setPackage(context.packageName))
                        }
                        "status" -> Unit
                        "pause_poll" -> androidx.work.WorkManager.getInstance(context)
                            .cancelUniqueWork("relay_poll").result.get(10, TimeUnit.SECONDS)
                        "stop" -> { result(status().put("id", id).put("ok", true)); return }
                        else -> error("Unknown test action")
                    }
                    result(status().put("id", id).put("ok", true).put("measurements", measurements))
                } catch (failure: Throwable) {
                    device.dumpWindowHierarchy(File(directory, "failure.xml"))
                    device.takeScreenshot(File(directory, "failure.png"))
                    result(status().put("id", id).put("ok", false).put("error", failure.stackTraceToString()))
                }
            }
            fail("Peer test controller did not stop within twenty minutes")
        } finally { device.unfreezeRotation() }
    }
}
