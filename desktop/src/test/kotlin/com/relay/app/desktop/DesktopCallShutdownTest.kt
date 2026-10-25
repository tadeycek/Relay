package com.relay.app.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** SIGTERM / window-manager kill: the JVM shutdown hook must run the hang-up path, once, and never hang the exit. */
class DesktopCallShutdownTest {
    @Test fun hookClosesTheLiveEngineExactlyOnce() {
        val closes = AtomicInteger()
        val ref = AtomicReference<AutoCloseable?>(AutoCloseable { closes.incrementAndGet() })
        val hook = CallLifecycle.shutdownHook({ ref.getAndSet(null) }, boundMs = 2_000)
        hook.run(); hook.run()
        assertEquals(1, closes.get())
        assertTrue(ref.get() == null)
    }

    @Test fun hookIsBoundedWhenTheCloseHangs() {
        val ref = AtomicReference<AutoCloseable?>(AutoCloseable { Thread.sleep(30_000) })
        val hook = CallLifecycle.shutdownHook({ ref.getAndSet(null) }, boundMs = 300)
        val start = System.nanoTime()
        hook.run()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("shutdown hook must not outlast its bound: ${ms}ms", ms < 5_000)
    }

    @Test fun hookWithNoEngineIsANoOp() {
        CallLifecycle.shutdownHook({ null }, boundMs = 100).run()
    }
}
