package com.relay.app.calls

import com.relay.app.call.CallOutcome
import com.relay.app.call.CallReasons
import com.relay.app.call.CallState

/** Which of the four call screens is on show. */
enum class CallStage { INCOMING, OUTGOING, CONNECTING, ACTIVE, ENDED }

/**
 * Everything a call screen needs, with no Android type in it. The Activity only draws this and sends
 * button presses back to [CallHostCore].
 */
data class CallUiModel(
    val stage: CallStage,
    val callId: String,
    val peerKey: String,
    val incoming: Boolean,
    val title: String,
    val subtitle: String,
    val durationMs: Long?,
    val muted: Boolean,
    val speakerOn: Boolean,
    val outcome: CallOutcome?,
    val reason: String?,
) {
    val canAccept: Boolean get() = stage == CallStage.INCOMING
    val canDecline: Boolean get() = stage == CallStage.INCOMING
    val canCancel: Boolean get() = stage == CallStage.OUTGOING
    val canMute: Boolean get() = stage == CallStage.ACTIVE
    val canToggleSpeaker: Boolean get() = stage == CallStage.ACTIVE
    val canHangUp: Boolean get() = stage != CallStage.ENDED
    val showDuration: Boolean get() = stage == CallStage.ACTIVE
}

object CallUiModelFactory {

    /** Null for [CallState.Idle] (nothing to show). */
    fun from(
        state: CallState,
        muted: Boolean,
        speakerOn: Boolean,
        nowMs: Long,
        peerName: String?,
        previewsHidden: Boolean,
    ): CallUiModel? = when (state) {
        CallState.Idle -> null

        is CallState.RingingIn -> CallUiModel(
            stage = CallStage.INCOMING,
            callId = state.call.id,
            peerKey = state.call.peer,
            incoming = true,
            // With previews hidden the lock screen must not name the caller (06 §4).
            title = if (previewsHidden) "Incoming call" else peerName ?: "Incoming call",
            subtitle = "Incoming call",
            durationMs = null,
            muted = muted,
            speakerOn = speakerOn,
            outcome = null,
            reason = null,
        )

        is CallState.RingingOut -> CallUiModel(
            stage = CallStage.OUTGOING,
            callId = state.call.id,
            peerKey = state.call.peer,
            incoming = false,
            title = peerName ?: "Calling",
            subtitle = if (state.calleeRinging) "Ringing…" else "Calling…",
            durationMs = null,
            muted = muted,
            speakerOn = speakerOn,
            outcome = null,
            reason = null,
        )

        is CallState.Connecting -> CallUiModel(
            stage = CallStage.CONNECTING,
            callId = state.call.id,
            peerKey = state.call.peer,
            incoming = state.call.incoming,
            title = peerName ?: "Call in progress",
            subtitle = "Connecting…",
            durationMs = null,
            muted = muted,
            speakerOn = speakerOn,
            outcome = null,
            reason = null,
        )

        is CallState.Active -> CallUiModel(
            stage = CallStage.ACTIVE,
            callId = state.call.id,
            peerKey = state.call.peer,
            incoming = state.call.incoming,
            title = peerName ?: "Call in progress",
            subtitle = if (state.restarting) "Reconnecting…" else duration(nowMs - state.connectedAtMs),
            durationMs = (nowMs - state.connectedAtMs).coerceAtLeast(0),
            muted = muted,
            speakerOn = speakerOn,
            outcome = null,
            reason = null,
        )

        is CallState.Ended -> CallUiModel(
            stage = CallStage.ENDED,
            callId = state.call.id,
            peerKey = state.call.peer,
            incoming = state.call.incoming,
            title = endTitle(state.outcome, state.reason),
            subtitle = if (state.outcome == CallOutcome.COMPLETED) "Call ended" else "",
            durationMs = null,
            muted = muted,
            speakerOn = speakerOn,
            outcome = state.outcome,
            reason = state.reason,
        )
    }

    /** `0:07`, `12:34`, `1:02:03`. */
    fun duration(ms: Long): String {
        val total = (ms.coerceAtLeast(0)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * What an ended call says happened. Honest states matter here: TURN unavailable and an unreachable
     * peer must not both read as "call ended".
     */
    fun failureText(outcome: CallOutcome, reason: String?): String? {
        if (outcome != CallOutcome.FAILED) return null
        return when (reason) {
            CallReasons.TURN_FAILED -> "Couldn't reach the call service"
            CallReasons.CONNECT_TIMEOUT, CallReasons.ICE_FAILED, CallReasons.NO_OFFER -> "The other person couldn't be reached"
            CallReasons.RESTART_TIMEOUT -> "The connection was lost"
            CallReasons.PEER_ERROR -> "The call failed on this device"
            else -> "Call failed"
        }
    }

    fun endTitle(outcome: CallOutcome, reason: String?): String = failureText(outcome, reason) ?: when (outcome) {
        CallOutcome.MISSED -> "Missed call"
        CallOutcome.DECLINED -> "Call declined"
        CallOutcome.BUSY -> "Busy"
        CallOutcome.NO_ANSWER -> "No answer"
        CallOutcome.CANCELLED -> "Call cancelled"
        CallOutcome.COMPLETED -> "Call ended"
        CallOutcome.ANSWERED_ELSEWHERE -> "Answered on another device"
        CallOutcome.FAILED -> "Call failed"
        CallOutcome.GLARE_LOST, CallOutcome.LOST_RACE -> "Call ended"
    }
}
