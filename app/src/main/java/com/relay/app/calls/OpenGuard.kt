package com.relay.app.calls

/**
 * Releases what [WebRtcPeer.open] acquired when a later step throws before the session is stored.
 * Cleanups run in reverse acquisition order; one failing cleanup never skips the others.
 */
internal class OpenGuard {
    private val cleanups = ArrayDeque<() -> Unit>()

    fun <T> track(resource: T, cleanup: (T) -> Unit): T {
        cleanups.addLast { cleanup(resource) }
        return resource
    }

    fun commit() = cleanups.clear()

    fun rollback() {
        while (cleanups.isNotEmpty()) runCatching { cleanups.removeLast()() }
    }
}
