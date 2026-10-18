package com.relay.app.calculator

data class AccessState(val configured: Boolean = false, val failures: Int = 0)

enum class AccessResult { ENROLLED, UNLOCKED, REJECTED, WIPE }

object AccessPolicy {
    /**
     * Wrong codes in a row before every bit of Relay data on the phone is erased (Tadej, 2026-10-18;
     * it replaced the owner-cleared lockout). A correct code resets the count.
     */
    const val WIPE_AFTER = 10

    fun submit(state: AccessState, matches: Boolean): Pair<AccessState, AccessResult> = when {
        !state.configured -> AccessState(configured = true) to AccessResult.ENROLLED
        matches -> state.copy(failures = 0) to AccessResult.UNLOCKED
        else -> state.copy(failures = state.failures + 1).let {
            it to if (it.failures >= WIPE_AFTER) AccessResult.WIPE else AccessResult.REJECTED
        }
    }
}
