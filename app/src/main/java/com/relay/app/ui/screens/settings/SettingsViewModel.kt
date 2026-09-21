package com.relay.app.ui.screens.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.relay.app.messaging.ConnectionService
import com.relay.app.sms.KeyRotationReceiver
import com.relay.app.transport.TransportStatus
import com.relay.app.transport.Transports
import com.relay.app.util.RelayPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = RelayPreferences(app)

    var autoApproveLocationRequests by mutableStateOf(prefs.autoApproveLocationRequests)
        private set

    var notifyOnAutoShare by mutableStateOf(prefs.notifyOnAutoShare)
        private set

    var locationRequestFrom by mutableStateOf(prefs.locationRequestFrom)
        private set

    var theme by mutableStateOf(prefs.theme)
        private set

    var appLock by mutableStateOf(prefs.appLock)
        private set

    var incomingPinNotification by mutableStateOf(prefs.incomingPinNotification)
        private set

    var incomingMessageNotification by mutableStateOf(prefs.incomingMessageNotification)
        private set

    var defaultMapZoom by mutableFloatStateOf(prefs.defaultMapZoom)
        private set

    var keepScreenOnMap by mutableStateOf(prefs.keepScreenOnMap)
        private set

    var defaultPinExpiry by mutableStateOf(prefs.defaultPinExpiry)
        private set

    var dndEnabled by mutableStateOf(prefs.dndEnabled)
        private set

    var dndStartHour by mutableStateOf(prefs.dndStartHour)
        private set

    var dndEndHour by mutableStateOf(prefs.dndEndHour)
        private set

    var backgroundConnection by mutableStateOf(prefs.backgroundConnection)
        private set

    var connectionStatus by mutableStateOf(TransportStatus.STOPPED)
        private set

    init {
        viewModelScope.launch {
            Transports.get(app).status.collect { connectionStatus = it }
        }
    }

    fun updateBackgroundConnection(v: Boolean) {
        prefs.backgroundConnection = v
        backgroundConnection = v
        if (v) ConnectionService.start(getApplication()) else ConnectionService.stop(getApplication())
    }

    fun reconnectNow() {
        viewModelScope.launch(Dispatchers.IO) { Transports.get(getApplication()).reconnect() }
    }

    var rotatingKey by mutableStateOf(false)
        private set

    var lastKeyRotationAt by mutableStateOf(prefs.lastKeyRotationAt)
        private set

    /** Manually retires the current E2E encryption key and broadcasts a fresh one to every
     *  already-paired contact — see KeyRotationReceiver for the same thing on a 30-day schedule. */
    fun rotateEncryptionKeyNow() {
        if (rotatingKey) return
        rotatingKey = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { KeyRotationReceiver.rotateAndBroadcast(getApplication()) }
            lastKeyRotationAt = prefs.lastKeyRotationAt
            rotatingKey = false
        }
    }

    fun updateAutoApproveLocationRequests(v: Boolean) {
        prefs.autoApproveLocationRequests = v
        autoApproveLocationRequests = v
    }

    fun updateNotifyOnAutoShare(v: Boolean) {
        prefs.notifyOnAutoShare = v
        notifyOnAutoShare = v
    }

    fun updateLocationRequestFrom(v: String) {
        prefs.locationRequestFrom = v
        locationRequestFrom = v
    }

    fun updateTheme(v: String) {
        prefs.theme = v
        theme = v
    }

    fun updateAppLock(v: Boolean) {
        prefs.appLock = v
        appLock = v
    }

    fun updateIncomingPinNotification(v: Boolean) {
        prefs.incomingPinNotification = v
        incomingPinNotification = v
    }

    fun updateIncomingMessageNotification(v: Boolean) {
        prefs.incomingMessageNotification = v
        incomingMessageNotification = v
    }

    fun updateDefaultMapZoom(v: Float) {
        prefs.defaultMapZoom = v
        defaultMapZoom = v
    }

    fun updateKeepScreenOnMap(v: Boolean) {
        prefs.keepScreenOnMap = v
        keepScreenOnMap = v
    }

    fun updateDefaultPinExpiry(v: String) {
        prefs.defaultPinExpiry = v
        defaultPinExpiry = v
    }

    fun updateDndEnabled(v: Boolean) {
        prefs.dndEnabled = v
        dndEnabled = v
    }

    fun updateDndStartHour(v: Int) {
        prefs.dndStartHour = v
        dndStartHour = v
    }

    fun updateDndEndHour(v: Int) {
        prefs.dndEndHour = v
        dndEndHour = v
    }
}
