package com.relay.app.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Ordinary calculator surface; no messaging UI is composed until the gate succeeds. */
@Composable
fun CalculatorScreen(allowUnlock: Boolean = true, onUnlock: () -> Unit) {
    var state by remember { mutableStateOf(CalculatorState()) }
    val context = LocalContext.current
    val store = remember(allowUnlock) { if (allowUnlock) CalculatorAccessStore.get(context) else null }

    fun press(key: String) {
        val candidate = if (key == "+") state.codeCandidate else null
        if (candidate != null && allowUnlock) {
            val result = runCatching { store?.submit(candidate) }.getOrNull()
            if (result == AccessResult.WIPE) {
                RelayWipe.wipe(context)
                return
            }
            if (result == AccessResult.ENROLLED || result == AccessResult.UNLOCKED) {
                state = CalculatorState()
                onUnlock()
                return
            }
        }
        // Wrong attempts leave a fully working calculator; no secret-screen hints.
        state = state.press(key)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(CalcBackground).systemBarsPadding().padding(horizontal = 22.dp)) {
        val landscape = maxWidth > maxHeight
        val landscapeKeyHeight = (maxHeight - 48.dp) / 5
        if (landscape) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    CalculatorDisplay(state, compact = true, Modifier.weight(1f).fillMaxWidth())
                    DeleteKey { press("⌫") }
                }
                CalculatorKeypad(
                    Modifier.weight(1.4f).fillMaxHeight().padding(vertical = 8.dp),
                    keyHeight = landscapeKeyHeight, gap = 8.dp, compact = true, onPress = ::press,
                )
            }
        } else {
            // Reserve room for the display on shorter phones as well as fitting the keypad width.
            val keyHeight = minOf((maxWidth - 36.dp) / 4, (maxHeight - 224.dp) / 5).coerceAtLeast(32.dp)
            Column(Modifier.fillMaxSize()) {
                CalculatorDisplay(state, compact = false, Modifier.weight(1f).fillMaxWidth())
                DeleteKey { press("⌫") }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF34383C)))
                CalculatorKeypad(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp),
                    keyHeight = keyHeight, gap = 12.dp, compact = false, onPress = ::press)
            }
        }
    }
}

private val CalcBackground = Color(0xFF17191B)
private val CalcAccent = Color(0xFFB7E481)

@Composable
private fun CalculatorDisplay(state: CalculatorState, compact: Boolean, modifier: Modifier) {
    Column(modifier.padding(top = if (compact) 8.dp else 30.dp), verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End) {
        Text(state.expression.ifEmpty { "0" }, color = Color.White,
            fontSize = if (compact || state.expression.length > 16) 28.sp else 42.sp,
            maxLines = 3, textAlign = TextAlign.End, lineHeight = if (compact) 36.sp else 52.sp)
        val preview = state.error ?: if (!state.evaluated && state.codeCandidate == null && state.expression.isNotEmpty())
            runCatching { CalculatorMath.evaluate(state.expression) }.getOrDefault("") else ""
        Text(preview, color = if (state.error != null) Color(0xFFFF9D92) else Color(0xFF9DABA0),
            fontSize = if (compact) 20.sp else 24.sp, maxLines = 2,
            modifier = Modifier.padding(top = 18.dp, bottom = if (compact) 8.dp else 24.dp))
    }
}

@Composable
private fun DeleteKey(onPress: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.End) {
        Text("⌫", color = CalcAccent, fontSize = 27.sp, modifier = Modifier.clip(CircleShape)
            .clickable(onClick = onPress).padding(horizontal = 20.dp, vertical = 10.dp))
    }
}

@Composable
private fun CalculatorKeypad(modifier: Modifier, keyHeight: Dp, gap: Dp, compact: Boolean, onPress: (String) -> Unit) {
    val rows = listOf(listOf("C", "()", "%", "÷"), listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "−"), listOf("1", "2", "3", "+"), listOf("+/−", "0", ".", "="))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(keyHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { key ->
                    val operator = key in listOf("()", "%", "÷", "×", "−", "+")
                    Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .background(if (key == "=") CalcAccent else Color(0xFF282C2F))
                        .clickable { onPress(key) }, contentAlignment = Alignment.Center) {
                        Text(key, color = when { key == "=" -> CalcBackground; key == "C" -> Color(0xFFFF9D92)
                            operator -> CalcAccent; else -> Color.White },
                            fontSize = if (compact) 24.sp else if (key == "+/−") 27.sp else 33.sp,
                            fontWeight = FontWeight.Normal)
                    }
                }
            }
        }
    }
}
