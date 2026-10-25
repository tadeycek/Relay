package com.relay.app.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.relay.app.call.CallEffect
import com.relay.app.call.CallLimits
import com.relay.app.call.CallState
import com.relay.app.call.CallUiPort
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.repository.ContactRepository

import com.relay.app.messaging.MessagingDb
import com.relay.app.transport.Transport
import com.relay.app.transport.Transports
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.RelayPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The Android host of the 1:1 call engine.
 *
 * Everything the engine sees is a port: [WebRtcPeer] (WebRTC), [MailboxSignalPort] (socket signals and
 * the urgent stored invite), [MailboxTurnPort] (`GET /v1/call/ice`), [HandlerTimerPort],
 * [WallClockPort]/[SecureRandomPort]. This object owns the Android-only parts — ringtone, audio focus
 * and mode, foreground service, wake/proximity lock, notification, the call activity and teardown —
 * and keeps them thin by delegating every decision to [CallHostCore] and the tested helpers.
 *
 * Serialisation (`CALL_ENGINE.md` MUST 3): every engine call, `onWire`, timer and port callback runs on
 * ONE single-thread executor. UI, notifications and sounds are posted to the main thread from it.
 */
object CallController {

    private const val TAG = "RelayCall"
    private const val ENDED_SCREEN_MS = 2_500L

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "relay-call") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val _screen = MutableStateFlow<CallUiModel?>(null)
    val screen: StateFlow<CallUiModel?> = _screen.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    private val _speaker = MutableStateFlow(false)
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var core: CallHostCore? = null
    @Volatile private var peer: WebRtcPeer? = null
    @Volatile private var turn: MailboxTurnPort? = null
    @Volatile private var timers: HandlerTimerPort? = null
    @Volatile private var ringtone: CallRingtone? = null

    @Volatile private var callId: String? = null
    @Volatile private var teardown: CallTeardown? = null
    @Volatile private var teardownCallId: String? = null
    private var audioFocus: AudioFocusRequest? = null
    /** True while the microphone is muted by the audio-focus rule (not by the user). Serial dispatcher only. */
    private var autoMuted = false
    private var proximity: PowerManager.WakeLock? = null

    /** True when a call is live (any state except idle/ended). Drives the in-call entry points. */
    fun isBusy(): Boolean = core?.state.let { it != null && it !is CallState.Idle && it !is CallState.Ended }

    fun currentState(): CallState = core?.state ?: CallState.Idle

    // ---- lifecycle ------------------------------------------------------------------------------

    /**
     * Builds the runtime once per process. A calculator-only install never creates one: no socket, no
     * ring, no microphone (06 §4).
     */
    fun initialize(context: Context): Boolean {
        val app = context.applicationContext
        if (com.relay.app.calculator.CalculatorOnly.enabled(app)) return false
        core?.let { return true }
        synchronized(this) {
            core?.let { return true }
            appContext = app
            val ring = CallRingtone(app)
            ringtone = ring
            val instance = CallHost.build(
                app = app,
                selfPubkey = NostrIdentity.publicKeyHex(app),
                deviceId = CallLimits.PRIMARY_DEVICE,
                relayOnly = RelayPreferences(app).callsRelayOnly,
                ui = UiPort(app),
                scope = scope,
                dispatch = { block -> dispatcher.execute(block) },
                postEvent = ::postEvent,
                isAllowedPeer = { pubkey -> isAllowedPeer(app, pubkey) },
            )
            core = instance.core
            peer = instance.peer
            turn = instance.turn
            timers = instance.timers
            Log.i(TAG, "call host initialised")
            return true
        }
    }

    /** Drops the whole runtime (app shutdown / wipe). Releases the microphone and the peer factory. */
    fun shutdown() {
        dispatcher.execute {
            callId?.let { runCatching { teardownFor(appContext, it).release() } }
            runCatching { peer?.releaseFactory() }
            runCatching { timers?.cancelAll() }
            core = null
            peer = null
            turn = null
            timers = null
            ringtone = null
        }
    }

    // ---- user commands --------------------------------------------------------------------------

    fun placeCall(context: Context, calleePubkey: String, peerName: String?) {
        val app = context.applicationContext
        if (!initialize(app)) {
            toast(app, CallGuard.startMessage(CallStartBlock.PROFILE_LOCKED))
            return
        }
        val block = CallGuard.startBlock(
            micGranted = hasMic(app),
            otherCallLive = isBusy(),
            online = online(app),
            callsEnabled = true,
        )
        if (block != CallStartBlock.NONE) {
            toast(app, CallGuard.startMessage(block))
            return
        }
        dispatcher.execute { core?.startCall(calleePubkey) }
    }

    fun accept(context: Context) {
        val app = context.applicationContext
        if (!initialize(app)) return
        val block = CallGuard.answerBlock(micGranted = hasMic(app), callsEnabled = true)
        if (block != CallStartBlock.NONE) {
            toast(app, CallGuard.startMessage(block))
            return
        }
        dispatcher.execute { core?.accept() }
    }

    fun decline(context: Context) {
        val app = context.applicationContext
        if (core == null) return
        dispatcher.execute { core?.decline() }
    }

    fun hangUp(context: Context) {
        val app = context.applicationContext
        if (core == null) return
        dispatcher.execute { core?.hangUp() }
    }

    fun setMuted(context: Context, muted: Boolean) {
        dispatcher.execute {
            autoMuted = false // a user choice always wins over the focus rule
            core?.setMuted(muted)
        }
        _muted.value = muted
    }

    fun toggleSpeaker(context: Context): Boolean {
        val next = !_speaker.value
        _speaker.value = next
        main.post { appContext?.let { CallAudio.apply(it, next) } }
        return next
    }

    // ---- signalling -----------------------------------------------------------------------------

    /**
     * A live `signal` frame body. Returns true when the body was a call body (handled or deliberately
     * dropped), so the message path never treats call signalling as a message.
     */
    fun onLiveSignal(context: Context, senderPubkey: String, body: String): Boolean {
        if (!CallHostCore.isCallBody(body)) return false
        if (!initialize(context)) return true // calculator-only: ignore, like messages (06 §4)
        dispatcher.execute {
            val result = core?.onWire(senderPubkey, body)
            if (result is com.relay.app.call.CallParseResult.Rejected) {
                Log.i(TAG, "call packet rejected: ${result.reason}")
            }
        }
        return true
    }

    /**
     * A stored inbox delivery. Only an `invite` is ever stored (`CALL_ENGINE.md` MUST 5); the engine
     * rejects it with EXPIRED once past its own `expires`, so a late open never rings.
     */
    fun onStoredDelivery(context: Context, senderPubkey: String, body: String): Boolean {
        if (!CallHostCore.isCallBody(body)) return false
        if (!initialize(context)) return true
        dispatcher.execute { core?.onWire(senderPubkey, body) }
        return true
    }

    /** FCM `urgent=1`: connect the socket now and let its catch-up deliver a fresh stored invite. */
    fun onUrgentWake(context: Context) {
        val app = context.applicationContext
        if (com.relay.app.calculator.CalculatorOnly.enabled(app)) return
        initialize(app)
        // Deliberately not PollWorker: WorkManager scheduling is too slow for a ring (06 §4 step 2).
        com.relay.app.messaging.MessagingRuntime.ensureStarted(app)
        com.relay.app.messaging.MessagingRuntime.onForeground(app)
    }

    private fun postEvent(event: com.relay.app.call.CallEvent) {
        dispatcher.execute { core?.runtime?.dispatch(event) }
    }

    // ---- engine callbacks ------------------------------------------------------------------------

    private class UiPort(private val app: Context) : CallUiPort {
        override fun startRing(callId: String, incoming: Boolean) {
            CallController.main.post {
                CallController.callId = callId
                CallController.ringtone?.start(incoming)
                CallForegroundService.start(app, callId, CallController.displayName(), ServiceStage.RINGING)
                if (incoming) {
                    val name = CallController.displayName()
                    runCatching {
                        app.getSystemService(android.app.NotificationManager::class.java)
                            ?.notify(CallNotifier.NOTIFICATION_ID, CallNotifier.incoming(app, name, callId))
                    }
                    CallController.launchCallScreen(app)
                }
            }
        }

        override fun stopRing(callId: String) {
            CallController.main.post { CallController.ringtone?.stop() }
        }

        override fun stateChanged(state: CallState) {
            // Still on the serial dispatcher: a contact lookup here never touches the main thread.
            val name = CallPeer.of(state)?.let { CallController.lookupName(app, it) }
            val model = CallUiModelFactory.from(
                state = state,
                muted = CallController._muted.value,
                speakerOn = CallController._speaker.value,
                nowMs = System.currentTimeMillis(),
                peerName = name,
                previewsHidden = CallController.previewsHidden(app),
            )
            CallController.main.post { CallController.applyState(app, state, model, name) }
        }

        override fun record(event: CallEffect.RecordCallEvent) {
            CallController.scope.launch { CallRecords.store(app, event) }
        }

        override fun commandRejected(reason: String) {
            CallController.main.post { CallController.toast(app, CallController.rejectedMessage(reason)) }
        }
    }

    private fun applyState(app: Context, state: CallState, model: CallUiModel?, name: String?) {
        _screen.value = model
        when (state) {
            is CallState.RingingIn -> Unit
            is CallState.RingingOut -> Unit
            is CallState.Connecting -> {
                CallForegroundService.start(app, state.call.id, name, ServiceStage.IN_CALL)
                takeAudio(app)
            }
            is CallState.Active -> {
                CallForegroundService.start(app, state.call.id, name, ServiceStage.IN_CALL)
                takeAudio(app)
                applyRoute(app)
                holdProximity(app)
                runCatching {
                    app.getSystemService(android.app.NotificationManager::class.java)
                        ?.notify(CallNotifier.NOTIFICATION_ID, CallNotifier.ongoing(app, name, state.call.id))
                }
            }
            is CallState.Ended -> {
                teardownFor(app, state.call.id).release()
                main.postDelayed({
                    if (_screen.value?.callId == state.call.id && _screen.value?.stage == CallStage.ENDED) _screen.value = null
                }, ENDED_SCREEN_MS)
            }
            CallState.Idle -> Unit
        }
    }

    // ---- platform glue --------------------------------------------------------------------------

    private fun takeAudio(app: Context) {
        if (audioFocus == null) audioFocus = CallAudio.take(app, ::onAudioFocusChange)
    }

    /** Applies [CallFocusPolicy] (documented there) on the serial dispatcher; the platform calls this on main. */
    private fun onAudioFocusChange(change: Int) {
        val event = CallFocusPolicy.eventFor(change) ?: return
        dispatcher.execute {
            val state = currentState()
            val live = state is CallState.Connecting || state is CallState.Active
            when (CallFocusPolicy.decide(event, live, autoMuted)) {
                FocusAction.MUTE_UNTIL_REGAINED -> if (!_muted.value) {
                    autoMuted = true
                    core?.setMuted(true)
                    _muted.value = true
                }
                FocusAction.MUTE_UNTIL_USER_UNMUTES -> {
                    autoMuted = false
                    if (!_muted.value) {
                        core?.setMuted(true)
                        _muted.value = true
                    }
                }
                FocusAction.UNMUTE -> {
                    autoMuted = false
                    core?.setMuted(false)
                    _muted.value = false
                }
                FocusAction.NONE -> Unit
            }
        }
    }

    private fun applyRoute(app: Context) {
        val route = CallAudio.apply(app, _speaker.value)
        if (route == CallRoute.EARPIECE || route == CallRoute.WIRED) holdProximity(app) else releaseProximity()
    }

    private fun holdProximity(app: Context) {
        if (_speaker.value) return releaseProximity()
        if (proximity?.isHeld == true) return
        val power = app.getSystemService(PowerManager::class.java) ?: return
        proximity = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "relay:call-proximity").apply {
            setReferenceCounted(false)
            runCatching { acquire(2 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseProximity() {
        runCatching { proximity?.takeIf { it.isHeld }?.release() }
        proximity = null
    }

    /**
     * The single release path. Every step runs once: a duplicate `Ended` cannot leave the microphone
     * open or the peer connection alive (`CallTeardown` is idempotent, and [WebRtcPeer.close] is too).
     */
    private fun teardownFor(app: Context?, callId: String): CallTeardown {
        val existing = teardown
        if (existing != null && teardownCallId == callId) return existing
        val created = CallTeardown(
            "ringtone" to { ringtone?.stop() },
            "peer" to { peer?.close(callId) },
            "turn" to { turn?.cancel(callId) },
            "audio" to {
                app?.let { CallAudio.release(it, audioFocus) }
                audioFocus = null
                autoMuted = false
            },
            "proximity" to { releaseProximity() },
            "service" to {
                app?.let {
                    CallForegroundService.stop(it)
                    CallNotifier.cancel(it)
                }
            },
        )
        teardown = created
        teardownCallId = callId
        return created
    }

    private fun launchCallScreen(app: Context) {
        try {
            app.startActivity(
                Intent(app, CallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(CallActivity.EXTRA_CALL_ID, callId),
            )
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open the call screen: ${e.message}")
        }
    }

    private fun displayName(): String? =
        CallPeer.of(currentState())?.let { peerKey -> appContext?.let { ctx -> lookupName(ctx, peerKey) } }

    private fun lookupName(context: Context, pubkey: String): String? = try {
        ContactRepository(MessagingDb.get(context)).findByNostrPubkeySync(pubkey)?.name
    } catch (e: Exception) {
        null
    }

    private fun isAllowedPeer(context: Context, pubkey: String): Boolean = try {
        val contact = ContactRepository(MessagingDb.get(context)).findByNostrPubkeySync(pubkey) ?: return false
        contact.qrVerified && !contact.isDeleted && contact.trustLevel != ContactTrustLevel.BLOCKED
    } catch (e: Exception) {
        false
    }

    private fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun online(context: Context): Boolean = try {
        val transport: Transport = Transports.get(context)
        transport.status.value == com.relay.app.transport.TransportStatus.ONLINE
    } catch (e: Exception) {
        true
    }

    internal fun previewsHidden(context: Context): Boolean =
        !RelayPreferences(context).incomingMessageNotification || com.relay.app.calculator.CalculatorOnly.enabled(context)

    private fun rejectedMessage(reason: String): String = when (reason) {
        "busy_local" -> "You're already on a call."
        "invalid_callee" -> "That contact can't be called."
        "id_collision" -> "Try the call again."
        else -> "The call couldn't start."
    }

    private fun toast(context: Context, message: String?) {
        if (message == null) return
        main.post { runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() } }
    }
}
