package com.relay.app.messaging

import org.json.JSONObject
import java.util.UUID

/** Only the authenticated original author may delete a message. Tombstones also cover late delivery. */
object DeletionBody {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:DELETE|"
    fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun format(id: String): String { require(validId(id)); return PREFIX + JSONObject().put("v", 1).put("id", id) }
    fun parse(body: String): String? = runCatching {
        require(body.startsWith(PREFIX) && body.length < 200)
        val o = JSONObject(body.removePrefix(PREFIX)); require(o.getInt("v") == 1)
        o.getString("id").also { require(validId(it)) }
    }.getOrNull()
}
