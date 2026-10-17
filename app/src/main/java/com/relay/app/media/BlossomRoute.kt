package com.relay.app.media

import android.content.Context
import com.relay.app.transport.Route
import com.relay.app.transport.TorControl

/** Tor-required-but-down message shown when a media transfer is refused rather than made directly. */
const val TOR_UNAVAILABLE_MESSAGE = "Tor is turned on but isn't running. Start Orbot, or turn Tor off in Settings."

/**
 * A [BlossomClient] that follows the user's Tor setting, or null if Tor is required but unavailable
 * (fail closed: a media transfer must never quietly bypass Tor and reveal the user's IP).
 */
internal fun blossomClientFor(context: Context): BlossomClient? = when (val route = TorControl.route(context)) {
    Route.Direct -> BlossomClient()
    is Route.Socks -> BlossomClient(proxy = TorControl.javaProxy(route))
    is Route.Blocked -> null
}
