package com.relay.app.messaging

import org.json.JSONObject

/** Authenticated author's own history only. Never deletes messages from other authors. */
object PurgeBody {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:PURGE|"
    fun format(before: Long): String { require(before > 0); return PREFIX + JSONObject().put("v", 1).put("before", before) }
    fun parse(body: String): Long? = runCatching {
        require(body.startsWith(PREFIX) && body.length < 200)
        val o = JSONObject(body.removePrefix(PREFIX)); require(o.getInt("v") == 1)
        o.getLong("before").also { require(it > 0) }
    }.getOrNull()
}
