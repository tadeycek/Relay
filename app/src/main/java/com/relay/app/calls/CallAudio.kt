package com.relay.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Where the call audio should come out. [routeFor] is pure, so the rules are testable without hardware. */
enum class CallRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED }

object CallAudio {
    fun needsBluetoothPermission(sdk: Int, granted: () -> Boolean): Boolean = sdk >= 31 && !granted()

    fun needsBluetoothPermission(context: Context): Boolean = needsBluetoothPermission(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Bluetooth wins when it is connected, then the user's speaker choice, then wired, then earpiece.
     * A wired headset is never overridden by the speaker toggle: it is what the user plugged in.
     */
    fun routeFor(speakerOn: Boolean, bluetooth: Boolean, wired: Boolean): CallRoute = when {
        bluetooth -> CallRoute.BLUETOOTH
        wired -> if (speakerOn) CallRoute.SPEAKER else CallRoute.WIRED
        speakerOn -> CallRoute.SPEAKER
        else -> CallRoute.EARPIECE
    }

    /** Acquires call audio focus and puts the device into communication mode. Returns the focus handle. */
    fun take(context: Context): AudioFocusRequest? = try {
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        manager.requestAudioFocus(request)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        request
    } catch (e: Exception) {
        null
    }

    fun release(context: Context, request: AudioFocusRequest?) {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching { request?.let(manager::abandonAudioFocusRequest) }
        runCatching { manager.mode = AudioManager.MODE_NORMAL }
        runCatching { manager.isSpeakerphoneOn = false }
        if (Build.VERSION.SDK_INT >= 31) runCatching { manager.clearCommunicationDevice() }
    }

    /** Applies [routeFor] through the platform. Returns the route actually applied. */
    fun apply(context: Context, speakerOn: Boolean): CallRoute {
        val manager = context.getSystemService(AudioManager::class.java) ?: return CallRoute.EARPIECE
        val devices = runCatching { manager.availableCommunicationDevices }.getOrDefault(emptyList())
        val bluetooth = !needsBluetoothPermission(context) && devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val wired = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val route = routeFor(speakerOn, bluetooth, wired)
        if (Build.VERSION.SDK_INT >= 31) {
            val wanted = when (route) {
                CallRoute.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                CallRoute.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                CallRoute.WIRED -> devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }?.type ?: AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                CallRoute.EARPIECE -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            devices.firstOrNull { it.type == wanted }?.let { runCatching { manager.setCommunicationDevice(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { manager.isSpeakerphoneOn = route == CallRoute.SPEAKER }
        }
        return route
    }
}
