package com.relay.app.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DndWindowTest {

    @Test
    fun sameDayWindow() {
        assertTrue(DndWindow.contains(9, 17, 9))
        assertTrue(DndWindow.contains(9, 17, 16))
        assertFalse(DndWindow.contains(9, 17, 17))
        assertFalse(DndWindow.contains(9, 17, 8))
    }

    @Test
    fun windowWrappingPastMidnight() {
        assertTrue(DndWindow.contains(22, 7, 22))
        assertTrue(DndWindow.contains(22, 7, 23))
        assertTrue(DndWindow.contains(22, 7, 0))
        assertTrue(DndWindow.contains(22, 7, 6))
        assertFalse(DndWindow.contains(22, 7, 7))
        assertFalse(DndWindow.contains(22, 7, 12))
        assertFalse(DndWindow.contains(22, 7, 21))
    }

    @Test
    fun equalStartAndEndMeansAlwaysOn() {
        for (h in 0..23) assertTrue(DndWindow.contains(5, 5, h))
    }
}
