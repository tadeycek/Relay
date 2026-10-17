package com.relay.app.calculator

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.messaging.ConnectionService
import com.relay.app.media.GifPreparation
import com.relay.app.mms.MediaCompressor
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalFeatureRegressionTest {
    @Test fun appCannotStartTheRetiredConnectionForegroundService() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val info=context.packageManager.getServiceInfo(ComponentName(context,ConnectionService::class.java),PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(info.enabled)
        ConnectionService.onAppForeground(context)
        ConnectionService.onAppBackground(context)
        ConnectionService.start(context)
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        assertNull(manager.getNotificationChannel("relay_connection"))
        assertFalse(manager.activeNotifications.any { it.id==1001 })
        val permissions=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING"))
    }
    @Test fun gifPreparationPreservesEveryFrameByte() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // Two-frame 1x1 GIF; retaining the complete original file preserves animation data.
        val hex="47494638396101000100800000000000ffffff21ff0b4e45545343415045322e30030100000021f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b"
        val bytes=hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val file=File(context.cacheDir,"animation-fixture.gif").apply { writeBytes(bytes) }
        try {
            val result=GifPreparation.image(context,Uri.fromFile(file),"image/gif")
            assertTrue(result is MediaCompressor.Result.Success)
            result as MediaCompressor.Result.Success
            try {
                assertEquals("image/gif",result.mimeType)
                assertArrayEquals(bytes,result.file.readBytes())
                if(android.os.Build.VERSION.SDK_INT>=28) {
                    val drawable=android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(result.file))
                    assertTrue(drawable is android.graphics.drawable.AnimatedImageDrawable)
                }
            } finally { result.file.delete() }
        } finally { file.delete() }
    }
}
