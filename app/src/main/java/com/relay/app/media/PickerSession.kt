package com.relay.app.media

import androidx.compose.runtime.staticCompositionLocalOf

class PickerSession(val launch: () -> Unit, val returned: () -> Unit)
val LocalPickerSession = staticCompositionLocalOf { PickerSession({}, {}) }
