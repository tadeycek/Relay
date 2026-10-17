package com.relay.app.calculator

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import com.relay.app.BuildConfig
import com.relay.app.MainActivity
import com.relay.app.data.model.Contact
import com.relay.app.data.model.MessageType
import com.relay.app.messaging.MessageNotifier
import com.relay.app.transport.Route
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises real Android Keystore/prefs/notifications, isolated from the user's access state. */
class CalculatorSecurityTest {
    private lateinit var context: Context
    private val testFile = "calculator_access_instrumentation"

    @Before fun before() {
        androidx.test.uiautomator.Configurator.getInstance().waitForIdleTimeout = 500
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteSharedPreferences(testFile)
    }

    @After fun after() {
        context.deleteSharedPreferences(testFile)
        MessageNotifier.cancel(context, 999)
        MessageNotifier.cancel(context, 1999)
    }

    @Test fun encryptedPinVerifierSurvivesRecreationAndIsNotThePin() {
        val store = CalculatorAccessStore(context, testFile)
        assertEquals(AccessResult.ENROLLED, store.submit("0042"))
        assertEquals(AccessResult.UNLOCKED, CalculatorAccessStore(context, testFile).submit("0042"))
        val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$testFile.xml")
        assertFalse(file.readText().contains(">0042<"))
    }

    @Test fun diagnosticsRequireSeparateConsentAndRevocationCannotBeAcknowledgedByAnOldReport() {
        val store = CalculatorAccessStore(context, testFile)
        assertFalse(store.diagnosticsChoice().second)
        store.submit("0042")
        assertFalse("PIN enrollment does not opt in", store.diagnosticsChoice().second)
        store.setDiagnosticsEnabled(true)
        val optedIn = store.diagnosticsChoice()
        assertTrue(CalculatorAccessStore(context, testFile).diagnosticsChoice().second)
        store.setDiagnosticsEnabled(false)
        store.acknowledgeDiagnostics(optedIn.first)
        assertTrue("Revocation still needs sending", store.diagnosticsPending())
        assertFalse(CalculatorAccessStore(context, testFile).diagnosticsChoice().second)
        store.acknowledgeDiagnostics(store.diagnosticsChoice().first)
        assertFalse(store.diagnosticsPending())
    }

    @Test fun diagnosticsContainOnlyDisclosedFieldsAndDoNotCollectForABlockedRoute() {
        val report = OwnerDiagnostics.report(context, Route.Direct)
        assertEquals(setOf("app_version", "manufacturer", "model", "android_version", "android_api", "network", "route", "permissions"),
            report.keys().asSequence().toSet())
        assertEquals(setOf("camera", "location", "notifications"), report.getJSONObject("permissions").keys().asSequence().toSet())
        try {
            OwnerDiagnostics.report(context, Route.Blocked("Waiting for Tor"))
            fail("Blocked routing must not produce a report")
        } catch (_: IllegalStateException) { }
    }

    @Test fun failureCountPersistsAndCorrectPinCannotBypassLockout() {
        CalculatorAccessStore(context, testFile).submit("0042")
        assertEquals(AccessResult.REJECTED, CalculatorAccessStore(context, testFile).submit("1111"))
        assertEquals(AccessResult.REJECTED, CalculatorAccessStore(context, testFile).submit("2222"))
        assertEquals(AccessResult.LOCKED, CalculatorAccessStore(context, testFile).submit("3333"))
        assertEquals(AccessResult.LOCKED, CalculatorAccessStore(context, testFile).submit("0042"))
        assertTrue(CalculatorAccessStore(context, testFile).state().locked)
    }

    @Test fun ownerRecoveryRequiresAcknowledgingTheCurrentLockout() {
        val store = CalculatorAccessStore(context, testFile)
        store.submit("0042")
        repeat(3) { store.submit("9999") }
        assertFalse(store.applyOwnerUnlock(3))
        assertTrue(store.applyOwnerUnlock(4))
        assertEquals(AccessResult.UNLOCKED, store.submit("0042"))
        repeat(3) { store.submit("9999") }
        assertFalse(store.applyOwnerUnlock(4))
        assertTrue(store.state().locked)
    }

