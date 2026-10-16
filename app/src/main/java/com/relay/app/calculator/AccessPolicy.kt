package com.relay.app.calculator

data class AccessState(val configured: Boolean = false, val failures: Int = 0) {
    val locked: Boolean get() = failures >= 3
}

enum class AccessResult { ENROLLED, UNLOCKED, REJECTED, LOCKED }

object AccessPolicy {
    fun submit(state: AccessState, matches: Boolean): Pair<AccessState, AccessResult> = when {
        state.locked -> state to AccessResult.LOCKED
        !state.configured -> AccessState(configured = true) to AccessResult.ENROLLED
        matches -> state.copy(failures = 0) to AccessResult.UNLOCKED
        else -> state.copy(failures = state.failures + 1).let {
            it to if (it.locked) AccessResult.LOCKED else AccessResult.REJECTED
        }
    }

    /** An owner command only clears the lockout it actually acknowledged, never a later one. */
    fun canApplyOwnerUnlock(lockoutSequence: Long, unlockThroughSequence: Long): Boolean =
        lockoutSequence > 0 && unlockThroughSequence >= lockoutSequence
}
