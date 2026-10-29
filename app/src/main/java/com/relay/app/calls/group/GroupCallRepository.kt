package com.relay.app.calls.group

import android.content.Context
import org.json.JSONObject

/**
 * The current group call as the UI needs it across a rotation: room identity, caller and expiry. It never
 * contains the join secret, a provider credential or audio keys.
 *
 * This is deliberately a *display* record. On a cold process start it is purged before any UI can read it,
 * because a call whose engine fences were not restored must never reappear as a live banner. The engine
 * snapshot in [AndroidGroupCallStore] is the only durable call state, and it also carries no secret.
 */
data class StoredGroupCall(val groupId: Long, val room: String, val caller: String, val created: Long, val expires: Long)

class GroupCallRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(): Map<Long, StoredGroupCall> {
        val out = LinkedHashMap<Long, StoredGroupCall>()
        for ((key, raw) in prefs.all) {
            if (raw !is String) continue
            val groupId = key.toLongOrNull() ?: continue
            runCatching { decode(raw)?.let { out[groupId] = it } }
        }
        return out
    }

    fun save(call: StoredGroupCall) {
        require(call.room.matches(ROOM) && call.caller.matches(KEY) && call.expires > 0)
        prefs.edit().putString(call.groupId.toString(), encode(call)).commit()
    }

    fun clear(groupId: Long) {
        prefs.edit().remove(groupId.toString()).commit()
    }

    /** A cold start has no coordinator, no room socket and no in-memory secret: never show its old banners. */
    fun purgeOnColdStart() {
        prefs.edit().clear().commit()
    }

    private fun encode(call: StoredGroupCall): String = JSONObject()
        .put("v", 1).put("id", call.groupId).put("room", call.room).put("caller", call.caller)
        .put("created", call.created).put("expires", call.expires).toString()

    private fun decode(raw: String): StoredGroupCall? {
        if (raw.length > 512) return null
        val o = JSONObject(raw)
        if (o.optInt("v", -1) != 1) return null
        val room = o.optString("room")
        val caller = o.optString("caller")
        val created = o.optLong("created", 0)
        val expires = o.optLong("expires", 0)
        if (!room.matches(ROOM) || !caller.matches(KEY) || created <= 0 || expires <= created) return null
        return StoredGroupCall(o.optLong("id", -1), room, caller, created, expires)
    }

    private companion object {
        const val PREFS = "group_call_state_v1"
        val ROOM = Regex("[0-9a-f]{32}")
        val KEY = Regex("[0-9a-f]{64}")
    }
}
