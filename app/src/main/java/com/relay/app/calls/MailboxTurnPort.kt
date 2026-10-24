package com.relay.app.calls

import com.relay.app.call.CallEvent
import com.relay.app.call.TurnCredentialPort
import kotlinx.coroutines.launch

/**
 * [TurnCredentialPort] over `GET /v1/call/ice` (`server/cloudflare/PROTOCOL.md` §Calls, 06 §1).
 *
 * [fetch] performs the authenticated request: the host signs it with the enrolled device key
 * (`PhoneDeviceEnrollment.authKeys`), exactly like every other mailbox request. This class only turns the
 * HTTP result into engine events, so the network plumbing stays testable and outside the engine.
 *
 * Ordering (`CALL_ENGINE.md` rule 8 and MUST 3):
 * - a credential answer for a call that already ended is dropped;
 * - [cancel] aborts the in-flight request on teardown;
 * - the answer is delivered through [post], which the host maps onto its single serial dispatcher, so a
 *   TURN reply can never interleave with an effect batch.
 */
class MailboxTurnPort(
    private val scope: kotlinx.coroutines.CoroutineScope,
    /** Runs [block] on the host's single serial context so engine events never interleave. */
    private val post: (() -> Unit) -> Unit,
    private val fetch: suspend () -> Pair<Int, String>,
    /** Where a transport failure is reported. Defaults to nowhere: unit tests run without Android's Log. */
    private val report: (Throwable) -> Unit = {},
) : TurnCredentialPort {

    @Volatile private var sink: ((CallEvent) -> Unit)? = null
    private var current: String? = null
    private var job: kotlinx.coroutines.Job? = null

    override fun attach(sink: (CallEvent) -> Unit) {
        this.sink = sink
    }

    override fun request(callId: String) {
        synchronized(this) {
            current = callId
            job?.cancel()
            job = scope.launch {
                val result = try {
                    val (status, body) = fetch()
                    TurnResponse.parse(status, body)
                } catch (e: Exception) {
                    report(e)
                    TurnResult.Failed("network", true)
                }
                if (current != callId) return@launch
                post {
                    when (result) {
                        is TurnResult.Ok -> sink?.invoke(CallEvent.IceServersReady(callId, result.servers))
                        is TurnResult.Failed -> sink?.invoke(CallEvent.IceServersFailed(callId))
                    }
                }
            }
        }
    }

    /** Drops any in-flight credential fetch for [callId]; a later answer for it is ignored. */
    fun cancel(callId: String) {
        synchronized(this) {
            if (current == callId) current = null
            job?.cancel()
            job = null
        }
    }

    private companion object { const val TAG = "RelayCallTurn" }
}
