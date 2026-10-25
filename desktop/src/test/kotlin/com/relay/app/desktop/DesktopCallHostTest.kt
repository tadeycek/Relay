package com.relay.app.desktop

import com.relay.app.call.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopCallHostTest {
    private val self = "a".repeat(64)
    private val other = "b".repeat(64)
    private val clock = object : ClockPort { override fun nowMs() = 1_800_000_000_000L }

    @Test(timeout = 5000) fun rejectsUnverifiedSenderBeforeNonceReplayAndKeepsEndedSticky() {
        val sent = mutableListOf<String>()
        var onState: (CallState) -> Unit = {}
        val ui = object : CallUiPort {
            override fun startRing(callId: String, incoming: Boolean) = Unit
            override fun stopRing(callId: String) = Unit
            override fun stateChanged(state: CallState) { onState(state) }
            override fun record(event: CallEffect.RecordCallEvent) = Unit
            override fun commandRejected(reason: String) = Unit
        }
        val host = DesktopCallHost(self, "d".repeat(32), clock, object : RandomPort { var n = 0; override fun hex(bytes: Int) = (++n).toString(16).padStart(bytes * 2, '0') },
            object : SignalPort { override fun send(toIdentity: String, wire: String, mode: SignalMode) { sent.add(wire) } },
            object : TurnCredentialPort {
                override fun attach(sink: (CallEvent) -> Unit) = Unit
                override fun request(callId: String) = Unit
            }, object : PeerConnectionPort {
                override fun attach(sink: (CallEvent) -> Unit) = Unit
                override fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean) = Unit
                override fun createOffer(callId: String, gen: Int, iceRestart: Boolean) = Unit
                override fun applyRemoteOffer(callId: String, gen: Int, sdp: String) = Unit
                override fun applyRemoteAnswer(callId: String, gen: Int, sdp: String) = Unit
                override fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>) = Unit
                override fun setMicMuted(callId: String, muted: Boolean) = Unit
                override fun close(callId: String) = Unit
            }, object : TimerPort {
                override fun attach(sink: (CallEvent) -> Unit) = Unit
                override fun start(callId: String, timer: CallTimer, delayMs: Long) = Unit
                override fun cancel(callId: String, timer: CallTimer) = Unit
            }, ui, { it == other })
        host.startCall(other)
        assertTrue(host.state is CallState.RingingOut)
        val invite = sent.single()
        assertNull(host.onWire("c".repeat(64), invite))
        host.hangUp()
        assertTrue(host.state is CallState.Ended)
        onState = { if (it is CallState.RingingOut) host.hangUp() }
        val before = sent.size
        host.startCall(other)
        assertTrue(sent.size > before) // sticky Ended permitted another invite
        assertTrue(host.state is CallState.Ended) // reentrant UI command did not deadlock
        host.close()
    }

    @Test fun queuedSignalsPreserveRecipientOrderAfterFailure() {
        val tasks = CopyOnWriteArrayList<() -> Unit>()
        val delivered = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val queue = DesktopCallSignalQueue({ task -> tasks.add(task) }, { _, body, _, done ->
            delivered.add(body)
            done(body != "first")
        }, { failed.add(it) })
        queue.send("peer", "first", SignalMode.LIVE)
        queue.send("peer", "second", SignalMode.LIVE)
        assertEquals(listOf("first"), delivered)
        tasks.removeAt(0).invoke()
        assertEquals(listOf("first", "second"), delivered)
        assertEquals(listOf("peer"), failed)
    }
    @Test fun duplicateCompletionCannotConsumeNextSignal() {
        val tasks = ArrayDeque<() -> Unit>()
        val callbacks = mutableMapOf<String, (Boolean) -> Unit>()
        val delivered = mutableListOf<String>()
        val queue = DesktopCallSignalQueue({ tasks.addLast(it) }, { _, body, _, done ->
            delivered.add(body); callbacks[body] = done
        }, { fail("unexpected failure") })
        queue.send("peer", "one", SignalMode.LIVE)
        queue.send("peer", "two", SignalMode.LIVE)
        queue.send("peer", "three", SignalMode.LIVE)
        callbacks.getValue("one")(true)
        callbacks.getValue("one")(true)
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        assertEquals(listOf("one", "two"), delivered)
        callbacks.getValue("two")(true)
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        assertEquals(listOf("one", "two", "three"), delivered)
    }
    @Test fun callbackThenThrowCannotConsumeNextSignal() {
        val tasks = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val queue = DesktopCallSignalQueue({ tasks.addLast(it) }, { _, body, _, done ->
            delivered.add(body)
            if (body == "one") { done(true); throw IllegalStateException("after callback") }
        }, { failures.add(it) })
        queue.send("peer", "one", SignalMode.LIVE)
        queue.send("peer", "two", SignalMode.LIVE)
        queue.send("peer", "three", SignalMode.LIVE)
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        assertEquals(listOf("one", "two"), delivered)
        assertTrue(failures.isEmpty())
    }
    @Test(timeout = 5000) fun canceledStartedTimerCannotFireAfterSameKeyRearm() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fired = CopyOnWriteArrayList<CallEvent>()
        val timers = DesktopCallTimers({ e, _ -> fired.add(e) }, {
            entered.countDown(); check(release.await(2, TimeUnit.SECONDS))
        })
        try {
            timers.start("call", CallTimer.RING_TIMEOUT, 0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            timers.cancel("call", CallTimer.RING_TIMEOUT)
            timers.start("call", CallTimer.RING_TIMEOUT, 1000)
            release.countDown()
            Thread.sleep(100)
            assertTrue("old arm posted after rearm", fired.isEmpty())
        } finally { release.countDown(); timers.close() }
    }
    @Test(timeout = 5000) fun closeReleasesRemainingPortsWhenUiThrows() {
        val closed = CopyOnWriteArrayList<String>()
        val signal = object : SignalPort, AutoCloseable {
            override fun send(toIdentity: String, wire: String, mode: SignalMode) = Unit
            override fun close() { closed.add("signal") }
        }
        val turn = object : TurnCredentialPort, AutoCloseable {
            override fun attach(sink: (CallEvent) -> Unit) = Unit
            override fun request(callId: String) = Unit
            override fun close() { closed.add("turn") }
        }
        val peer = object : PeerConnectionPort, AutoCloseable {
            override fun attach(sink: (CallEvent) -> Unit) = Unit
            override fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean) = Unit
            override fun createOffer(callId: String, gen: Int, iceRestart: Boolean) = Unit
            override fun applyRemoteOffer(callId: String, gen: Int, sdp: String) = Unit
            override fun applyRemoteAnswer(callId: String, gen: Int, sdp: String) = Unit
            override fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>) = Unit
            override fun setMicMuted(callId: String, muted: Boolean) = Unit
            override fun close(callId: String) { closed.add("peer-call") }
            override fun close() { closed.add("peer") }
        }
        val timers = object : TimerPort, AutoCloseable {
            override fun attach(sink: (CallEvent) -> Unit) = Unit
            override fun start(callId: String, timer: CallTimer, delayMs: Long) = Unit
            override fun cancel(callId: String, timer: CallTimer) = Unit
            override fun close() { closed.add("timers") }
        }
        val ui = object : CallUiPort {
            override fun startRing(callId: String, incoming: Boolean) = Unit
            override fun stopRing(callId: String) = Unit
            override fun stateChanged(state: CallState) { if (state is CallState.Ended) error("UI failure") }
            override fun record(event: CallEffect.RecordCallEvent) = Unit
            override fun commandRejected(reason: String) = Unit
        }
        val host = DesktopCallHost(self, "d".repeat(32), clock, object : RandomPort {
            override fun hex(bytes: Int) = "1".repeat(bytes * 2)
        }, signal, turn, peer, timers, ui, { it == other })
        host.startCall(other)
        runCatching { host.close() }
        assertTrue(closed.containsAll(listOf("peer-call", "turn", "timers", "signal", "peer")))
        assertThrows(IllegalStateException::class.java) { host.startCall(other) }
    }

    // --- re-review round 3 ---------------------------------------------------------------------
    private open class QuietPeer : PeerConnectionPort, AutoCloseable {
        override fun attach(sink: (CallEvent) -> Unit) = Unit
        override fun open(callId: String, role: PeerRole, iceServers: List<IceServer>, relayOnly: Boolean) = Unit
        override fun createOffer(callId: String, gen: Int, iceRestart: Boolean) = Unit
        override fun applyRemoteOffer(callId: String, gen: Int, sdp: String) = Unit
        override fun applyRemoteAnswer(callId: String, gen: Int, sdp: String) = Unit
        override fun addRemoteCandidates(callId: String, gen: Int, candidates: List<IceCandidate>) = Unit
        override fun setMicMuted(callId: String, muted: Boolean) = Unit
        override fun close(callId: String) = Unit
        override fun close() = Unit
    }
    private fun newHost(peer: PeerConnectionPort, ui: CallUiPort) = DesktopCallHost(self, "d".repeat(32), clock,
        object : RandomPort { var n = 0; override fun hex(bytes: Int) = (++n).toString(16).padStart(bytes * 2, '0') },
        object : SignalPort { override fun send(toIdentity: String, wire: String, mode: SignalMode) = Unit },
        object : TurnCredentialPort {
            override fun attach(sink: (CallEvent) -> Unit) = Unit
            override fun request(callId: String) = Unit
        }, peer, object : TimerPort {
            override fun attach(sink: (CallEvent) -> Unit) = Unit
            override fun start(callId: String, timer: CallTimer, delayMs: Long) = Unit
            override fun cancel(callId: String, timer: CallTimer) = Unit
        }, ui, { it == other })
    private fun uiOf(onState: (CallState) -> Unit = {}) = object : CallUiPort {
        override fun startRing(callId: String, incoming: Boolean) = Unit
        override fun stopRing(callId: String) = Unit
        override fun stateChanged(state: CallState) = onState(state)
        override fun record(event: CallEffect.RecordCallEvent) = Unit
        override fun commandRejected(reason: String) = Unit
    }
    private fun awaitWaiting(t: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (t.state != Thread.State.WAITING && t.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(5)
    }

    @Test(timeout = 15000) fun callerQueuedBehindSlowCloseDoesNotHangWhenCloseShutsExecutor() {
        val inPeerClose = CountDownLatch(1)
        val releasePeerClose = CountDownLatch(1)
        val inRinging = CountDownLatch(1)
        val releaseRinging = CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val peer = object : QuietPeer() {
            override fun close(callId: String) { if (armed.get()) { inPeerClose.countDown(); releasePeerClose.await(10, TimeUnit.SECONDS) } }
        }
        val host = newHost(peer, uiOf { st ->
            if (st is CallState.RingingOut && armed.get()) { inRinging.countDown(); try { releaseRinging.await(10, TimeUnit.SECONDS) } catch (_: InterruptedException) {} }
        })
        host.startCall(other); host.hangUp()
        armed.set(true)
        val outcomes = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun caller(name: String, body: () -> Unit) = Thread({
            try { body(); outcomes[name] = "ok" } catch (t: Throwable) { outcomes[name] = t }
        }, name).apply { isDaemon = true; start() }
        val closer = caller("closer") { host.close() }
        assertTrue(inPeerClose.await(5, TimeUnit.SECONDS))          // worker is inside peer.close(callId)
        val busy = caller("busy") { host.startCall(other) }       // runs after close step 2 and parks the worker in the UI hook
        awaitWaiting(busy)
        val victim = caller("victim") { host.hangUp() }           // queued behind the parked worker
        awaitWaiting(victim)
        releasePeerClose.countDown()
        assertTrue(inRinging.await(5, TimeUnit.SECONDS))
        closer.join(5000)                                          // close() finishes: shutdownNow drops the queued victim task
        victim.join(2000)
        val hung = victim.isAlive
        releaseRinging.countDown()
        assertFalse("caller queued behind close() hangs forever", hung)
        assertTrue("victim must fail, got ${outcomes["victim"]}", outcomes["victim"] is Throwable)
        assertTrue(outcomes["victim"] is java.util.concurrent.CancellationException || outcomes["victim"] is IllegalStateException)
    }

    @Test(timeout = 5000) fun postAfterCloseNeverThrows() {
        val host = newHost(QuietPeer(), uiOf())
        host.close()
        host.post(CallEvent.TimerFired("x", CallTimer.RING_TIMEOUT)) // must not throw RejectedExecutionException
    }

    @Test(timeout = 5000) fun postedEventWhoseGuardIsFalseIsDropped() {
        val host = newHost(QuietPeer(), uiOf())
        try {
            host.startCall(other)
            val ringing = host.state as CallState.RingingOut
            host.post(CallEvent.TimerFired(ringing.call.id, CallTimer.RING_TIMEOUT)) { false }
            assertTrue("stale event dispatched", host.state is CallState.RingingOut)
            host.post(CallEvent.TimerFired(ringing.call.id, CallTimer.RING_TIMEOUT)) { true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (host.state !is CallState.Ended && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("valid event not dispatched", host.state is CallState.Ended)
        } finally { host.close() }
    }

    @Test(timeout = 5000) fun firedTimerEventIsStaleOnceCancelledOrRearmed() {
        val posted = CopyOnWriteArrayList<Pair<CallEvent, () -> Boolean>>()
        val timers = DesktopCallTimers({ e, valid -> posted.add(e to valid) })
        try {
            timers.start("call", CallTimer.RING_TIMEOUT, 0)
            val d1 = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (posted.size < 1 && System.nanoTime() < d1) Thread.sleep(5)
            assertEquals(1, posted.size)
            assertTrue("fresh fire is valid", posted[0].second())
            timers.cancel("call", CallTimer.RING_TIMEOUT)
            assertFalse("cancelled timer's queued event still valid", posted[0].second())
            timers.start("call", CallTimer.RING_TIMEOUT, 0)
            val d2 = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (posted.size < 2 && System.nanoTime() < d2) Thread.sleep(5)
            assertEquals(2, posted.size)
            assertTrue(posted[1].second())
            timers.start("call", CallTimer.RING_TIMEOUT, 60_000) // re-arm after the fire
            assertFalse("re-armed timer's queued event still valid", posted[1].second())
        } finally { timers.close() }
    }

    @Test fun duplicateFailedCompletionReportsExactlyOneFailure() {
        val tasks = ArrayDeque<() -> Unit>()
        val callbacks = mutableMapOf<String, (Boolean) -> Unit>()
        val delivered = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val queue = DesktopCallSignalQueue({ tasks.addLast(it) }, { _, body, _, done -> delivered.add(body); callbacks[body] = done }, { failed.add(it) })
        queue.send("peer", "one", SignalMode.LIVE)
        queue.send("peer", "two", SignalMode.LIVE)
        queue.send("peer", "three", SignalMode.LIVE)
        callbacks.getValue("one")(false)
        callbacks.getValue("one")(false)
        callbacks.getValue("one")(true)
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
        assertEquals(listOf("peer"), failed)
        assertEquals(listOf("one", "two"), delivered)
    }
}
