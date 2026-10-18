package com.relay.app.calculator

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.VideoView
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.relay.app.MainActivity
import com.relay.app.crypto.RelayFileCrypto
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.messaging.MessageNotifier
import com.relay.app.messaging.MessagingDb
import com.relay.app.mms.MediaCompressor
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Local fixtures only. Run after CalculatorSecurityTest on dedicated test emulators (code 4826). */
class MediaViewerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun waitFor(reason: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(reason, predicate())
    }

    private fun fixture(type: MessageType, bytes: ByteArray, verify: () -> Unit) {
        androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout = 500
        val contacts = ContactRepository(MessagingDb.get(context))
        val contact = contacts.findOrCreateByNostrSync(rust.nostr.sdk.Keys.generate().publicKey().toHex(), "Media fixture")
        val file = File(context.filesDir, "viewer-${UUID.randomUUID()}.bin")
        try {
            RelayFileCrypto.writeEncrypted(context, file, bytes)
            MessageRepository(MessagingDb.get(context)).insertMessageSync(
                Message(contactId = contact.id, body = "", type = type, isSent = false, mediaUri = file.absolutePath))
            device.executeShellCommand("am start -W -f 0x10008000 -n ${context.packageName}/com.relay.app.MainActivity " +
                "--el ${MessageNotifier.EXTRA_OPEN_CHAT} ${contact.id}")
            waitFor("Calculator gate or requested media screen finishes opening") {
                device.findObject(UiSelector().text("C")).exists() ||
                    device.findObject(UiSelector().description("Open photo")).exists() ||
                    device.findObject(UiSelector().description("Play video")).exists()
            }
            if (device.findObject(UiSelector().text("C")).exists()) {
                for (key in listOf("C", "4", "8", "2", "6", "+")) device.findObject(UiSelector().text(key)).click()
            }
            verify()
        } finally {
            device.pressBack()
            kotlinx.coroutines.runBlocking { contacts.deleteContact(contact.id) }
            file.delete()
        }
    }

    @Test fun compressionAppliesCameraRotationBeforeDroppingExif() {
        val input = File(context.cacheDir, "orientation-test.jpg")
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        for (x in 0 until 120) for (y in 0 until 60) bitmap.setPixel(x, y, if (x < 60) Color.RED else Color.GREEN)
        input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        bitmap.recycle()
        ExifInterface(input.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val result = MediaCompressor.compressImage(context, Uri.fromFile(input))
        assertTrue(result is MediaCompressor.Result.Success)
        val output = (result as MediaCompressor.Result.Success).file
        try {
            val decoded = BitmapFactory.decodeFile(output.absolutePath)
            assertEquals(60, decoded.width)
            assertEquals(120, decoded.height)
            assertTrue(Color.red(decoded.getPixel(30, 20)) > 200)
            assertTrue(Color.green(decoded.getPixel(30, 100)) > 200)
            decoded.recycle()
        } finally { input.delete(); output.delete() }
    }

    @Test fun portraitAndLandscapePhotosKeepTheirRatioAndOpenInsideTheApp() {
        for ((width, height) in listOf(160 to 320, 400 to 100)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            fixture(MessageType.IMAGE, bytes) {
                val photo = device.findObject(UiSelector().description("Open photo"))
                assertTrue(photo.waitForExists(8_000))
                waitFor("Preview keeps source aspect ratio without cropping") {
                    val bounds = photo.bounds
                    kotlin.math.abs(bounds.width().toFloat() / bounds.height() - width.toFloat() / height) < .04f
                }
                photo.click()
                assertTrue(device.findObject(UiSelector().description("Full screen photo")).waitForExists(5_000))
                device.findObject(UiSelector().description("Close media")).click()
                assertFalse(device.findObject(UiSelector().description("Full screen photo")).exists())
                assertTrue(photo.exists())
            }
        }
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test fun receivedVideoActuallyPlaysAndStopsOnCloseAndSessionExpiry() {
        val bytes = instrumentation.context.assets.open("viewer-test.mp4").use { it.readBytes() }
        fixture(MessageType.VIDEO, bytes) {
            val play = device.findObject(UiSelector().description("Play video"))
            assertTrue(play.waitForExists(8_000))
            fun findVideo(view: View): VideoView? = (view as? VideoView)
                ?: (view as? ViewGroup)?.let { parent -> (0 until parent.childCount).firstNotNullOfOrNull { findVideo(parent.getChildAt(it)) } }
            fun openedPlayer(): VideoView {
                var result: VideoView? = null
                waitFor("In-app video decoder starts playback") {
                    instrumentation.runOnMainSync {
                        result = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { findVideo(it) }
                    }
                    result?.isPlaying == true
                }
                return result!!
            }
            play.click()
            val first = openedPlayer()
            val position = first.currentPosition
            SystemClock.sleep(400)
            assertTrue(first.currentPosition > position)
            assertTrue(first.duration >= 4_000)
            device.findObject(UiSelector().description("Close media")).click()
            waitFor("Closing media releases playback") { !first.isPlaying }
            play.click()
            val second = openedPlayer()
            val expiryStarted = SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().first()
                val session = ViewModelProvider(activity)[CalculatorSession::class.java]
                session.expireIfNeeded(session.deadline)
                assertEquals(0L, session.deadline)
            }
            val expiredGate = device.wait(Until.hasObject(By.pkg(context.packageName).text("C")), 15_000)
            if (!expiredGate) device.dumpWindowHierarchy(File(context.filesDir, "expiry-failure.xml"))
            assertTrue("Expired media session returns to Calculator", expiredGate)
            assertTrue("Session expiry removes the private media window", device.wait(
                Until.gone(By.pkg(context.packageName).desc("Close media")), 5_000))
            waitFor("Session expiry stops playback") { !second.isPlaying }
            waitFor("Session expiry removes the private plaintext view files") {
                File(context.cacheDir, "private-media-view").listFiles().isNullOrEmpty()
            }
            Log.i("RelayMediaExpiryTest", "Calculator gate, stopped playback and cleared view cache verified in ${SystemClock.elapsedRealtime() - expiryStarted} ms")
        }
    }
}
