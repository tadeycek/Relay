package com.relay.app.calls.group

import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallEffect
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** WP16 step 2 (starting): roster-bound create, honest 409/403 handling, group_start only after a 200. */
class GroupCallStartTest {
    class FakeHttp(var answer: (String, String, String?) -> HttpAnswer) : GroupCallHttp {
        val calls = mutableListOf<Triple<String, String, String?>>()
        override suspend fun request(method: String, path: String, body: String?): HttpAnswer {
            calls += Triple(method, path, body); return answer(method, path, body)
        }
    }
    class Sent : AnnouncementSender { val wires = mutableListOf<String>(); override fun broadcast(wire: String): Int { wires += wire; return 2 } }

    private val roster = G.roster(members = listOf(G.carol, G.alice, G.bob)) // deliberately unsorted
    private val store = MemoryStore()
    private val coordinator = GroupCallCoordinator(roster, G.bob, { G.NOW }, store)
    private val sent = Sent()
    private fun starter(http: FakeHttp) = GroupCallStarter(http, coordinator, sent, G.bob, "primary", { G.NOW }, G.rng(5))
    private fun err(code: String, retry: Boolean = false, extra: String = "") = """{"v":1,"error":{"code":"$code","retryable":$retry}$extra}"""

    @Test fun createBodyIsRosterBoundWithSortedUniqueMembersIncludingTheStarter() {
        val a = G.start(G.bob, roster)
        val json = JSONObject(GroupCallRest.createBody(a, roster))
        assertEquals(1, json.getInt("v"))
        assertEquals(a.room, json.getString("room")); assertTrue(a.room.matches(Regex("[0-9a-f]{32}")))
        assertEquals(GroupCallCodec.secretHash(a.joinSecret!!), json.getString("secretHash"))
        assertEquals(G.GROUP, json.getString("group")); assertEquals(1L, json.getLong("revision"))
        assertEquals(GroupCallCodec.rosterHash(roster), json.getString("rosterHash"))
        val members = (0 until json.getJSONArray("members").length()).map { json.getJSONArray("members").getString(it) }
        assertEquals(listOf(G.alice, G.bob, G.carol), members)
        assertTrue(G.bob in members)
        assertFalse("the join secret itself never goes to the server", json.toString().contains(a.joinSecret!!))
    }

    @Test fun groupStartIsSentOnlyAfterA200AndCarriesTheSecretTheServerHashed() = runBlocking {
        val http = FakeHttp { _, _, _ -> HttpAnswer(200, """{"v":1,"created":true,"rostered":true}""") }
        val out = starter(http).start(roster) as StartOutcome.Started
        assertEquals(1, http.calls.size); assertEquals("POST", http.calls[0].first); assertEquals("/v1/call/rooms", http.calls[0].second)
        assertEquals(1, sent.wires.size)
        val wire = JSONObject(GroupCallWire.unwrap(sent.wires[0])!!)
        assertEquals("group_start", wire.getString("op")); assertEquals(out.announcement.room, wire.getString("room"))
        assertEquals(GroupCallCodec.secretHash(wire.getString("joinSecret")), JSONObject(http.calls[0].third!!).getString("secretHash"))
        assertEquals(out.announcement.room, coordinator.liveBanner()?.room)
    }

