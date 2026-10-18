package com.relay.app.util

import android.content.Context
import com.relay.app.media.DefaultBlossomServers
import com.relay.app.transport.nostr.DefaultRelays

class RelayPreferences(context: Context) {
    private val appContext = context.applicationContext

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoApproveLocationRequests: Boolean
        get() = prefs.getBoolean(KEY_AUTO_APPROVE, false)
        set(v) { prefs.edit().putBoolean(KEY_AUTO_APPROVE, v).apply() }

    var notifyOnAutoShare: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_AUTO_SHARE, true)
        set(v) { prefs.edit().putBoolean(KEY_NOTIFY_AUTO_SHARE, v).apply() }

    var locationRequestFrom: String
        get() = prefs.getString(KEY_LOCATION_FROM, FROM_ALL) ?: FROM_ALL
        set(v) { prefs.edit().putString(KEY_LOCATION_FROM, v).apply() }

    var theme: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) { prefs.edit().putString(KEY_THEME, v).apply() }

    var unlockDance: Boolean
        get() = prefs.getBoolean("unlock_dance", false)
        set(v) { prefs.edit().putBoolean("unlock_dance", v).apply() }

    var appLock: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK, false)
        set(v) { prefs.edit().putBoolean(KEY_APP_LOCK, v).apply() }

    var incomingPinNotification: Boolean
        get() = prefs.getBoolean(KEY_PIN_NOTIF, true)
        set(v) { prefs.edit().putBoolean(KEY_PIN_NOTIF, v).apply() }

    var incomingMessageNotification: Boolean
        get() = prefs.getBoolean(KEY_MSG_NOTIF, true)
        set(v) { prefs.edit().putBoolean(KEY_MSG_NOTIF, v).apply() }

    var defaultPinExpiry: String
        get() = prefs.getString(KEY_DEFAULT_PIN_EXPIRY, "never") ?: "never"
        set(v) { prefs.edit().putString(KEY_DEFAULT_PIN_EXPIRY, v).apply() }

    var shareActivity: Boolean
        get() = prefs.getBoolean("share_activity", true)
        set(v) { prefs.edit().putBoolean("share_activity",v).apply(); com.relay.app.messaging.MessagingRuntime.launchIo { com.relay.app.messaging.PresenceSignals.heartbeat(appContext) } }

    var typingIndicators: Boolean
        get() = prefs.getBoolean("typing_indicators", true)
        set(v) { prefs.edit().putBoolean("typing_indicators", v).apply() }

    var readReceipts: Boolean
        get() = prefs.getBoolean(KEY_READ_RECEIPTS, true)
        set(v) { prefs.edit().putBoolean(KEY_READ_RECEIPTS, v).apply() }

    var dndEnabled: Boolean
        get() = prefs.getBoolean(KEY_DND_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_DND_ENABLED, v).apply() }

    var dndStartHour: Int
        get() = prefs.getInt(KEY_DND_START_HOUR, 22)
        set(v) { prefs.edit().putInt(KEY_DND_START_HOUR, v.coerceIn(0, 23)).apply() }

    var dndEndHour: Int
        get() = prefs.getInt(KEY_DND_END_HOUR, 7)
        set(v) { prefs.edit().putInt(KEY_DND_END_HOUR, v.coerceIn(0, 23)).apply() }

    /** Self-profile shown on your own QR code (Contacts -> QR icon -> My Code). Empty until set. */
    var myName: String
        get() = prefs.getString(KEY_MY_NAME, "") ?: ""
        set(v) {
            if (myName == v) return
            check(prefs.edit().putString(KEY_MY_NAME, v)
                .putLong("profile_name_revision", Math.addExact(profileNameRevision, 1L)).commit())
            com.relay.app.media.ProfilePhotoSync.syncPending(appContext)
        }

    val profileNameRevision: Long get() = prefs.getLong("profile_name_revision", 0L)

    /** Epoch millis after which the retired identity key (kept only to decrypt late-arriving
     *  messages right after a rotation) is permanently deleted. 0 = no retired key on file. */
    var previousKeyExpiresAt: Long
        get() = prefs.getLong(KEY_PREV_KEY_EXPIRES_AT, 0L)
        set(v) { prefs.edit().putLong(KEY_PREV_KEY_EXPIRES_AT, v).apply() }

    var lastKeyRotationAt: Long
        get() = prefs.getLong(KEY_LAST_ROTATION_AT, 0L)
        set(v) { prefs.edit().putLong(KEY_LAST_ROTATION_AT, v).apply() }

    /** Nostr relays used for sending and receiving (one URL per stored line). Falls back to the built-in defaults. */
    var nostrRelays: List<String>
        get() = prefs.getString(KEY_NOSTR_RELAYS, null)
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: DefaultRelays.CLEARNET
        set(v) { prefs.edit().putString(KEY_NOSTR_RELAYS, v.joinToString("\n")).apply() }

    /** Whether the one-time Android 13+ notification permission prompt has been shown. */
    var askedNotificationPermission: Boolean
        get() = prefs.getBoolean(KEY_ASKED_NOTIF_PERMISSION, false)
        set(v) { prefs.edit().putBoolean(KEY_ASKED_NOTIF_PERMISSION, v).apply() }

    /** Route relay and media-server connections through Tor (Orbot). Off by default: slower and heavier on battery. */
    var torEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOR_ENABLED, false)
        set(v) { prefs.edit().putBoolean(KEY_TOR_ENABLED, v).apply() }

    /** If Tor is on but unreachable, connect directly instead of waiting. Off by default (fail closed). */
    var torFallbackToDirect: Boolean
        get() = prefs.getBoolean(KEY_TOR_FALLBACK, false)
        set(v) { prefs.edit().putBoolean(KEY_TOR_FALLBACK, v).apply() }

    /**
     * Send through Relay's own delivery server (pilot builds only) to contacts on it. On by default;
     * receiving from the server doesn't depend on it, so switching it off strands nothing.
     */
    var mailboxEnabled: Boolean
        get() = prefs.getBoolean(KEY_MAILBOX_ENABLED, true)
        set(v) { prefs.edit().putBoolean(KEY_MAILBOX_ENABLED, v).apply() }

    /** Blossom media servers tried in order for uploads (one URL per stored line). Falls back to defaults. */
    var blossomServers: List<String>
        get() = prefs.getString(KEY_BLOSSOM_SERVERS, null)
            ?.lines()?.map { it.trim() }?.filter { it.startsWith("https://") }
            ?.takeIf { it.isNotEmpty() }
            ?: DefaultBlossomServers.LIST
        set(v) { prefs.edit().putString(KEY_BLOSSOM_SERVERS, v.joinToString("\n")).apply() }

    /**
     * Compatibility preference: continuous foreground delivery was retired to remove its notice.
     * Old stored opt-ins cannot re-enable the retired service.
     */
    var backgroundConnection: Boolean
        get() = false
        set(v) { prefs.edit().putBoolean(KEY_BACKGROUND_CONNECTION, false).apply() }

    companion object {
        private const val KEY_ASKED_NOTIF_PERMISSION = "asked_notification_permission"
        private const val KEY_TOR_ENABLED = "tor_enabled"
        private const val KEY_TOR_FALLBACK = "tor_fallback_direct"
        private const val KEY_MAILBOX_ENABLED = "mailbox_enabled"
        private const val KEY_BLOSSOM_SERVERS = "blossom_servers"
        private const val KEY_BACKGROUND_CONNECTION = "background_connection"
        private const val KEY_NOSTR_RELAYS = "nostr_relays"
        private const val PREFS_NAME = "relay_settings"
        private const val KEY_AUTO_APPROVE = "auto_approve_location"
        private const val KEY_NOTIFY_AUTO_SHARE = "notify_auto_share"
        private const val KEY_LOCATION_FROM = "location_request_from"
        private const val KEY_THEME = "theme"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_PIN_NOTIF = "incoming_pin_notif"
        private const val KEY_MSG_NOTIF = "incoming_msg_notif"
        private const val KEY_DEFAULT_PIN_EXPIRY = "default_pin_expiry"
        private const val KEY_READ_RECEIPTS = "read_receipts"
        private const val KEY_DND_ENABLED = "dnd_enabled"
        private const val KEY_DND_START_HOUR = "dnd_start_hour"
        private const val KEY_DND_END_HOUR = "dnd_end_hour"
        private const val KEY_MY_NAME = "my_name"
        private const val KEY_PREV_KEY_EXPIRES_AT = "prev_identity_key_expires_at"
        private const val KEY_LAST_ROTATION_AT = "last_identity_key_rotation_at"

        const val FROM_ALL = "all"
        const val FROM_NOBODY = "nobody"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"

        const val LOCATION_REQUEST_CHANNEL = com.relay.app.messaging.LauncherBadges.LOCATION
        const val SECURITY_ALERT_CHANNEL = com.relay.app.messaging.LauncherBadges.SECURITY
    }
}
