package com.relay.app.transport

import android.content.Context
import com.relay.app.util.RelayPreferences
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Tor support via an external SOCKS proxy (Orbot listening on 127.0.0.1:9050). An embedded Tor is a
 * possible later step; using Orbot keeps the app small and lets Orbot update itself.
 *
 * Reachability is probed with a plain TCP connect to the SOCKS port. That proves something is
 * listening, not that Tor has finished bootstrapping, so connections can still fail right after
 * Orbot starts; the transport's reconnect loop handles that.
 */
object TorControl {

    private const val PROBE_TIMEOUT_MS = 1_500

    fun route(context: Context): Route {
        val prefs = RelayPreferences(context)
        if (!prefs.torEnabled) return Route.Direct
        return RoutePolicy.decide(
            torEnabled = true,
            proxyReachable = isProxyReachable(RoutePolicy.DEFAULT_SOCKS_HOST, RoutePolicy.DEFAULT_SOCKS_PORT),
            fallbackToDirect = prefs.torFallbackToDirect,
        )
    }

    /** A java.net proxy for HTTP(S) downloads/uploads. The hostname is left unresolved so Tor resolves DNS. */
    fun javaProxy(route: Route.Socks): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(route.host, route.port))

    fun isProxyReachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    /** Orbot's package name, to offer installing or opening it. */
    const val ORBOT_PACKAGE = "org.torproject.android"
}