    @Test fun groupRoomExistsJoinsTheWinnersRoomAndSendsNothing() = runBlocking {
        val winner = "f".repeat(32)
        val http = FakeHttp { _, _, _ -> HttpAnswer(409, err("group_room_exists", false, ""","room":"$winner"""")) }
        val out = starter(http).start(roster)
        assertEquals(StartOutcome.JoinWinner(winner), out)
        assertTrue("own secret must not be announced", sent.wires.isEmpty())
        assertNull(coordinator.liveBanner())
        assertEquals("never a legacy fallback", 1, http.calls.size)
    }

    @Test fun staleRevisionNotInRosterAndRosterRequiredAreReportedHonestlyAndNeverRetriedLegacy() = runBlocking {
        for ((status, code, expected) in listOf(
            Triple(409, "stale_revision", StartOutcome.StaleRevision),
            Triple(403, "not_in_roster", StartOutcome.NotInRoster),
            Triple(400, "roster_required", StartOutcome.RosterRequired),
        )) {
            val http = FakeHttp { _, _, _ -> HttpAnswer(status, err(code)) }
            assertEquals(code, expected, starter(http).start(roster))
            assertEquals(1, http.calls.size)
            assertTrue(JSONObject(http.calls[0].third!!).has("members"))
        }
        assertTrue(sent.wires.isEmpty()); assertNull(coordinator.liveBanner())
    }

    @Test fun transportAndServerFailuresAreRetryableOnlyWhenTheServerSaysSo() = runBlocking {
        assertEquals(StartOutcome.Failed(true, null), starter(FakeHttp { _, _, _ -> HttpAnswer(0, "") }).start(roster))
        assertEquals(StartOutcome.Failed(true, "rate_limited"), starter(FakeHttp { _, _, _ -> HttpAnswer(429, err("rate_limited", true)) }).start(roster))
        assertEquals(StartOutcome.Failed(false, "not_allowed"), starter(FakeHttp { _, _, _ -> HttpAnswer(403, err("not_allowed")) }).start(roster))
        assertTrue(sent.wires.isEmpty())
    }

    @Test fun parseCreateRejectsGarbageBodiesWithoutThrowing() {
        assertTrue(GroupCallRest.parseCreate(409, "not json") is CreateResult.Failed)
        assertTrue("an invalid winner room id is not followed", GroupCallRest.parseCreate(409, err("group_room_exists", false, ""","room":"nothex"""")) is CreateResult.Failed)
        assertEquals(CreateResult.Created, GroupCallRest.parseCreate(200, """{"v":1,"created":true,"rostered":true}"""))
        assertTrue("a 200 that is not rostered is not accepted as a roster-bound room", GroupCallRest.parseCreate(200, """{"v":1,"created":true}""") is CreateResult.Failed)
    }

    @Test fun endPostsEndThenSendsGroupEndAndNonCreatorsCannotEnd() = runBlocking {
        val http = FakeHttp { _, _, _ -> HttpAnswer(200, """{"v":1,"created":true,"rostered":true}""") }
        val s = starter(http); val started = (s.start(roster) as StartOutcome.Started).announcement
        sent.wires.clear(); http.calls.clear()
        http.answer = { _, _, _ -> HttpAnswer(200, """{"v":1,"ended":true}""") }
        assertEquals(EndOutcome.Ended, s.end(started))
        assertEquals("POST", http.calls[0].first); assertEquals("/v1/call/rooms/${started.room}/end?device=primary", http.calls[0].second); assertNull(http.calls[0].third)
        assertEquals("group_end", JSONObject(GroupCallWire.unwrap(sent.wires.single())!!).getString("op"))
        assertNull(coordinator.liveBanner())
        // somebody else's announcement: not the creator
        val theirs = G.start(G.alice, roster, seed = 40)
        assertEquals(EndOutcome.NotCreator, s.end(theirs))
    }

    @Test fun endThatTheServerDidNotConfirmSendsNoGroupEnd() = runBlocking {
        val http = FakeHttp { _, _, _ -> HttpAnswer(200, """{"v":1,"created":true,"rostered":true}""") }
        val s = starter(http); val a = (s.start(roster) as StartOutcome.Started).announcement
        sent.wires.clear()
        http.answer = { _, _, _ -> HttpAnswer(502, err("upstream_failed", true)) }
        assertEquals(EndOutcome.Failed(true), s.end(a))
        assertTrue(sent.wires.isEmpty())
        http.answer = { _, _, _ -> HttpAnswer(404, err("not_found")) } // already gone: the end is still true to announce
        assertEquals(EndOutcome.Ended, s.end(a))
        assertEquals(1, sent.wires.size)
    }

    @Test fun secureRandomMintsFreshRoomAndSecretOfTheRightShape() {
        val first = GroupCallCodec.newStart(roster, G.bob, "primary", G.NOW, SecureBytes.provider)
        val second = GroupCallCodec.newStart(roster, G.bob, "primary", G.NOW, SecureBytes.provider)
        assertTrue(first.room.matches(Regex("[0-9a-f]{32}"))); assertTrue(first.joinSecret!!.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first.room, second.room); assertNotEquals(first.joinSecret, second.joinSecret)
    }
}
