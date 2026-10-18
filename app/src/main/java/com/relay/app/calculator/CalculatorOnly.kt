package com.relay.app.calculator

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.app.NotificationManager
import androidx.work.WorkManager
import java.io.File
import java.security.KeyStore

/** Survives a crash during cleanup; checked before any messaging initialization. Clearing app data
 * or reinstalling can reset this local mode. Compiled APK code is not uninstalled by a data wipe. */
object CalculatorOnly {
    private fun marker(context: Context) = File(context.noBackupFilesDir, "calculator-only")
    fun enabled(context: Context) = marker(context).exists()
    fun arm(context: Context) {
        val file = marker(context)
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file).use { it.write(1); it.fd.sync() }
        context.stopService(android.content.Intent(context, com.relay.app.messaging.ConnectionService::class.java))
        WorkManager.getInstance(context).cancelAllWork().result.get()
        for (name in listOf("messaging.BootReceiver", "sms.PinExpiryReceiver", "sms.KeyRotationReceiver", "nfc.PairingCardService", "sms.LocationShareService", "messaging.ConnectionService")) {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, "com.relay.app.$name"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        if (clipboard?.primaryClipDescription?.label?.toString() == "Relay private message") {
            if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
        val shortcuts = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
        if (shortcuts.isNotEmpty()) androidx.core.content.pm.ShortcutManagerCompat.removeLongLivedShortcuts(context, shortcuts)
        androidx.core.content.pm.ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        val manager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
        manager?.pinnedShortcuts?.map { it.id }?.takeIf { it.isNotEmpty() }?.let { manager.disableShortcuts(it, "Messaging erased") }
    }
    /** Called before startup and after shutting down the messaging runtime. Repeatable. */
    fun clean(context: Context) {
        check(enabled(context))
        erasePrivateData(context, keep = "calculator-only")
    }

    /** Every database, file, preference and Keystore key the app holds, except [keep] in no-backup files. */
    internal fun erasePrivateData(context: Context, keep: String? = null) {
        context.databaseList().forEach { context.deleteDatabase(it) }
        listOf(context.filesDir, context.cacheDir, context.codeCacheDir, File(context.applicationInfo.dataDir, "shared_prefs")).forEach { dir ->
            dir.listFiles()?.forEach { check(it.deleteRecursively()) { "Couldn't erase private app data" } }
        }
        context.noBackupFilesDir.listFiles()?.filter { it.name != keep }?.forEach { it.deleteRecursively() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.aliases().toList().forEach { store.deleteEntry(it) }
    }
}

/**
 * Too many wrong calculator codes: erase all of Relay's data on this phone, so it is exactly as if
 * Relay had just been installed. The calculator stays; messaging needs a new code and re-pairing.
 */
object RelayWipe {
    fun wipe(context: Context) {
        val app = context.applicationContext
        // The system's "Clear data": databases, files, preferences and this app's Keystore keys, then
        // the process ends. The manual erase is only a fallback for when the system refuses.
        val cleared = runCatching {
            (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()
        }.getOrDefault(false)
        if (!cleared) {
            runCatching { CalculatorOnly.erasePrivateData(app) }
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
