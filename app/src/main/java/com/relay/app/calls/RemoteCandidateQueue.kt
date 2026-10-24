package com.relay.app.calls

/** Holds ICE until the asynchronous remote SDP setter succeeds. One queue per peer connection. */
internal class RemoteCandidateQueue<T>(
    private val capacity: Int,
    private val apply: (T) -> Unit,
) {
    private var generation: Int? = null
    private var ready = false
    private var closed = false
    private val pending = ArrayDeque<T>()

    @Synchronized fun begin(gen: Int) {
        if (closed || generation == gen) return
        generation = gen
        ready = false
        pending.clear()
    }

    @Synchronized fun add(gen: Int, candidates: List<T>) {
        if (closed || generation != gen) return
        candidates.forEach { candidate ->
            if (ready) apply(candidate)
            else if (pending.size < capacity) pending.addLast(candidate)
        }
    }

    @Synchronized fun remoteDescriptionSet(gen: Int) {
        if (closed || generation != gen) return
        ready = true
        while (pending.isNotEmpty()) apply(pending.removeFirst())
    }

    @Synchronized fun close() {
        closed = true
        pending.clear()
    }
}
