package com.relay.app.transport

/** How outgoing network connections are to be made right now. */
sealed class Route {
    /** Connect straight out. */
    data object Direct : Route()

    /** Route everything through a local SOCKS5 proxy (Orbot / Tor). */
    data class Socks(val host: String, val port: Int) : Route()

    /** Tor is required but not available: make no connection at all. */
    data class Blocked(val reason: String) : Route()
}

/**
 * Decides the [Route]. Pure, so the "fail closed" rule is unit-tested: with Tor on and the proxy
 * unreachable, nothing may fall back to a direct connection unless the user explicitly chose that.
 */
object RoutePolicy {

    const val DEFAULT_SOCKS_HOST = "127.0.0.1"
    const val DEFAULT_SOCKS_PORT = 9050

    fun decide(
        torEnabled: Boolean,
        proxyReachable: Boolean,
        fallbackToDirect: Boolean,
        host: String = DEFAULT_SOCKS_HOST,
        port: Int = DEFAULT_SOCKS_PORT,
    ): Route = when {
        !torEnabled -> Route.Direct
        proxyReachable -> Route.Socks(host, port)
        fallbackToDirect -> Route.Direct
        else -> Route.Blocked("Tor is turned on but isn't running. Start Orbot, or turn Tor off in Settings.")
    }
}
