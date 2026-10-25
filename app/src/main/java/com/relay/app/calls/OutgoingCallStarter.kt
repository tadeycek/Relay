package com.relay.app.calls

/**
 * Outgoing-call permission flow with every side effect injected: ask for `RECORD_AUDIO` first, place the
 * call only after a grant, report a refusal, and never place on a stray or repeated result.
 */
class OutgoingCallStarter(
    private val hasMic: () -> Boolean,
    private val requestMic: () -> Unit,
    private val place: (pubkey: String, name: String?) -> Unit,
    private val denied: () -> Unit,
) {
    private var pending: Pair<String, String?>? = null

    fun start(pubkey: String, name: String?) {
        if (hasMic()) {
            pending = null
            place(pubkey, name)
        } else {
            pending = pubkey to name
            requestMic()
        }
    }

    fun onResult(granted: Boolean) {
        val target = pending ?: return
        pending = null
        if (granted) place(target.first, target.second) else denied()
    }
}
