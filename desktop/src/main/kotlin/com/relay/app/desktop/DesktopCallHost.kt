package com.relay.app.desktop

import com.relay.app.call.*
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Injectable WP14 call foundation; mailbox and UI wiring are deliberately external. */
class DesktopCallHost(
    selfPubkey: String,
    deviceId: String,
    clock: ClockPort = DesktopCallEpochClock,
    random: RandomPort = DesktopCallSecureRandom(),
    private val signal: SignalPort,
    private val turn: TurnCredentialPort,
    private val peer: PeerConnectionPort,
    private val timers: TimerPort,
    ui: CallUiPort,
    isAllowedPeer: (String) -> Boolean,
    relayOnly: Boolean = false,
) : AutoCloseable {
    private val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-desktop-call").apply {
        isDaemon = true; worker.set(this)
    } }
    private val self = selfPubkey
    private val allowed = isAllowedPeer
    @Volatile private var stopped = false
    private val runtime = CallRuntime(CallEngineConfig(selfPubkey, deviceId, relayOnly = relayOnly,
        isAllowedPeer = isAllowedPeer), clock, random, signal, turn, peer, timers, object : CallUiPort {
        override fun startRing(callId: String, incoming: Boolean) = ui.startRing(callId, incoming)
        override fun stopRing(callId: String) = ui.stopRing(callId)
        override fun record(event: CallEffect.RecordCallEvent) = ui.record(event)
        override fun commandRejected(reason: String) = ui.commandRejected(reason)
        override fun stateChanged(state: CallState) {
            if (state is CallState.Ended) (turn as? DesktopCallTurnPort)?.cancel()
            ui.stateChanged(state)
        }
    })
    val state: CallState get() = onSerial { runtime.state }

    private fun <T> onSerial(action: () -> T): T {
        check(!stopped) { "Call host closed" }
        if (Thread.currentThread() == worker.get()) return action()
        val future = try { executor.submit<T> { action() } }
        catch (e: java.util.concurrent.RejectedExecutionException) { throw IllegalStateException("Call host closed", e) }
        // close() cancels still-queued tasks after shutdownNow, so this throws CancellationException instead of hanging.
        return future.get()
    }
    fun startCall(peer: String) = onSerial { runtime.dispatch(CallEvent.StartCall(peer)) }
    fun accept() = onSerial { runtime.dispatch(CallEvent.AcceptIncoming) }
    fun decline() = onSerial { runtime.dispatch(CallEvent.DeclineIncoming) }
    fun hangUp() = onSerial { runtime.dispatch(CallEvent.HangUp) }
    fun setMuted(muted: Boolean) = onSerial { runtime.dispatch(CallEvent.SetMuted(muted)) }
    /** Authenticated NIP-17 seal sender, NOT a field supplied by the call body. */
    fun onWire(senderPubkey: String, body: String): CallParseResult? = onSerial {
        if (!body.startsWith(CallLimits.PREFIX) || body.length > CallLimits.MAX_WIRE_CHARS + CallLimits.PREFIX.length ||
            (senderPubkey != self && !allowed(senderPubkey))) null
        else runtime.onWire(senderPubkey, body)
    }
    /** Wire EventSource callbacks through this queue; never call runtime from native callback threads. */
    fun post(event: CallEvent, valid: () -> Boolean = { true }) {
        if (stopped) return
        try { executor.execute { if (!stopped && valid()) runtime.dispatch(event) } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* closed concurrently: native/timer threads must not throw */ }
    }
    @Synchronized override fun close() {
        if (stopped) return
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (t: Throwable) {
                if (failure == null) failure = t else failure!!.addSuppressed(t)
            }
        }
        try {
            attempt { onSerial { runtime.dispatch(CallEvent.HangUp) } }
            // Even if a UI/effect throws, an open peer still needs explicit disposal.
            attempt { onSerial { peer.close((runtime.state as? CallState.Ended)?.call?.id ?: "") } }
        } finally {
            stopped = true
            attempt { (turn as? AutoCloseable)?.close() }
            attempt { (timers as? AutoCloseable)?.close() }
            attempt { (signal as? AutoCloseable)?.close() }
            attempt { (peer as? AutoCloseable)?.close() }
            // shutdownNow drops queued FutureTasks; cancel them so callers blocked in Future.get() are released.
            executor.shutdownNow().forEach { (it as? java.util.concurrent.Future<*>)?.cancel(false) }
        }
        failure?.let { throw it }
    }
}

