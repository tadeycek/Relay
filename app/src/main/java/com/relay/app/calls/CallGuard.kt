package com.relay.app.calls

/** Why this device will not start or answer a call. */
enum class CallStartBlock { NONE, MIC_DENIED, BUSY, OFFLINE, PROFILE_LOCKED }

/**
 * The decisions made before a call starts, kept out of the Activity so a refused microphone, a second
 * call, a dead network and the calculator lock are all testable without Android.
 */
object CallGuard {

    fun startBlock(micGranted: Boolean, otherCallLive: Boolean, online: Boolean, callsEnabled: Boolean): CallStartBlock = when {
        !callsEnabled -> CallStartBlock.PROFILE_LOCKED
        otherCallLive -> CallStartBlock.BUSY
        !online -> CallStartBlock.OFFLINE
        !micGranted -> CallStartBlock.MIC_DENIED
        else -> CallStartBlock.NONE
    }

    fun answerBlock(micGranted: Boolean, callsEnabled: Boolean): CallStartBlock = when {
        !callsEnabled -> CallStartBlock.PROFILE_LOCKED
        !micGranted -> CallStartBlock.MIC_DENIED
        else -> CallStartBlock.NONE
    }

    fun startMessage(block: CallStartBlock): String? = when (block) {
        CallStartBlock.NONE -> null
        CallStartBlock.MIC_DENIED -> "Microphone access is needed for calls. Allow it in Settings."
        CallStartBlock.BUSY -> "You're already on a call."
        CallStartBlock.OFFLINE -> "No connection. Calls need the internet."
        CallStartBlock.PROFILE_LOCKED -> "Calls are unavailable while Relay is locked."
    }
}
