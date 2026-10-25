package com.relay.app.calls

import com.relay.app.call.CallState

/** The remote party of whatever call is on show, so the name survives every state, not just ringing. */
object CallPeer {
    fun of(state: CallState): String? = when (state) {
        is CallState.RingingIn -> state.call.peer
        is CallState.RingingOut -> state.call.peer
        is CallState.Connecting -> state.call.peer
        is CallState.Active -> state.call.peer
        is CallState.Ended, CallState.Idle -> null
    }
}
