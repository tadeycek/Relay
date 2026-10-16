package com.relay.app.calculator

import android.content.Context
import com.relay.app.BuildConfig
import com.relay.app.transport.Route
import com.relay.app.transport.TorControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Access reports plus separately opted-in diagnostics. No messaging identity, content, PIN or contacts. */
object OwnerSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false

    fun kick() { kicks.trySend(Unit); OwnerRelaySync.kick() }

    fun start(context: Context) {
        if (BuildConfig.OWNER_PUBLIC_KEY.isNotBlank()) {
            OwnerRelaySync.start(context)
            return
        }
        if (BuildConfig.OWNER_SERVICE_URL.isBlank() || BuildConfig.OWNER_ENROLLMENT_TOKEN.isBlank()) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        scope.launch {
            while (true) {
                runCatching { sync(app) }
                val locked = runCatching { CalculatorAccessStore.get(app).state().locked }.getOrDefault(false)
                withTimeoutOrNull(if (locked) 5_000L else 60_000L) { kicks.receive() }
            }
        }
    }

    private fun sync(context: Context) {
        val store = CalculatorAccessStore.get(context)
        if (!store.state().configured) return
        val (id, token) = store.credentials()
        // Idempotent enrollment lets a restored/restarted local service recover its registration.
        request(context, "/v1/enroll", BuildConfig.OWNER_ENROLLMENT_TOKEN,
            JSONObject().put("device_id", id).put("device_token", token))
        val events = store.pendingEvents()
        if (events.length() > 0) {
            val ack = request(context, "/v1/devices/$id/events", token, JSONObject().put("events", events))
            store.acknowledge(ack.getLong("last_sequence"))
        }
        val state = request(context, "/v1/devices/$id/state", token, null)
        if (store.applyOwnerUnlock(state.getLong("unlock_through_sequence"))) kick()
        // Optional reports cannot prevent access reports or recovery on an older service.
        runCatching { syncDiagnostics(context, store, id, token) }
    }

    private fun syncDiagnostics(context: Context, store: CalculatorAccessStore, id: String, token: String) {
        val choice = store.diagnosticsChoice()
        if (choice.second || store.diagnosticsPending()) {
            val route = TorControl.route(context)
            check(route !is Route.Blocked) { "Waiting for Tor" }
            val body = JSONObject().put("enabled", choice.second)
            if (choice.second) body.put("report", OwnerDiagnostics.report(context, route))
            if (store.diagnosticsChoice() == choice) {
                request(context, "/v1/devices/$id/diagnostics", token, body, route)
                store.acknowledgeDiagnostics(choice.first)
            }
        }
    }

    private fun request(context: Context, path: String, token: String, body: JSONObject?,
                        route: Route = TorControl.route(context)): JSONObject {
        val base = URI(BuildConfig.OWNER_SERVICE_URL.trimEnd('/'))
        require(base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
        val localDebug = BuildConfig.DEBUG && base.scheme == "http" && base.host == "10.0.2.2"
        require(base.scheme == "https" || localDebug) { "Owner service requires HTTPS" }
        check(route !is Route.Blocked) { "Waiting for Tor" }
        val url = URI(base.toString() + path).toURL()
        val connection = (if (route is Route.Socks) url.openConnection(TorControl.javaProxy(route)) else url.openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode == 200) { "Owner service unavailable" }
            val bytes = connection.inputStream.use { it.readBytes() }
            require(bytes.size <= 16_384)
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
