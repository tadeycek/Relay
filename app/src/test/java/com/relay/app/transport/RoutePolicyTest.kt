package com.relay.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyTest {

    @Test
    fun torOffAlwaysGoesDirectRegardlessOfProxy() {
        assertEquals(Route.Direct, RoutePolicy.decide(torEnabled = false, proxyReachable = true, fallbackToDirect = false))
        assertEquals(Route.Direct, RoutePolicy.decide(torEnabled = false, proxyReachable = false, fallbackToDirect = false))
    }

    @Test
    fun torOnAndReachableUsesTheProxy() {
        assertEquals(
            Route.Socks("127.0.0.1", 9050),
            RoutePolicy.decide(torEnabled = true, proxyReachable = true, fallbackToDirect = false),
        )
        assertEquals(
            "reachable proxy wins even if fallback is allowed",
            Route.Socks("127.0.0.1", 9050),
            RoutePolicy.decide(torEnabled = true, proxyReachable = true, fallbackToDirect = true),
        )
    }

    @Test
    fun torOnAndUnreachableFailsClosedByDefault() {
        val r = RoutePolicy.decide(torEnabled = true, proxyReachable = false, fallbackToDirect = false)
        assertTrue("must not fall back to a direct connection", r is Route.Blocked)
    }

    @Test
    fun directFallbackOnlyWhenTheUserExplicitlyAllowedIt() {
        assertEquals(Route.Direct, RoutePolicy.decide(torEnabled = true, proxyReachable = false, fallbackToDirect = true))
    }

    @Test
    fun customHostAndPortAreCarriedThrough() {
        assertEquals(
            Route.Socks("10.0.0.5", 9150),
            RoutePolicy.decide(true, true, false, host = "10.0.0.5", port = 9150),
        )
    }
}
