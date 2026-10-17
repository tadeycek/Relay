package com.relay.app.ui.screens.chat

/** Acceptance identifies the exact submitted draft, independently of later edits. */
internal data class AcceptedDraft(val composerId: String, val revision: Long, val mediaSelectionId: String?,
    val text: String = "", val reply: String? = null) {
    fun rebaseKeyboardEdit(rendered: String, current: String, next: String): String =
        if (text.isNotEmpty() && rendered != current && rendered.startsWith(text) && next.startsWith(text)) next.removePrefix(text) else next
    fun remainingText(current: String, sameRevision: Boolean, appendOnly: Boolean): String = when {
        sameRevision -> ""
        appendOnly && current.startsWith(text) -> current.removePrefix(text)
        else -> current // Replacements/in-place edits are a distinct recoverable draft.
    }
}
