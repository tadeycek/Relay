package com.relay.app.calculator

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import com.relay.app.messaging.LauncherBadges
import org.junit.Assert.*
import org.junit.Test

/** Requires an isolated test install: this exercises migration, not an OEM launcher's rendering. */
class LauncherBadgeRegressionTest {
    @Test fun migrateExistingChannelsWithoutResettingSilentOrBlockedPreferences() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val manager=context.getSystemService(NotificationManager::class.java)
        val oldIds=listOf("relay_messages","relay_location_requests","relay_security_alerts")
        val newIds=listOf(LauncherBadges.MESSAGES,LauncherBadges.LOCATION,LauncherBadges.SECURITY)
        val levels=listOf(NotificationManager.IMPORTANCE_LOW,NotificationManager.IMPORTANCE_NONE,NotificationManager.IMPORTANCE_DEFAULT)
        oldIds.zip(newIds).forEach { (old,new) -> manager.deleteNotificationChannel(old); manager.deleteNotificationChannel(new) }
        try {
            oldIds.forEachIndexed { i,id -> manager.createNotificationChannel(NotificationChannel(id,"Fixture",levels[i]).apply {
                setShowBadge(true); setSound(null,null); enableVibration(false)
            }) }
            LauncherBadges.disable(context)
            newIds.forEachIndexed { i,id ->
                val channel=manager.getNotificationChannel(id)
                assertFalse(channel.canShowBadge())
                assertEquals(levels[i],channel.importance)
                assertNull(channel.sound)
                assertFalse(channel.shouldVibrate())
            }
            LauncherBadges.disable(context) // Idempotent; existing settings are retained.
            assertFalse(manager.getNotificationChannel(LauncherBadges.MESSAGES).canShowBadge())
        } finally { oldIds.forEach(manager::deleteNotificationChannel); newIds.forEach(manager::deleteNotificationChannel) }
    }
}
