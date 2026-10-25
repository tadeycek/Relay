package com.relay.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val CallText = Color(0xFFDBDEE1)
private val CallMuted = Color(0xFF949BA4)
private val CallBarColor = Color(0xFF1E3A2A)
private val Danger = Color(0xFFED4245)
private val Accept = Color(0xFF3BA55D)

private const val MAX_NAME_CHARS = 64

/** A contact/profile name is remote-controlled: one line, bounded, no control characters. */
internal fun boundedName(name: String?, fallback: String = "Unknown"): String {
    val clean = name.orEmpty().filter { !it.isISOControl() || it == ' ' }.trim()
    if (clean.isEmpty()) return fallback
    return if (clean.length > MAX_NAME_CHARS) clean.take(MAX_NAME_CHARS - 1) + "…" else clean
}

/** In-call bar under the thread header: peer, state text, timer once connected, mute and hang up. */
@Composable
fun CallBar(call: DesktopCallUi, actions: ChatActions, nowMs: () -> Long = System::currentTimeMillis, tick: Boolean = true) {
    var now by remember { mutableStateOf(nowMs()) }
    LaunchedEffect(call.callId, call.connectedAtMs, call.stage) {
        now = nowMs()
        // Only an active call ticks. Tests pass tick = false: an endless delay loop never lets Compose's test clock go idle.
        while (tick && call.stage == DesktopCallStage.ACTIVE) { delay(500); now = nowMs() }
    }
    Column(Modifier.fillMaxWidth().background(CallBarColor).testTag("call-bar")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(boundedName(call.peerName, "Call"), color = CallText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Text(call.status, color = CallText, fontSize = 12.sp, modifier = Modifier.testTag("call-status"))
            if (call.stage == DesktopCallStage.ACTIVE && call.connectedAtMs != null) {
                Spacer(Modifier.width(10.dp))
                Text(DesktopCallLog.duration((now - call.connectedAtMs).coerceAtLeast(0)), color = CallMuted, fontSize = 12.sp, modifier = Modifier.testTag("call-timer"))
            }
            Spacer(Modifier.weight(1f))
            if (call.live) {
                if (call.stage == DesktopCallStage.ACTIVE) {
                    OutlinedButton(onClick = { actions.toggleMute(!call.muted) }, modifier = Modifier.testTag("call-mute")) {
                        Text(if (call.muted) "Unmute" else "Mute", color = CallText, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = actions.hangUp, colors = ButtonDefaults.buttonColors(containerColor = Danger), modifier = Modifier.testTag("call-hangup")) {
                    Text(if (call.stage == DesktopCallStage.RINGING_OUT) "Cancel" else "Hang up", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        call.notice?.let { Text(it, color = Color(0xFFF2B8B5), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 6.dp).testTag("call-notice")) }
    }
}

/** The incoming-call card: same composable in the always-on-top ring window and as an in-window overlay. */
@Composable
fun RingCard(call: DesktopCallUi, actions: ChatActions, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFF2B2D31), shape = RoundedCornerShape(14.dp), modifier = modifier.testTag("call-ring")) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Incoming call", color = CallMuted, fontSize = 12.sp)
            Text(boundedName(call.peerName), color = CallText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(call.status, color = CallMuted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = actions.declineCall, colors = ButtonDefaults.buttonColors(containerColor = Danger), modifier = Modifier.testTag("call-decline")) { Text("Decline", color = Color.White) }
                Button(onClick = actions.acceptCall, colors = ButtonDefaults.buttonColors(containerColor = Accept), modifier = Modifier.testTag("call-accept")) { Text("Accept", color = Color.White) }
            }
        }
    }
}

/** A local call-log note. Rendered only for rows the call-log table produced ([ThreadRow.callLog]). */
@Composable
fun CallLogNote(row: ThreadRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("call-log-${row.id}"), horizontalArrangement = Arrangement.Center) {
        Text("☎ ${row.text}" + if (row.time.isNotEmpty()) " · ${row.time}" else "", color = CallMuted, fontSize = 12.sp)
    }
}

/** A refusal with no call to hang it on (for example a rejected start while idle). */
@Composable
fun CallNotice(text: String) {
    Text(text, color = Color(0xFFF2B8B5), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(CallBarColor).padding(horizontal = 18.dp, vertical = 6.dp).testTag("call-notice"))
}
