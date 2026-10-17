package com.relay.app.messaging

data class PreviewLine(val text: String, val timestamp: Long)

/** Only the currently displayed notification retains these five preview lines. */
object PreviewHistory {
    fun update(previous: List<PreviewLine>, text: String?, refresh: Boolean, now: Long): List<PreviewLine> =
        if (refresh) previous.takeLast(5) else previous.takeLast(4) + PreviewLine(text.orEmpty().take(240), now)
}