object DesktopCallEpochClock : ClockPort { override fun nowMs(): Long = System.currentTimeMillis() }
class DesktopCallSecureRandom(private val rng: SecureRandom = SecureRandom()) : RandomPort {
    override fun hex(bytes: Int): String {
        require(bytes in 1..64)
        val data = ByteArray(bytes).also(rng::nextBytes)
        return data.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Async send completion controls the next item for that recipient; failures are surfaced. */
class DesktopCallSignalQueue(
    private val schedule: (() -> Unit) -> Unit,
    private val deliver: (String, String, SignalMode, (Boolean) -> Unit) -> Unit,
    private val failed: (String) -> Unit,
) : SignalPort {
    private data class Pending(val wire: String, val mode: SignalMode)
    private val queues = mutableMapOf<String, ArrayDeque<Pending>>()
    @Synchronized override fun send(toIdentity: String, wire: String, mode: SignalMode) {
        val q = queues.getOrPut(toIdentity) { ArrayDeque() }
        require(q.size < 256) { "Call signal queue full" }
        q.addLast(Pending(wire, mode))
        if (q.size == 1) drain(toIdentity)
    }
    private fun drain(to: String) {
        val current = queues[to]?.firstOrNull() ?: return
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val complete: (Boolean) -> Unit = { ok ->
            if (completed.compareAndSet(false, true)) schedule {
                synchronized(this) {
                    try { if (!ok) failed(to) } finally {
                        if (queues[to]?.firstOrNull() === current) {
                            queues[to]?.removeFirstOrNull()
                            if (queues[to].isNullOrEmpty()) queues.remove(to) else drain(to)
                        }
                    }
                }
            }
        }
        try { deliver(to, current.wire, current.mode, complete) }
        catch (_: Exception) { complete(false) }
    }
}

class DesktopCallTimers(private val post: (CallEvent, () -> Boolean) -> Unit, private val beforeFire: () -> Unit = {}) : TimerPort, AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay-call-timer").apply { isDaemon = true } }
    private val tasks = mutableMapOf<Pair<String, CallTimer>, ScheduledFuture<*>>()
    private val arms = mutableMapOf<Pair<String, CallTimer>, Any>()
    private val generations = mutableMapOf<Pair<String, CallTimer>, Long>()
    private var nextGeneration = 0L
    private var closed = false
    override fun attach(sink: (CallEvent) -> Unit) = Unit // post is the serial host enqueue function
    @Synchronized override fun start(callId: String, timer: CallTimer, delayMs: Long) {
        if (closed) return
        cancel(callId, timer)
        val key = callId to timer
        val token = Any()
        arms[key] = token
        val generation = ++nextGeneration
        generations[key] = generation
        tasks[key] = scheduler.schedule({
            beforeFire()
            synchronized(this) {
                if (!closed && arms[key] === token) {
                    arms.remove(key); tasks.remove(key)
                    // Still-queued fire events are stale once the key is cancelled or re-armed (generation changes).
                    post(CallEvent.TimerFired(callId, timer)) { synchronized(this) { !closed && generations[key] == generation } }
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }
    @Synchronized override fun cancel(callId: String, timer: CallTimer) {
        arms.remove(callId to timer)
        generations.remove(callId to timer)
        tasks.remove(callId to timer)?.cancel(false)
    }
    @Synchronized override fun close() {
        closed = true; arms.clear(); generations.clear()
        tasks.values.forEach { it.cancel(false) }; tasks.clear(); scheduler.shutdownNow()
    }
}

/** Inject an authenticated GET /v1/call/ice fetch; never logs credential values. */
class DesktopCallTurnPort(
    private val nowMs: () -> Long,
    private val fetch: (callId: String, reply: (servers: List<IceServer>?, expiresAtMs: Long?) -> Unit) -> AutoCloseable,
    private val post: (CallEvent) -> Unit,
) : TurnCredentialPort, AutoCloseable {
    private var pending: AutoCloseable? = null
    private var generation = 0L
    private var closed = false
    override fun attach(sink: (CallEvent) -> Unit) = Unit
    @Synchronized override fun request(callId: String) {
        pending?.close(); pending = null
        val token = ++generation
        if (closed) return
        val request = try { fetch(callId) { servers, expiry ->
            synchronized(this) {
                if (closed || generation != token) return@fetch
                post(if (servers != null && expiry != null && expiry > nowMs() + 5_000 &&
                    servers.isNotEmpty() && servers.all { it.urls.isNotEmpty() })
                    CallEvent.IceServersReady(callId, servers) else CallEvent.IceServersFailed(callId))
            }
        } } catch (_: Exception) {
            if (generation == token && !closed) post(CallEvent.IceServersFailed(callId))
            return
        }
        if (generation == token && !closed) pending = request else request.close()
    }
    @Synchronized override fun close() { closed = true; generation++; pending?.close(); pending = null }
    @Synchronized fun cancel() { generation++; pending?.close(); pending = null }
}
