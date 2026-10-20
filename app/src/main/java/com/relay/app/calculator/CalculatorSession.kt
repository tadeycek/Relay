package com.relay.app.calculator

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Survives rotation, but never process death, backup, or a fresh application launch. */
class CalculatorSession : ViewModel() {
    var deadline by mutableLongStateOf(0L)
        private set

    // Only an explicit, in-flight picker may retain the private navigation tree. Never persisted.
    var transitionUntil by mutableLongStateOf(0L)
        private set
    var transitionSlot by mutableIntStateOf(-1)
        private set
    fun startTransition(enabled: Boolean) {
        transitionSlot = if (enabled) UnlockLoadingPolicy.pick() else -1
        transitionUntil = if (enabled) SystemClock.elapsedRealtime() + UnlockLoadingPolicy.DURATION_MS else 0
    }
    fun finishTransition() { transitionUntil = 0 }
    var pickerOpen = false
    fun keepChatForPicker() = SessionPolicy.retainChatForPicker(pickerOpen, deadline, SystemClock.elapsedRealtime())

    fun unlock() { deadline = SystemClock.elapsedRealtime() + SessionPolicy.DURATION_MS }
    fun remainingMs() = SessionPolicy.remainingMs(deadline, SystemClock.elapsedRealtime())
    fun isActive() = remainingMs() > 0

    fun expireIfNeeded(now: Long = SystemClock.elapsedRealtime()) {
        if (SessionPolicy.remainingMs(deadline, now) == 0L) {
            deadline = 0L
            finishTransition()
        }
    }
}
