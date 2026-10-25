package com.relay.app.calls

/** Audio-focus changes the call cares about, decoupled from the platform ints so the rule is pure. */
enum class FocusEvent { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

enum class FocusAction { NONE, MUTE_UNTIL_REGAINED, MUTE_UNTIL_USER_UNMUTES, UNMUTE }

/**
 * The documented audio-focus rule (WP13 / G4):
 *
 *  - Focus matters only once media can flow (Connecting/Active). While ringing nothing is captured.
 *  - Transient loss (another app or a cellular ring briefly takes focus): mute the microphone and
 *    unmute when focus returns — but only if the focus rule muted it, never undo the user's own mute.
 *  - Ducking is ignored: a voice call must stay audible.
 *  - Permanent loss: mute and keep it muted until the user unmutes. The call is never ended
 *    automatically, because another app grabbing focus must not silently drop a call; hanging up
 *    stays the user's choice, and the muted state is visible in the call screen.
 */
object CallFocusPolicy {
    fun eventFor(change: Int): FocusEvent? = when (change) {
        1 -> FocusEvent.GAIN // AudioManager.AUDIOFOCUS_GAIN
        -1 -> FocusEvent.LOSS // AUDIOFOCUS_LOSS
        -2 -> FocusEvent.LOSS_TRANSIENT // AUDIOFOCUS_LOSS_TRANSIENT
        -3 -> FocusEvent.LOSS_TRANSIENT_CAN_DUCK // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        else -> null
    }

    fun decide(event: FocusEvent, callLive: Boolean, autoMuted: Boolean): FocusAction = when (event) {
        FocusEvent.GAIN -> if (autoMuted) FocusAction.UNMUTE else FocusAction.NONE
        else -> when {
            !callLive -> FocusAction.NONE
            event == FocusEvent.LOSS -> FocusAction.MUTE_UNTIL_USER_UNMUTES
            event == FocusEvent.LOSS_TRANSIENT -> FocusAction.MUTE_UNTIL_REGAINED
            else -> FocusAction.NONE
        }
    }
}
