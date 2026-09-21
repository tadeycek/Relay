package com.relay.app.transport

/**
 * Bounded, thread-safe memory of ids already handled. The same gift wrap (or the same payload)
 * routinely arrives once per relay and again on every reconnect, so processing must be idempotent.
 * This is the fast first check; a persistent check against stored message ids backs it up across
 * process restarts.
 */
class SeenIds(private val capacity: Int = 2048) {

    private val ids = object : LinkedHashMap<String, Boolean>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }

    /** Returns true the first time [id] is seen, false for every repeat. */
    @Synchronized
    fun markIfNew(id: String): Boolean {
        // get() (not containsKey) so a repeat sighting counts as an access and refreshes recency.
        if (ids[id] != null) return false
        ids[id] = true
        return true
    }
}
