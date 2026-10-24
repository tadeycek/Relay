package com.relay.app.calls

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.relay.app.call.CallEvent
import com.relay.app.call.CallTimer
import com.relay.app.call.ClockPort
import com.relay.app.call.RandomPort
import com.relay.app.call.SignalMode
import com.relay.app.call.SignalPort
import com.relay.app.call.TimerPort
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.Transport
import com.relay.app.transport.mailbox.MailboxTransport
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Wall-clock epoch milliseconds. The engine binds `ts` and `expires` to absolute epoch values, so a
 * monotonic clock would reject every packet as future-dated or expired (`CALL_ENGINE.md` rule 1).
 */
object WallClockPort : ClockPort {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Call ids and nonces are the only replay defence, so this is [SecureRandom], never `Random`. */
class SecureRandomPort : RandomPort {
    private val random = SecureRandom()
    override fun hex(bytes: Int): String {
        require(bytes in 1..64) { "hex bytes out of range" }
        val out = ByteArray(bytes)
        random.nextBytes(out)
        return out.joinToString("") { "%02x".format(it) }
    }
}

/**
 * One-shot timers on the main looper. [post] must deliver to the engine's single serial context, so a
 * timer that fires while an effect batch is still running cannot interleave with it.
 */
class HandlerTimerPort(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val post: (CallEvent) -> Unit,
) : TimerPort {
    private var sink: ((CallEvent) -> Unit)? = null
    private val armed = HashMap<Pair<String, CallTimer>, Runnable>()

    override fun attach(sink: (CallEvent) -> Unit) {
        this.sink = sink
    }

    override fun start(callId: String, timer: CallTimer, delayMs: Long) {
        val key = callId to timer
        armed.remove(key)?.let(handler::removeCallbacks)
        val runnable = Runnable {
            armed.remove(key)
            post(CallEvent.TimerFired(callId, timer))
        }
        armed[key] = runnable
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0))
    }

    override fun cancel(callId: String, timer: CallTimer) {
        armed.remove(callId to timer)?.let(handler::removeCallbacks)
    }

    /** Drops every armed timer; used when the process tears a call down after a crash. */
    fun cancelAll() {
        armed.values.forEach(handler::removeCallbacks)
        armed.clear()
    }
}

/**
 * [SignalPort] over the mailbox. A `LIVE` packet is the socket `signal` frame (never stored);
 * `LIVE_AND_STORED_URGENT` additionally stores the same body as an urgent delivery so the callee's
 * phone can be woken by FCM when its socket is asleep (`CALL_ENGINE.md` MUST 4).
 *
 * Best effort by contract: a send failure must never block the state machine.
 */
class MailboxSignalPort(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val transport: () -> Transport?,
    private val random: RandomPort,
) : SignalPort {

    private val queue = RecipientSignalQueue(scope) { recipient, packed ->
        // The mode is kept with its wire until the previous packet for this recipient completes.
        val mode = if (packed.startsWith("U")) SignalMode.LIVE_AND_STORED_URGENT else SignalMode.LIVE
        val wire = packed.substring(1)
        val target = transport() ?: return@RecipientSignalQueue
        val id = "c" + random.hex(12)
        val payload = RelayPayload(id = id, ts = System.currentTimeMillis(), body = wire)
        target.send(recipient, payload, ephemeral = true)
        if (mode == SignalMode.LIVE_AND_STORED_URGENT) {
            val urgent = RelayPayload(id = "u" + random.hex(12), ts = payload.ts, body = wire)
            when (target) {
                is MailboxTransport -> target.deliverUrgent(recipient, urgent)
                else -> runCatching { Log.i(TAG, "transport ${target::class.simpleName} has no urgent delivery; live only") }
            }
        }
    }

    override fun send(toIdentity: String, wire: String, mode: SignalMode) {
        if (!queue.send(toIdentity, (if (mode == SignalMode.LIVE_AND_STORED_URGENT) "U" else "L") + wire))
            runCatching { Log.w(TAG, "call signal queue full; dropping ${mode.name} packet") }
    }

    /** Test seam: completes when every packet queued so far has been handed to the transport. */
    internal suspend fun flush() = queue.flush()

    private companion object { const val TAG = "RelayCallSignal" }
}