    @Test fun messageNotificationTapDismissesWithoutOpeningAppOrReplyAction() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contact = Contact(id = 999, name = "Preview test", phone = "nostr:test")
        assertTrue("Notification permission is granted", MessageNotifier.canPostNotifications(context))
        MessageNotifier.notifyIncoming(context, contact, MessageType.TEXT, "Hello from the test")
        // NotificationManager posts asynchronously; wait for the OS to publish it.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var posted = manager.activeNotifications.firstOrNull { it.id == 5000 && it.tag == "conversation:999" }
        while (posted == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            posted = manager.activeNotifications.firstOrNull { it.id == 5000 && it.tag == "conversation:999" }
        }
        assertNotNull("OS posted the message notification", posted)
        val notification = posted!!.notification
        assertEquals("conversation:999", notification.shortcutId)
        val shortcut = context.getSystemService(android.content.pm.ShortcutManager::class.java)
            .dynamicShortcuts.firstOrNull { it.id == notification.shortcutId }
        assertNotNull("A published conversation shortcut supplies the compact avatar", shortcut)
        assertEquals("Preview test", shortcut!!.shortLabel.toString())
        assertFalse("Preview taps cannot launch an activity", notification.contentIntent.isActivity)
        assertTrue(notification.actions.isNullOrEmpty())
        assertEquals("Hello from the test", notification.extras.getCharSequence("android.text").toString())
        assertEquals("android.app.Notification\$MessagingStyle", notification.extras.getString("android.template"))
        assertNotNull("Contact avatar is supplied to the OS", notification.getLargeIcon())
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertNotNull("Sender has an avatar", style.messages.last().person?.icon)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        val underlyingPackage = device.currentPackageName
        device.openNotification()
        val preview = device.findObject(UiSelector().textContains("Hello from the test"))
        assertTrue("The preview is visible in Android's shade", preview.waitForExists(5_000))
        preview.click()
        val dismissedAt = SystemClock.elapsedRealtime() + 5_000
        while (manager.activeNotifications.any { it.tag == "conversation:999" } &&
            SystemClock.elapsedRealtime() < dismissedAt) SystemClock.sleep(30)
        assertFalse("A real tap removes the notification", manager.activeNotifications.any { it.tag == "conversation:999" })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("A notification tap never resumes Calculator",
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .none { it is MainActivity })
        }
        device.pressHome()
        assertEquals(underlyingPackage, device.currentPackageName)
    }

    @Test fun lateProfileRefreshUpdatesVisibleNameAndAvatarWithoutAddingMessages() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contact = Contact(id = 999, name = "Contact 12345678", phone = "nostr:fixture")
        MessageNotifier.cancel(context, contact.id)
        MessageNotifier.notifyIncoming(context, contact, MessageType.TEXT, "Keep this preview")
        SystemClock.sleep(300)
        MessageNotifier.refreshContact(context, contact.copy(name = "Exion"))
        SystemClock.sleep(300)
        val notification = manager.activeNotifications.first { it.tag == "conversation:999" }.notification
        val style = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals(1, style.messages.size)
        assertEquals("Exion", style.messages.last().person!!.name.toString())
        assertEquals("Keep this preview", style.messages.last().text.toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertFalse("Preview taps cannot launch an activity", notification.contentIntent.isActivity)
        assertTrue(notification.actions.isNullOrEmpty())
        MessageNotifier.cancel(context, contact.id)
    }

    @Test fun notificationsKeepBoundedHistorySeparateForEachConversation() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val first = Contact(id = 999, name = "First", phone = "nostr:first")
        val second = Contact(id = 1999, name = "Second", phone = "nostr:second")
        MessageNotifier.cancel(context, first.id)
        for (i in 1..7) {
            MessageNotifier.notifyIncoming(context, first, MessageType.TEXT, "Message $i")
            val end = SystemClock.elapsedRealtime() + 3_000
            while (manager.activeNotifications.firstOrNull { it.tag == "conversation:999" }
                    ?.notification?.extras?.getCharSequence("android.text")?.toString() != "Message $i" &&
                SystemClock.elapsedRealtime() < end) SystemClock.sleep(30)
        }
        MessageNotifier.notifyIncoming(context, second, MessageType.TEXT, "Separate chat")
        SystemClock.sleep(300)
        val posted = manager.activeNotifications.filter { it.id == 5000 }
        assertEquals(2, posted.count { it.tag in listOf("conversation:999", "conversation:1999") })
        val history = androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
            posted.first { it.tag == "conversation:999" }.notification)!!.messages
        assertEquals(listOf("Message 3", "Message 4", "Message 5", "Message 6", "Message 7"), history.map { it.text.toString() })
        val firstPreview = posted.first { it.tag == "conversation:999" }.notification
        assertFalse(firstPreview.contentIntent.isActivity)
        firstPreview.contentIntent.send()
        val dismissDeadline = SystemClock.elapsedRealtime() + 5_000
        while (manager.activeNotifications.any { it.tag == "conversation:999" } &&
            SystemClock.elapsedRealtime() < dismissDeadline) SystemClock.sleep(30)
        assertFalse(manager.activeNotifications.any { it.tag == "conversation:999" })
        assertTrue("Another conversation is not dismissed", manager.activeNotifications.any { it.tag == "conversation:1999" })
    }

    @Test fun landscapeCalculatorKeepsEveryKeyVisibleAndCalculates() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            device.setOrientationLeft()
            assertTrue(device.findObject(UiSelector().text("C")).waitForExists(5_000))
            device.waitForIdle()
            val keys = listOf("C", "()", "%", "÷", "7", "8", "9", "×", "4", "5", "6", "−", "1", "2", "3", "+", "+/−", "0", ".", "=")
            for (key in keys) {
                val button = device.findObject(UiSelector().text(key))
                assertTrue("Landscape key is visible: $key", button.exists())
                val bounds = button.bounds
                assertTrue("Landscape key is on screen: $key", bounds.left >= 0 && bounds.top >= 0 &&
                    bounds.right <= device.displayWidth && bounds.bottom <= device.displayHeight && !bounds.isEmpty)
            }
            for (key in listOf("C", "2", "+", "3", "×", "4", "=")) device.findObject(UiSelector().text(key)).click()
            assertTrue(device.findObject(UiSelector().text("14")).waitForExists(3_000))
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            device.pressHome()
        }
    }

    @Test fun recoveryUsesAnIndependentPersistentEncryptedIdentity() {
        val store = CalculatorAccessStore(context, testFile)
        val recovery = store.ownerRelayKeys()
        assertNotEquals(com.relay.app.transport.nostr.NostrIdentity.publicKeyHex(context), recovery.publicKey().toHex())
        assertEquals(recovery.publicKey().toHex(), CalculatorAccessStore(context, testFile).ownerRelayKeys().publicKey().toHex())
        val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$testFile.xml")
        assertFalse(file.readText().contains(recovery.secretKey().toHex()))
    }

    @Test fun tenMinuteSessionSurvivesHomeAndRotationThenExpiresToCalculator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assertFalse("UI smoke test needs a fresh app install", CalculatorAccessStore.get(context).state().configured)
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.findObject(UiSelector().text("C")).waitForExists(5_000))
        for (key in listOf("4", "8", "2", "6", "+")) device.findObject(UiSelector().text(key)).click()
        assertTrue(device.findObject(UiSelector().text("Account")).waitForExists(5_000))
        val store = CalculatorAccessStore.get(context)
        instrumentation.sendStatus(2, Bundle().apply { putString("calculator_test_installation", store.credentials().first) })
        device.findObject(UiSelector().text("Account")).click()
        val security = device.findObject(UiSelector().text("Security"))
        if (!security.waitForExists(3_000)) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 15)
        }
        assertTrue(security.waitForExists(3_000))
        security.click()
        val sharing = device.findObject(UiSelector().text("Share diagnostics with the app owner"))
        assertTrue(sharing.waitForExists(5_000))
        assertFalse("Opening settings does not opt in", store.diagnosticsChoice().second)
        if (BuildConfig.OWNER_PUBLIC_KEY.isNotBlank() || (BuildConfig.OWNER_SERVICE_URL.isNotBlank() && BuildConfig.OWNER_ENROLLMENT_TOKEN.isNotBlank())) {
            sharing.click()
            assertTrue(device.findObject(UiSelector().text("Share phone diagnostics?")).waitForExists(3_000))
            device.findObject(UiSelector().text("Keep off")).click()
            assertFalse("Dismissing disclosure keeps sharing off", store.diagnosticsChoice().second)
            sharing.click()
            device.findObject(UiSelector().text("Agree and share")).click()
            assertTrue("Explicit agreement opts in", store.diagnosticsChoice().second)
            sharing.click()
            assertFalse("Sharing can be stopped without a code or owner approval", store.diagnosticsChoice().second)
        } else {
            assertTrue(device.findObject(UiSelector().text("No Control Panel service is configured.")).exists())
            sharing.click()
            assertFalse("A preview without a service cannot opt into diagnostics", store.diagnosticsChoice().second)
            assertFalse(device.findObject(UiSelector().text("Share phone diagnostics?")).exists())
        }
        device.pressHome()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Return within ten minutes resumes messaging", device.findObject(UiSelector().textMatches("Security|Account")).waitForExists(5_000))
        assertFalse(device.findObject(UiSelector().text("C")).exists())
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().first()
            val session = androidx.lifecycle.ViewModelProvider(activity)[CalculatorSession::class.java]
            assertTrue(session.remainingMs() in 500_000L..600_000L)
            activity.recreate()
        }
        assertTrue("Rotation/recreation retains the fixed session", device.findObject(UiSelector().textMatches("Security|Account")).waitForExists(5_000))
        instrumentation.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().first()
            val session = androidx.lifecycle.ViewModelProvider(activity)[CalculatorSession::class.java]
            // Advance the expiry check to the exact deadline without waiting ten minutes in CI.
            session.expireIfNeeded(session.deadline)
        }
        assertTrue("Expiry closes private screens while the app is open", device.findObject(UiSelector().text("C")).waitForExists(5_000))
        assertFalse(device.findObject(UiSelector().text("Account")).exists())
        for (key in listOf("4", "8", "2", "6", "+")) device.findObject(UiSelector().text(key)).click()
        assertTrue("Original code starts another session", device.findObject(UiSelector().textMatches("Security|Account")).waitForExists(5_000))
        device.pressHome()
    }
}
