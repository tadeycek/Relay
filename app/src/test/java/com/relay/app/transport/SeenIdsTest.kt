package com.relay.app.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenIdsTest {

    @Test
    fun firstSightingIsNewRepeatsAreNot() {
        val s = SeenIds()
        assertTrue(s.markIfNew("a"))
        assertFalse(s.markIfNew("a"))
        assertTrue(s.markIfNew("b"))
    }

    @Test
    fun evictsOldestBeyondCapacity() {
        val s = SeenIds(capacity = 3)
        listOf("a", "b", "c").forEach { assertTrue(s.markIfNew(it)) }
        assertTrue(s.markIfNew("d")) // evicts "a"
        assertTrue("a was evicted so counts as new again", s.markIfNew("a"))
        assertFalse("d is still remembered", s.markIfNew("d"))
    }

    @Test
    fun recentUseProtectsFromEviction() {
        val s = SeenIds(capacity = 3)
        listOf("a", "b", "c").forEach { s.markIfNew(it) }
        assertFalse(s.markIfNew("a")) // touch a -> b becomes eldest
        assertTrue(s.markIfNew("d")) // evicts b
        assertFalse(s.markIfNew("a"))
        assertTrue(s.markIfNew("b"))
    }
}
