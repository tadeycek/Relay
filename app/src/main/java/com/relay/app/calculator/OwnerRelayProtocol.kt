package com.relay.app.calculator

import org.json.JSONObject

/** The transport authenticates the seal; this additionally pins the owner and installation. */
object OwnerRelayProtocol {
    const val NAME = "calculator_owner_v1"
    data class State(val acknowledged: Long, val unlockThrough: Long, val diagnosticsRevision: Long)

    fun state(body: String, sender: String, owner: String, installation: String): State? {
        if (owner.isBlank() || !sender.equals(owner, ignoreCase = true)) return null
        return runCatching {
            val json = JSONObject(body)
            require(json.getString("protocol") == NAME && json.getString("type") == "state")
            require(json.getString("device_id") == installation)
            val ack = json.getLong("acknowledged_sequence")
            val unlock = json.getLong("unlock_through_sequence")
            val diagnostics = json.getLong("diagnostics_revision")
            require(ack >= 0 && unlock in 0..ack && diagnostics >= 0)
            State(ack, unlock, diagnostics)
        }.getOrNull()
    }
}
