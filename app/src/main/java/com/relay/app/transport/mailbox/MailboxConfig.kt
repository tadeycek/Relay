package com.relay.app.transport.mailbox

import com.relay.app.BuildConfig

/**
 * The pilot delivery server built into this APK. It comes from `.local/mailbox.properties`
 * (`url=https://...`, gitignored) at build time; builds without one behave exactly as before.
 */
object MailboxConfig {
    val url: String get() = BuildConfig.MAILBOX_URL.trim().trimEnd('/')
    val available: Boolean get() = url.startsWith("https://")
}
