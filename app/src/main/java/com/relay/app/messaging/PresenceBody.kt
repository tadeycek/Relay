package com.relay.app.messaging

import org.json.JSONObject

/** Authenticated encrypted leases describe private-app activity, never relay connectivity. */
object PresenceBody {
    const val PREFIX = "TYPE:PROFILE_PHOTO|KIND:ACTIVITY|"
    const val LEASE_MS = 60_000L
    data class Status(val time: Long, val active: Boolean)
    fun format(status: Status): String {
        require(status.time > 0)
        return PREFIX + JSONObject().put("v",1).put("time",status.time).put("active",status.active)
    }
    fun parse(body: String, now: Long): Status? = runCatching {
        require(body.startsWith(PREFIX) && body.length < 256)
        val o=JSONObject(body.removePrefix(PREFIX)); require(o.getInt("v")==1)
        Status(o.getLong("time"),o.getBoolean("active")).also {
            require(it.time>0 && it.time<=now+5_000 && !RetentionPolicy.expired(it.time,now))
        }
    }.getOrNull()
    fun newer(old: Status?, incoming: Status) = old == null || incoming.time>old.time
    fun active(status: Status, now: Long) = status.active && now-status.time in -5_000..LEASE_MS
    fun label(status: Status?, now: Long): String = when {
        status==null -> "Last active unknown"
        active(status,now) -> "Active now"
        now-status.time<60_000 -> "Last active just now"
        now-status.time<3_600_000 -> "Last active ${(now-status.time)/60_000} min ago"
        now-status.time<86_400_000 -> "Last active ${(now-status.time)/3_600_000} h ago"
        else -> "Last active ${(now-status.time)/86_400_000} d ago"
    }
}
