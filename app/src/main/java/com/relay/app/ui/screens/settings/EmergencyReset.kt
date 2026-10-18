package com.relay.app.ui.screens.settings

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.relay.app.calculator.*
import com.relay.app.messaging.*
import com.relay.app.data.repository.MessageRepository
import com.relay.app.data.repository.GroupMessageRepository
import com.relay.app.data.repository.GroupRepository
import com.relay.app.data.repository.ContactRepository
import kotlinx.coroutines.*

@Composable
fun EmergencyReset() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    com.relay.app.ui.components.DangerButton("Emergency: keep calculator only", { open = true }, Modifier.fillMaxWidth())
    if (open) AlertDialog(onDismissRequest = { if (!busy) { open = false; code = "" } }, title = { Text("Erase messaging permanently?") },
        text = { Column {
            Text("Enter your four-digit unlock code. This erases this phone’s messages, contacts, photos and keys, then disables messaging. The calculator remains. Reinstalling or clearing app data can reset this mode.\n\nWe will attempt deletion requests for messages you sent for up to 15 seconds. Offline devices, old app versions, saved copies and screenshots may retain them. Undelivered requests are erased with this phone’s data.")
            OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(4) }, label = { Text("Unlock code") },
                enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
            if (busy) Text("Attempting remote deletion, then erasing this phone…")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = code.length == 4 && !busy, onClick = {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { CalculatorAccessStore.get(context).submit(code) } }.getOrNull()
                code = ""
                if (result == AccessResult.WIPE) { com.relay.app.calculator.RelayWipe.wipe(context); return@launch }
                if (result != AccessResult.UNLOCKED) {
                    error = "Code rejected. ${com.relay.app.calculator.AccessPolicy.WIPE_AFTER} wrong codes in a row erase everything."
                    busy = false
                    return@launch
                }
                // A confirmed destructive operation is process-owned, so leaving this screen cannot stop it halfway.
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        withTimeoutOrNull(15000) {
                            val db = MessagingDb.get(context)
                            val before = System.currentTimeMillis()
                            for (contact in ContactRepository(db).getAllContacts()) {
                                ensureActive()
                                if (com.relay.app.groups.GroupProtocol.eligible(contact)) runCatching { Outgoing.enqueue(context, contact, PurgeBody.format(before)) }
                            }
                            for (group in GroupRepository(db).getAllGroups()) {
                                ensureActive(); runCatching { com.relay.app.groups.SharedGroups.purgeOwnHistory(context, group.id, before) }
                            }
                            MessagingRuntime.kick()
                            while (com.relay.app.data.repository.OutboxRepository(db).queuedCountSync() > 0) delay(100)
                        }
                    } finally {
                        CalculatorOnly.arm(context)
                        MessagingRuntime.shutdown(context)
                        // Startup repeats cleanup before displaying the calculator if a task raced with shutdown.
                        runCatching { CalculatorOnly.clean(context) }
                        withContext(Dispatchers.Main) { (context as? android.app.Activity)?.finishAffinity() }
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            }
        }) { Text(if (busy) "Erasing…" else "Confirm and erase", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { open = false; code = "" }) { Text("Cancel") } })
}
