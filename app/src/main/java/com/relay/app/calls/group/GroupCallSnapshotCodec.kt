package com.relay.app.calls.group

import android.content.Context
import com.relay.app.call.group.GroupCallFenceSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Only replay ids, room identities and expiry times: never the room capability or audio keys. */
object GroupCallSnapshotCodec {
    private const val MAX_BYTES = 48_000
    private val ROOM = Regex("[0-9a-f]{32}")
    private val KEY = Regex("[0-9a-f]{64}")

    fun encode(snapshot: GroupCallFenceSnapshot): String {
        val ended = JSONArray()
        snapshot.ended.forEach { (identity, expiry) -> ended.put(JSONArray().put(identity.first).put(identity.second).put(expiry)) }
        val replayed = JSONArray()
        snapshot.replayed.forEach { (id, expiry) -> replayed.put(JSONArray().put(id).put(expiry)) }
        val active = snapshot.activeRoom?.let { JSONArray().put(it.first).put(it.second) }
        return JSONObject().put("v", 1).put("ended", ended).put("replayed", replayed).put("active", active ?: JSONObject.NULL)
            .toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    /** Corruption throws: the caller must fail closed, not restore an empty replay fence. */
    fun decode(wire: String): GroupCallFenceSnapshot {
        require(wire.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        try {
            val o = JSONObject(wire)
            require(o.length() == 4 && o.getInt("v") == 1 && o.has("ended") && o.has("replayed") && o.has("active"))
            val endedRows = o.getJSONArray("ended")
            val replayRows = o.getJSONArray("replayed")
            require(endedRows.length() <= 256 && replayRows.length() <= 256)
            val ended = LinkedHashMap<Pair<String, String>, Long>()
            for (i in 0 until endedRows.length()) {
                val row = endedRows.getJSONArray(i)
                require(row.length() == 3)
                val room = row.getString(0); val caller = row.getString(1)
                require(ROOM.matches(room) && KEY.matches(caller) && row.getLong(2) > 0)
                require(ended.put(room to caller, row.getLong(2)) == null)
            }
            val replayed = LinkedHashMap<String, Long>()
            for (i in 0 until replayRows.length()) {
                val row = replayRows.getJSONArray(i)
                require(row.length() == 2)
                val id = row.getString(0)
                require(ROOM.matches(id) && row.getLong(1) > 0)
                require(replayed.put(id, row.getLong(1)) == null)
            }
            val active = if (o.isNull("active")) null else o.getJSONArray("active").let {
                require(it.length() == 2)
                val room = it.getString(0); val caller = it.getString(1)
                require(ROOM.matches(room) && KEY.matches(caller))
                room to caller
            }
            return GroupCallFenceSnapshot(ended, replayed, active)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid group call replay snapshot", e)
        }
    }
}

/** Synchronous durable commit before a banner/effect can be released. Separate key per shared group. */
class AndroidGroupCallStore(context: Context, groupId: String, owner: String) : GroupCallStore {
    private val preferences = context.applicationContext.getSharedPreferences("group_call_fences_v1", Context.MODE_PRIVATE)
    private val key = "${owner}:${groupId}"
    override fun load(): GroupCallFenceSnapshot? = preferences.getString(key, null)?.let(GroupCallSnapshotCodec::decode)
    override fun commit(snapshot: GroupCallFenceSnapshot) {
        check(preferences.edit().putString(key, GroupCallSnapshotCodec.encode(snapshot)).commit()) { "Group call replay fence not persisted" }
    }
}
