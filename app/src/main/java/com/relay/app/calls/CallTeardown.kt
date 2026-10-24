package com.relay.app.calls

/**
 * The single place a call's platform resources are released. Every ending path (local hang-up, remote
 * `end`, timeout, failure, activity destroyed) funnels through one of these, and [release] is
 * idempotent so a duplicate `Ended` can never abandon the microphone or leave a peer connection open.
 */
class CallTeardown(vararg steps: Pair<String, () -> Unit>) {

    private val steps = steps.toList()
    private var done = false

    val released: Boolean get() = synchronized(this) { done }

    /**
     * Runs every step exactly once, in order, even when one throws. Returns the names of the steps that
     * failed on this first call; an already-released teardown returns an empty list.
     */
    fun release(): List<String> {
        synchronized(this) {
            if (done) return emptyList()
            done = true
        }
        val failures = mutableListOf<String>()
        for ((name, step) in steps) {
            try {
                step()
            } catch (e: Exception) {
                failures += name
            }
        }
        return failures
    }
}
