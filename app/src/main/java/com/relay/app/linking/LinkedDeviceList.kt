package com.relay.app.linking

import org.json.JSONObject

/** One computer this identity has linked, as `GET /v1/devices` reports it. */
data class LinkedDevice(
    val id: String,
    val label: String,
    val addedAt: Long,
    val lastSeen: Long,
    val active: Boolean,
) {
    /** What the account screen shows: the label, or a short id when the phone never named it. */
    val displayName: String get() = label.trim().ifEmpty { "Computer ${id.take(4)}" }
}

/**
 * Parses the `GET /v1/devices` reply:
 * `{"v":1,"devices":[{device,label,added_at,last_seen,active}],"head":<seq>}`.
 *
 * The phone's own `primary` row is dropped — it is not a linked computer. Hostile or truncated input
 * yields [Result.failure] rather than an exception; the screen then shows "couldn't load" instead of
 * an empty list that would look like "no computers are linked".
 *
 * Pure: no I/O, no Android types.
 */
object LinkedDeviceList {

    /** Bound on rows the parser will allocate, well above the server's cap of three linked devices. */
    const val MAX_DEVICES = 64

    fun parse(json: String?): Result<List<LinkedDevice>> {
        if (json.isNullOrBlank()) return Result.failure(IllegalArgumentException("empty reply"))
        return try {
            val root = JSONObject(json)
            val array = root.optJSONArray("devices") ?: return Result.failure(IllegalArgumentException("no devices"))
            val devices = buildList {
                for (i in 0 until minOf(array.length(), MAX_DEVICES)) {
                    val row = array.optJSONObject(i) ?: continue
                    val id = row.optString("device")
                    if (id.isEmpty() || id == "primary") continue
                    add(
                        LinkedDevice(
                            id = id,
                            label = row.optString("label"),
                            addedAt = row.optLong("added_at"),
                            lastSeen = row.optLong("last_seen"),
                            active = row.optBoolean("active", true),
                        ),
                    )
                }
            }
            Result.success(devices)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
