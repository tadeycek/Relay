package com.relay.app.calls

import com.relay.app.call.CallState

enum class FgsType { PHONE_CALL, MICROPHONE }

enum class ServiceStage { RINGING, IN_CALL }

/**
 * Which foreground-service types the call service may hold. Android 14 refuses to start a microphone
 * foreground service from the background, and a ringing call opens no microphone, so the service starts
 * with `phoneCall` only and is upgraded to `phoneCall|microphone` after accept (Connecting/Active),
 * and only when RECORD_AUDIO is granted.
 */
object CallServicePolicy {
    fun typesFor(sdk: Int, stage: ServiceStage, micGranted: Boolean): List<FgsType> = when {
        sdk < 29 -> emptyList() // typed foreground services do not exist
        sdk < 30 -> listOf(FgsType.PHONE_CALL) // FOREGROUND_SERVICE_TYPE_MICROPHONE is API 30
        stage == ServiceStage.IN_CALL && micGranted -> listOf(FgsType.PHONE_CALL, FgsType.MICROPHONE)
        else -> listOf(FgsType.PHONE_CALL)
    }

    fun stageFor(state: CallState): ServiceStage? = when (state) {
        is CallState.RingingIn, is CallState.RingingOut -> ServiceStage.RINGING
        is CallState.Connecting, is CallState.Active -> ServiceStage.IN_CALL
        is CallState.Ended, CallState.Idle -> null
    }
}
