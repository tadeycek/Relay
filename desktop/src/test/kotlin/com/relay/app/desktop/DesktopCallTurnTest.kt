package com.relay.app.desktop

import com.relay.app.call.*
import org.junit.Assert.*
import org.junit.Test

class DesktopCallTurnTest {
    @Test fun fetchExceptionFailsWithoutCredentialLogging() {
        val events = mutableListOf<CallEvent>()
        val port = DesktopCallTurnPort({ 100_000L }, { _, _ -> throw IllegalStateException("private value") }, events::add)
        port.request("call")
        assertEquals(listOf(CallEvent.IceServersFailed("call")), events)
        port.close()
    }

    @Test fun expiredCredentialFailsAndLateCancelledReplyIsIgnored() {
        val callbacks = mutableListOf<(List<IceServer>?, Long?) -> Unit>()
        val cancelled = mutableListOf<Boolean>()
        val events = mutableListOf<CallEvent>()
        val port = DesktopCallTurnPort({ 100_000L }, { _, reply ->
            callbacks.add(reply)
            val index = callbacks.lastIndex
            cancelled.add(false)
            AutoCloseable { cancelled[index] = true }
        }, events::add)
        port.request("old")
        port.request("new")
        assertTrue(cancelled[0])
        callbacks[0](listOf(IceServer(listOf("turns:example:443"))), 200_000L)
        assertTrue(events.isEmpty())
        callbacks[1](listOf(IceServer(listOf("turns:example:443"))), 100_001L)
        assertEquals(listOf(CallEvent.IceServersFailed("new")), events)
        port.close()
        assertTrue(cancelled[1])
        callbacks[1](listOf(IceServer(listOf("turns:example:443"))), 200_000L)
        assertEquals(1, events.size)
    }
}
