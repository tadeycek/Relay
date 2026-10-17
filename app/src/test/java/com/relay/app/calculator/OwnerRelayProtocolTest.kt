package com.relay.app.calculator

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OwnerRelayProtocolTest {
    private val owner = "a".repeat(64)
    private fun state() = JSONObject().put("protocol", OwnerRelayProtocol.NAME).put("type", "state")
        .put("device_id", "installation-a").put("acknowledged_sequence", 12)
        .put("unlock_through_sequence", 10).put("diagnostics_revision", 2)

    @Test fun acceptsOnlyPinnedOwnerAndMatchingInstallation() {
        assertEquals(10L, OwnerRelayProtocol.state(state().toString(), owner, owner, "installation-a")!!.unlockThrough)
        assertNull(OwnerRelayProtocol.state(state().toString(), "b".repeat(64), owner, "installation-a"))
        assertNull(OwnerRelayProtocol.state(state().toString(), owner, owner, "installation-b"))
        assertNull(OwnerRelayProtocol.state(state().toString(), owner, "", "installation-a"))
    }

    @Test fun rejectsUnknownProtocolAndMalformedCounters() {
        for (bad in listOf(state().put("protocol", "other"), state().put("type", "report"),
            state().put("acknowledged_sequence", -1), state().put("unlock_through_sequence", 13),
            state().put("diagnostics_revision", -1))) {
            assertNull(OwnerRelayProtocol.state(bad.toString(), owner, owner, "installation-a"))
        }
        assertNull(OwnerRelayProtocol.state("garbage", owner, owner, "installation-a"))
    }
}
