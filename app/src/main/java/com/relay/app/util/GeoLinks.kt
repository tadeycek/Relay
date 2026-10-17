package com.relay.app.util

import android.net.Uri

/**
 * Builds a `geo:` URI that opens a location in whatever maps app the user has installed. Relay has no
 * map of its own: showing tiles would mean fetching them from a map server (revealing the viewer's IP),
 * and opening in the user's own maps app keeps that a deliberate, visible action.
 *
 * Pure string building (no Android types) so it can be unit tested; see [toUri] for the Android wrapper.
 */
object GeoLinks {

    /** Characters that would break the `(label)` part of a geo URI are dropped; the label is display text only. */
    fun sanitizeLabel(label: String?): String? =
        label?.replace(Regex("""[()\p{Cc}\p{Cf}]"""), "")?.trim()?.take(60)?.takeIf { it.isNotEmpty() }

    /** e.g. `geo:46.0569,14.5058?q=46.0569,14.5058(Caf%C3%A9)`. */
    fun geoString(lat: Double, lng: Double, label: String? = null): String {
        val point = "${"%.6f".format(java.util.Locale.US, lat)},${"%.6f".format(java.util.Locale.US, lng)}"
        val name = sanitizeLabel(label)
        return if (name == null) "geo:$point?q=$point" else "geo:$point?q=$point(${percentEncode(name)})"
    }

    fun toUri(lat: Double, lng: Double, label: String? = null): Uri = Uri.parse(geoString(lat, lng, label))

    private fun percentEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
