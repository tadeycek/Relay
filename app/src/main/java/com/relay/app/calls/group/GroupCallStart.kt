package com.relay.app.calls.group

import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.GroupCallCodec
import com.relay.app.groups.GroupProtocol
import org.json.JSONObject
import java.security.SecureRandom

data class HttpAnswer(val status: Int, val body: String)

interface GroupCallHttp {
    suspend fun request(method: String, path: String, body: String?): HttpAnswer
}

/** Hands one sealed announcement wire to every other group member. Returns how many legs were queued. */
fun interface AnnouncementSender {
    fun broadcast(wire: String): Int
}

sealed interface CreateResult {
    data object Created : CreateResult
    data class GroupRoomExists(val room: String) : CreateResult
    data object StaleRevision : CreateResult
    data object NotInRoster : CreateResult
    data object RosterRequired : CreateResult
    data class Failed(val status: Int, val code: String?, val retryable: Boolean) : CreateResult
}

object GroupCallRest {
    private val HEX32 = Regex("[0-9a-f]{32}")

    /** The roster-bound create body (PROTOCOL.md "Roster-bound rooms (G5)"). The join secret itself never appears. */
    fun createBody(a: GroupAnnouncement, roster: GroupProtocol.State): String {
        val members = (roster.members.map { it.key } + a.caller).distinct().sorted()
        return JSONObject()
            .put("v", 1).put("room", a.room).put("secretHash", GroupCallCodec.secretHash(requireNotNull(a.joinSecret)))
            .put("group", roster.id).put("revision", roster.revision).put("rosterHash", GroupCallCodec.rosterHash(roster))
            .put("members", org.json.JSONArray(members))
            .toString()
    }

    internal fun errorOf(status: Int, body: String): Triple<String?, Boolean, JSONObject?> {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val error = json?.optJSONObject("error")
        val code = error?.optString("code")?.takeIf { it.isNotEmpty() }
        val retryable = when {
            status == 0 -> true
            error != null && error.has("retryable") -> error.optBoolean("retryable")
            else -> status == 429 || status >= 500
        }
        return Triple(code, retryable, json)
    }

    fun parseCreate(status: Int, body: String): CreateResult {
        val (code, retryable, json) = errorOf(status, body)
        return when {
            status == 200 ->
                if (json?.optBoolean("created") == true && json.optBoolean("rostered")) CreateResult.Created
                else CreateResult.Failed(200, "not_rostered", false)
            status == 409 && code == "group_room_exists" ->
                json?.optString("room")?.takeIf { HEX32.matches(it) }?.let { CreateResult.GroupRoomExists(it) }
                    ?: CreateResult.Failed(409, code, retryable)
            status == 409 && code == "stale_revision" -> CreateResult.StaleRevision
            status == 403 && code == "not_in_roster" -> CreateResult.NotInRoster
            status == 400 && code == "roster_required" -> CreateResult.RosterRequired
            else -> CreateResult.Failed(status, code, retryable)
        }
    }
}

sealed interface StartOutcome {
    data class Started(val announcement: GroupAnnouncement, val sentTo: Int) : StartOutcome
    /** Another member's room won; wait for the winner's own group_start (it carries the secret) and join that. */
    data class JoinWinner(val room: String) : StartOutcome
    data object StaleRevision : StartOutcome
    data object NotInRoster : StartOutcome
    data object RosterRequired : StartOutcome
    data class Failed(val retryable: Boolean, val code: String?) : StartOutcome
}

sealed interface EndOutcome {
    data object Ended : EndOutcome
    data class Failed(val retryable: Boolean) : EndOutcome
    data object NotCreator : EndOutcome
}

/** CSPRNG bytes for room ids, join secrets and event ids. Never a seeded or predictable source. */
object SecureBytes {
    private val random = SecureRandom()
    val provider: (Int) -> ByteArray = { size -> ByteArray(size).also(random::nextBytes) }
}

class GroupCallStarter(
    private val http: GroupCallHttp,
    private val coordinator: GroupCallCoordinator,
    private val sender: AnnouncementSender,
    private val self: String,
    private val device: String,
    private val clock: () -> Long,
    private val random: (Int) -> ByteArray = SecureBytes.provider,
) {
    /**
     * Mint room + secret, create the roster-bound room, and only on a 200 announce it to the group. A lost
     * race (409 group_room_exists) discards our room and secret: the winner's secret arrives in its own
     * group_start. No error ever falls back to a legacy (unrostered) create.
     */
    suspend fun start(roster: GroupProtocol.State): StartOutcome {
        // WP17: the starter owns the call key. One fresh 32-byte key from SecureRandom (the injected provider
        // is SecureBytes.provider in production), epoch 0, and it only ever leaves this process inside the
        // E2E-sealed group_start below - never in the roster-bound create body, the room record or a log.
        val callKey = GroupCallE2eeKey.fresh(random)
        val a = GroupCallCodec.newStart(roster, self, device, clock(), random,
            keyEpoch = callKey.epoch.toLong(), callKey = callKey.copyKey())
        val answer = http.request("POST", "/v1/call/rooms", GroupCallRest.createBody(a, roster))
        return when (val result = GroupCallRest.parseCreate(answer.status, answer.body)) {
            CreateResult.Created -> announce(a)
            is CreateResult.GroupRoomExists -> StartOutcome.JoinWinner(result.room)
            CreateResult.StaleRevision -> StartOutcome.StaleRevision
            CreateResult.NotInRoster -> StartOutcome.NotInRoster
            CreateResult.RosterRequired -> StartOutcome.RosterRequired
            is CreateResult.Failed -> StartOutcome.Failed(result.retryable, result.code)
        }
    }

    private suspend fun announce(a: GroupAnnouncement): StartOutcome {
        val wire = GroupCallWire.wrap(GroupCallCodec.encode(a))
        val local = coordinator.onWire(self, wire)
        if (local !is IngressResult.Accepted) {
            // The room exists on the server but this device cannot track it: do not announce it, take it down.
            runCatching { http.request("POST", endPath(a.room), null) }
            return StartOutcome.Failed(false, "local_state")
        }
        return StartOutcome.Started(a, sender.broadcast(wire))
    }

    private fun endPath(room: String) = "/v1/call/rooms/$room/end?device=$device"

    /** Creator only: end the room on the server, then (only if that is confirmed or the room is already gone) announce it. */
    suspend fun end(announcement: GroupAnnouncement): EndOutcome {
        if (announcement.caller != self || announcement.op != "group_start") return EndOutcome.NotCreator
        val answer = http.request("POST", endPath(announcement.room), null)
        if (answer.status != 200 && answer.status != 404) {
            return EndOutcome.Failed(GroupCallRest.errorOf(answer.status, answer.body).second)
        }
        val end = GroupCallCodec.newEnd(announcement, clock(), random)
        val wire = GroupCallWire.wrap(GroupCallCodec.encode(end))
        coordinator.onWire(self, wire)
        sender.broadcast(wire)
        return EndOutcome.Ended
    }
}
