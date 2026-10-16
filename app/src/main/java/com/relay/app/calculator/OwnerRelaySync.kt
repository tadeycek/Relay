package com.relay.app.calculator

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.relay.app.BuildConfig
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.Route
import com.relay.app.transport.SendResult
import com.relay.app.transport.TorControl
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.nostr.DefaultRelays
import com.relay.app.transport.nostr.NostrTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.MessageDigest

/** Owner reports and signed recovery use encrypted relays, with a separate installation identity. */
object OwnerRelaySync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false
    fun kick() { kicks.trySend(Unit) }

    fun start(context: Context) {
        synchronized(this) { if (started) return; started = true }
        val app = context.applicationContext
        scope.launch {
            val store = CalculatorAccessStore.get(app)
            var transport: NostrTransport? = null
            var lastRoute: Route? = null
            var lastFingerprint: ByteArray? = null
            var lastSent = 0L
            while (true) {
                runCatching {
                    if (!store.state().configured) return@runCatching
                    val route = TorControl.route(app)
                    if (transport == null) {
                        val link = NostrTransport(app, { DefaultRelays.CLEARNET }, { TorControl.route(app) },
                            object : NostrTransport.LastSeenStore {
                                override fun lastSeenSecs() = store.ownerLastSeenSecs()
                                override fun touch(secs: Long) { /* Advance only after a pinned-owner response. */ }
                            }, identity = { store.ownerRelayKeys() }, publishAndDiscoverInbox = false)
                        transport = link
                        scope.launch {
                            link.incoming.collect { incoming ->
                                val state = OwnerRelayProtocol.state(incoming.payload.body, incoming.senderPubkeyHex,
                                    BuildConfig.OWNER_PUBLIC_KEY, store.credentials().first) ?: run {
                                    Log.d("OwnerRelaySync", "Ignored unauthorized or malformed recovery state")
                                    return@collect
                                }
                                store.touchOwnerRelay(incoming.receivedAtMs / 1000)
                                store.acknowledge(state.acknowledged)
                                store.acknowledgeDiagnostics(state.diagnosticsRevision)
                                store.applyOwnerUnlock(state.unlockThrough)
                                kick()
                            }
                        }
                        scope.launch { link.status.collect { if (it == TransportStatus.ONLINE) kick() } }
                        lastRoute = route
                        link.start()
                    } else if (route != lastRoute) {
                        lastRoute = route
                        transport?.reconnect()
                    }
                    if (route is Route.Blocked || transport?.status?.value != TransportStatus.ONLINE) return@runCatching
                    val events = store.pendingEvents()
                    val choice = store.diagnosticsChoice()
                    val now = SystemClock.elapsedRealtime()
                    val hasPending = events.length() > 0 || store.diagnosticsPending()
                    if (!hasPending && !(choice.second && now - lastSent >= 300_000)) return@runCatching
                    val (id, token) = store.credentials()
                    val diagnostics = JSONObject().put("revision", choice.first).put("enabled", choice.second)
                    if (choice.second) diagnostics.put("report", OwnerDiagnostics.report(app, route))
                    val body = JSONObject().put("protocol", OwnerRelayProtocol.NAME).put("type", "report")
                        .put("device_id", id).put("device_token", token).put("events", events)
                        .put("diagnostics", diagnostics).toString()
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
                    if (lastFingerprint?.contentEquals(fingerprint) == true && now - lastSent < 60_000) return@runCatching
                    val result = transport?.send(BuildConfig.OWNER_PUBLIC_KEY,
                        RelayPayload(PayloadCodec.newId(), System.currentTimeMillis(), body), DefaultRelays.CLEARNET)
                    if (result is SendResult.Sent) { lastFingerprint = fingerprint; lastSent = now }
                }
                withTimeoutOrNull(5_000L) { kicks.receive() }
            }
        }
    }
}
