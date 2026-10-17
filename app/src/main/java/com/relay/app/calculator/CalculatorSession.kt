package com.relay.app.calculator

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Survives rotation, but never process death, backup, or a fresh application launch. */
class CalculatorSession : ViewModel() {
    var deadline by mutableLongStateOf(0L)
        private set

    fun unlock() { deadline = SystemClock.elapsedRealtime() + SessionPolicy.DURATION_MS }
    fun remainingMs() = SessionPolicy.remainingMs(deadline, SystemClock.elapsedRealtime())
    fun isActive() = remainingMs() > 0

    fun expireIfNeeded(now: Long = SystemClock.elapsedRealtime()) {
        if (SessionPolicy.remainingMs(deadline, now) == 0L) deadline = 0L
    }
}
