package com.relay.app.calls

import android.content.Context
import android.util.Log
import com.relay.app.call.CallEvent
import com.relay.app.call.CallUiPort
import com.relay.app.transport.Transports
import com.relay.app.transport.mailbox.MailboxTransport
import com.relay.app.transport.mailbox.PhoneDeviceEnrollment
import com.relay.app.transport.nostr.NostrIdentity
import kotlinx.coroutines.CoroutineScope

/**
 * The assembled Android host for one device (WP13): the `:core` engine plus its five platform ports.
 *
 * Every piece here is a port from `core/.../CALL_ENGINE.md`:
 *  - [WallClockPort] — wall-clock epoch ms, never monotonic (rule 1);
 *  - [SecureRandomPort] — [java.security.SecureRandom] call ids and nonces (rule 2);
 *  - [MailboxSignalPort] — socket `signal` frames, plus the stored urgent copy of the first invite (MUST 4);
 *  - [MailboxTurnPort] — `GET /v1/call/ice` signed with the enrolled device key, cancelled on teardown (rule 8);
 *  - [WebRtcPeer] — audio-only PeerConnection, microphone opened only by `OpenPeerConnection`.
 *
 * Ordering: [postEvent] and [dispatch] must land on the SAME single serial executor the engine runs on
 * (MUST 3). [CallController] owns that executor and the Android-only glue (audio focus, notification,
 * foreground service, wake lock, teardown); this class stays free of policy so its assembly is trivial.
 */
class CallHostInstance(
    val core: CallHostCore,
    val peer: WebRtcPeer,
    val turn: MailboxTurnPort,
    val timers: HandlerTimerPort,
)

object CallHost {

    private const val TAG = "RelayCallHost"

    /**
     * Builds the engine, the ports and the peer factory for [app].
     *
     * [turnFetch] is the authenticated `GET /v1/call/ice`; the default signs with
     * [PhoneDeviceEnrollment.authKeys], exactly like every other mailbox request (G1). Callers pass a
     * fake in tests so the TURN path never needs a live provider.
     */
    fun build(
        app: Context,
        selfPubkey: String,
        deviceId: String,
        relayOnly: Boolean,
        ui: CallUiPort,
        scope: CoroutineScope,
        dispatch: (() -> Unit) -> Unit,
        postEvent: (CallEvent) -> Unit,
        isAllowedPeer: (String) -> Boolean,
        turnFetch: (suspend () -> Pair<Int, String>)? = null,
    ): CallHostInstance {
        val random = SecureRandomPort()

        val peer = WebRtcPeer(app, postEvent)

        val turn = MailboxTurnPort(
            scope = scope,
            post = dispatch,
            fetch = {
                if (turnFetch != null) {
                    turnFetch()
                } else {
                    val transport = Transports.get(app) as? MailboxTransport
                    if (transport == null) 0 to "" else {
                        val keys = PhoneDeviceEnrollment.authKeys(app) { NostrIdentity.keys(app) }
                        val response = transport.signedRequest("GET", "/v1/call/ice", keys = keys)
                        response.status to response.body
                    }
                }
            },
            report = { e -> Log.w(TAG, "call/ice request failed", e) },
        )

        val timers = HandlerTimerPort(android.os.Handler(android.os.Looper.getMainLooper()), postEvent)

        val core = CallHostCore(
            selfPubkey = selfPubkey,
            deviceId = deviceId,
            relayOnly = relayOnly,
            clock = WallClockPort,
            random = random,
            signal = MailboxSignalPort(scope, { Transports.get(app) }, random),
            turn = turn,
            peer = peer,
            timers = timers,
            ui = ui,
            isAllowedPeer = isAllowedPeer,
        )
        Log.i(TAG, "call host assembled")
        return CallHostInstance(core = core, peer = peer, turn = turn, timers = timers)
    }
}
