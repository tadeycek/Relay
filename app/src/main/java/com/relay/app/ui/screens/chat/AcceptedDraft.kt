package com.relay.app.ui.screens.chat

/** ViewModel retains acceptance across screen recreation; the UI matches the submitted generation. */
internal data class AcceptedDraft(val composerId: String, val revision: Long, val mediaSelectionId: String?)
