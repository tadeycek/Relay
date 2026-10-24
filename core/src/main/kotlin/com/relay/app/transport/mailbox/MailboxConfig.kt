package com.relay.app.transport.mailbox

/**
 * The pilot delivery server built into this app. The host application installs its build-time URL
 * once at process start (Android: `.local/mailbox.properties` via `BuildConfig`, gitignored); builds
 * without one behave exactly as before.
 */
object MailboxConfig {
    @Volatile private var configured: String = ""

    /** Called by the host before any mailbox use. Android calls it from the Application constructor. */
    fun install(rawUrl: String) { configured = rawUrl }

    val url: String get() = configured.trim().trimEnd('/')
    val available: Boolean get() = url.startsWith("https://")
}
