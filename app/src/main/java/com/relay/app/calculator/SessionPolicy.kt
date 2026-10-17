package com.relay.app.calculator

/** A fixed session, measured with elapsed time rather than the user-adjustable wall clock. */
object SessionPolicy {
    const val DURATION_MS = 10 * 60 * 1000L

    fun retainChatForPicker(pickerOpen: Boolean, deadline: Long, now: Long) = pickerOpen && remainingMs(deadline, now) > 0

    fun remainingMs(deadline: Long, now: Long): Long =
        if (deadline > 0 && now >= 0 && deadline > now && deadline - now <= DURATION_MS) deadline - now else 0
}
