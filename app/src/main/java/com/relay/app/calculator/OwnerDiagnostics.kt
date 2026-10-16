package com.relay.app.calculator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.relay.app.BuildConfig
import com.relay.app.transport.Route
import com.relay.app.util.RelayPreferences
import org.json.JSONObject

/** Fixed, disclosed allowlist. Called only after the user has opted in; requests no permissions. */
internal object OwnerDiagnostics {
    fun report(context: Context, route: Route): JSONObject {
        check(route !is Route.Blocked)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = runCatching {
            val active = manager.activeNetwork ?: return@runCatching "offline"
            val caps = manager.getNetworkCapabilities(active) ?: return@runCatching "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        }.getOrDefault("unknown")
        fun allowed(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        fun text(value: String, limit: Int) = value.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .take(limit).ifBlank { "unknown" }
        return JSONObject()
            .put("app_version", text(BuildConfig.VERSION_NAME, 64))
            .put("manufacturer", text(Build.MANUFACTURER, 64))
            .put("model", text(Build.MODEL, 96))
            .put("android_version", text(Build.VERSION.RELEASE, 32))
            .put("android_api", Build.VERSION.SDK_INT)
            .put("network", network)
            .put("route", when {
                route is Route.Socks -> "tor"
                RelayPreferences(context).torEnabled -> "direct_fallback"
                else -> "direct"
            })
            .put("permissions", JSONObject()
                .put("camera", allowed(Manifest.permission.CAMERA))
                .put("location", allowed(Manifest.permission.ACCESS_COARSE_LOCATION) || allowed(Manifest.permission.ACCESS_FINE_LOCATION))
                .put("notifications", NotificationManagerCompat.from(context).areNotificationsEnabled()))
    }
}
