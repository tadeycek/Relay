package com.relay.app.ui.screens.map

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Contact
import com.relay.app.data.model.Group
import com.relay.app.data.model.MapStyle
import com.relay.app.data.model.Message
import com.relay.app.data.model.MessageType
import com.relay.app.data.model.PinExpiry
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.data.repository.GroupRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.sms.RelaySecureSend
import com.relay.app.util.RelayPreferences
import com.relay.app.util.SmsMessageParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

data class MapUiState(
    val droppedPin: GeoPoint? = null,
    val contacts: List<Contact> = emptyList(),
    val groups: List<Group> = emptyList(),
    val selectedContactId: Long? = null,
    val selectedGroupId: Long? = null,
    val showSendSheet: Boolean = false,
    val sendSuccess: Boolean = false,
    val mapStyle: MapStyle = MapStyle.STANDARD,
    val savedPins: List<Message> = emptyList(),
    val pinLabel: String = "",
    val pinExpiry: PinExpiry = PinExpiry.NEVER,
)

class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val db = RelayDbHelper(app)
    private val contactRepo = ContactRepository(db)
    private val messageRepo = MessageRepository(db)
    private val groupRepo = GroupRepository(db)
    private val groupMessageRepo = GroupMessageRepository(db)
    private val prefs = RelayPreferences(app)

    private val _uiState = MutableStateFlow(MapUiState(
        mapStyle = MapStyle.fromKey(prefs.mapStyle),
        pinExpiry = PinExpiry.fromPrefsKey(prefs.defaultPinExpiry),
    ))
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        viewModelScope.launch {
            val relayContacts = contactRepo.getAllContacts().filter { it.hasRelay }
            val groups = groupRepo.getAllGroups()
            val savedPins = messageRepo.getSavedPins()
            _uiState.update { it.copy(contacts = relayContacts, groups = groups, savedPins = savedPins) }
        }
    }

    fun dropPin(point: GeoPoint) {
        _uiState.update { it.copy(droppedPin = point, showSendSheet = true, selectedContactId = null, selectedGroupId = null) }
    }

    fun dismissSheet() {
        _uiState.update { it.copy(showSendSheet = false, selectedContactId = null, selectedGroupId = null) }
    }

    fun clearPin() {
        _uiState.update { it.copy(droppedPin = null, showSendSheet = false, selectedContactId = null, selectedGroupId = null) }
    }

    fun selectContact(id: Long) {
        _uiState.update { it.copy(selectedContactId = id, selectedGroupId = null) }
    }

    fun selectGroup(id: Long) {
        _uiState.update { it.copy(selectedGroupId = id, selectedContactId = null) }
    }

    fun setPinLabel(label: String) {
        _uiState.update { it.copy(pinLabel = label.take(30)) }
    }

    fun setPinExpiry(expiry: PinExpiry) {
        _uiState.update { it.copy(pinExpiry = expiry) }
    }

    fun setMapStyle(style: MapStyle, context: Context) {
        prefs.mapStyle = style.prefsKey
        _uiState.update { it.copy(mapStyle = style) }
    }

    fun sendPin(context: Context) {
        val state = _uiState.value
        val pin = state.droppedPin ?: return

        if (state.selectedContactId != null) {
            sendPinToContact(context, pin, state.selectedContactId, state.pinLabel, state.pinExpiry)
        } else if (state.selectedGroupId != null) {
            sendPinToGroup(context, pin, state.selectedGroupId, state.pinLabel, state.pinExpiry)
        }
    }

    private fun sendPinToContact(
        context: Context,
        pin: GeoPoint,
        contactId: Long,
        pinLabel: String,
        pinExpiry: PinExpiry,
    ) {
        val contact = _uiState.value.contacts.find { it.id == contactId } ?: return

        viewModelScope.launch {
            val body = SmsMessageParser.formatLocation(
                pin.latitude, pin.longitude,
                expiry = pinExpiry,
                label = pinLabel.ifBlank { null },
            )
            val sent = RelaySecureSend.send(context, contactRepo, contact, body)
            if (sent) {
                val expiryAt = pinExpiry.durationMs?.let { System.currentTimeMillis() + it }
                messageRepo.insertMessage(Message(
                    contactId = contactId,
                    body = body,
                    type = MessageType.LOCATION,
                    lat = pin.latitude,
                    lng = pin.longitude,
                    isSent = true,
                    pinLabel = pinLabel.ifBlank { null },
                    expiryAt = expiryAt,
                    msgId = System.currentTimeMillis().toString(),
                ))
                val savedPins = messageRepo.getSavedPins()
                _uiState.update {
                    it.copy(showSendSheet = false, droppedPin = null, selectedContactId = null, sendSuccess = true, savedPins = savedPins)
                }
            }
        }
    }

    private fun sendPinToGroup(
        context: Context,
        pin: GeoPoint,
        groupId: Long,
        pinLabel: String,
        pinExpiry: PinExpiry,
    ) {
        val group = _uiState.value.groups.find { it.id == groupId } ?: return

        viewModelScope.launch {
            val body = SmsMessageParser.formatLocation(
                pin.latitude, pin.longitude,
                expiry = pinExpiry,
                label = pinLabel.ifBlank { null },
            )
            val expiryAt = pinExpiry.durationMs?.let { System.currentTimeMillis() + it }
            val ts = System.currentTimeMillis()

            for (member in group.members) {
                RelaySecureSend.send(context, contactRepo, member, body)
            }
            groupMessageRepo.insertMessage(
                com.relay.app.data.model.GroupMessage(
                    groupId = groupId,
                    contactId = null,
                    body = body,
                    type = MessageType.LOCATION,
                    lat = pin.latitude,
                    lng = pin.longitude,
                    isSent = true,
                    timestamp = ts,
                    pinLabel = pinLabel.ifBlank { null },
                    expiryAt = expiryAt,
                )
            )
            val savedPins = messageRepo.getSavedPins()
            _uiState.update {
                it.copy(showSendSheet = false, droppedPin = null, selectedGroupId = null, sendSuccess = true, savedPins = savedPins)
            }
        }
    }

    fun reloadSavedPins() {
        viewModelScope.launch {
            val savedPins = messageRepo.getSavedPins()
            _uiState.update { it.copy(savedPins = savedPins) }
        }
    }

    fun reloadDefaults() {
        _uiState.update {
            it.copy(
                pinExpiry = PinExpiry.fromPrefsKey(prefs.defaultPinExpiry),
                mapStyle = MapStyle.fromKey(prefs.mapStyle),
            )
        }
    }

    fun onContactsResumed() {
        loadAll()
    }
}
