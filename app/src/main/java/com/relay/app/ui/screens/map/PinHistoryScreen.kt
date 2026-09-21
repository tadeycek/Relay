package com.relay.app.ui.screens.map

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.model.Message
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.MessageRepository
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.theme.Accent
import com.relay.app.ui.theme.Background
import com.relay.app.ui.theme.IbmPlexMono
import com.relay.app.ui.theme.IbmPlexSans
import com.relay.app.ui.theme.Surface1
import com.relay.app.ui.theme.TextPrimary
import com.relay.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val pinHistoryDateFormat = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())

internal data class PinHistoryItem(
    val message: Message,
    val contactName: String,
)

// Must not be private: the default ViewModel factory instantiates it reflectively and a private
// class throws IllegalAccessException (this crashed the app when opening Pin History).
internal class PinHistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val db = RelayDbHelper(app)
    private val messageRepo = MessageRepository(db)
    private val contactRepo = ContactRepository(db)
    private val _items = MutableStateFlow<List<PinHistoryItem>>(emptyList())
    val items: StateFlow<List<PinHistoryItem>> = _items.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val contactsById = contactRepo.getAllContacts().associateBy { it.id }
            _items.value = messageRepo.getPinHistory().map { msg ->
                PinHistoryItem(
                    message = msg,
                    contactName = contactsById[msg.contactId]?.name ?: "Unknown contact",
                )
            }
        }
    }
}

@Composable
fun PinHistoryScreen(navController: NavController) {
    val vm: PinHistoryViewModel = viewModel()
    val items by vm.items.collectAsState()

    Scaffold(
        topBar = {
            RelayTopBar(
                title = "Pin History",
                onBack = { navController.popBackStack() },
            )
        },
        containerColor = Background,
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No pins yet",
                    color = TextSecondary,
                    fontFamily = IbmPlexSans,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.message.id }) { item ->
                    val msg = item.message
                    val expiryText = when {
                        msg.expiryAt == null -> "Never expires"
                        msg.expiryAt <= System.currentTimeMillis() -> "Expired"
                        else -> "Expires ${pinHistoryDateFormat.format(Date(msg.expiryAt))}"
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Surface1, RectangleShape)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.contactName, color = TextPrimary, fontFamily = IbmPlexSans, fontSize = 14.sp)
                            Text(
                                pinHistoryDateFormat.format(Date(msg.timestamp)),
                                color = TextSecondary,
                                fontFamily = IbmPlexMono,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            text = msg.pinLabel ?: "Unlabeled pin",
                            color = TextPrimary,
                            fontFamily = IbmPlexSans,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "${"%.5f".format(msg.lat)}, ${"%.5f".format(msg.lng)}",
                            color = TextSecondary,
                            fontFamily = IbmPlexMono,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = expiryText,
                            color = if (expiryText == "Expired") TextSecondary else Accent,
                            fontFamily = IbmPlexMono,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
