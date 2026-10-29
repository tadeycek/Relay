package com.relay.app.calls.group

import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupParticipant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** WP16 steps 2-3: frames drive the engine's subscription diff, the SFU follows serially, everything is torn down. */
class GroupCallSessionCoreTest {
    private val me = "0123456789abcdef"
    private val pidAlice = "aaaaaaaaaaaaaaaa"; private val pidMal = "dddddddddddddddd"; private val pidCarol = "cccccccccccccccc"

    class Http : GroupCallHttp {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var live: (String) -> HttpAnswer = { HttpAnswer(404, """{"v":1,"error":{"code":"not_found","retryable":false}}""") }
        var roster: ((String) -> HttpAnswer)? = null
        override suspend fun request(method: String, path: String, body: String?): HttpAnswer {
            calls += Triple(method, path, body)
            return when {
                method == "GET" -> live(path)
                path.contains("/roster") -> roster?.invoke(path) ?: HttpAnswer(200, """{"v":1,"removed":1}""")
                else -> HttpAnswer(200, "{}")
            }
        }
    }
    class Listener : GroupCallSessionListener {
        val phases = mutableListOf<SessionPhase>(); var participants: List<GroupParticipant> = emptyList(); val warned = mutableListOf<String>()
        var deafened: Boolean? = null; var finished: Pair<SessionEnd, String>? = null
        override fun onPhase(phase: SessionPhase) { phases += phase }
        override fun onParticipants(participants: List<GroupParticipant>, deafened: Map<String, Boolean>) { this.participants = participants }
        override fun onWarnUnknown(pid: String) { warned += pid }
        override fun onDeafened(deafened: Boolean) { this.deafened = deafened }
        override fun onFinished(end: SessionEnd, message: String) { finished = end to message }
    }

    private val roster = G.roster()
    private val start = G.start(G.alice, roster)   // Alice (creator of the room) announced it
    private val store = MemoryStore()
    private val coordinator = GroupCallCoordinator(roster, G.bob, { G.NOW }, store)
    private val http = Http(); private val fake = GroupRoomClientTest.Fake(); private val listener = Listener()
    private val api = SfuSessionTest.FakeApi(); private val peer = SfuSessionTest.FakePeer(api); private val sfu = SfuSession(api, peer)

    private fun liveOk(path: String) = HttpAnswer(200, """{"v":1,"room":"${start.room}","participants":1,"createdAt":1,"rostered":true,"revision":1,"rosterHash":"${GroupCallCodec.rosterHash(roster)}"}""")
    private fun core(scope: kotlinx.coroutines.CoroutineScope, creator: String? = G.alice) = GroupCallSessionCore(
        scope, coordinator, http, fake, fake, sfu, { roster }, G.bob, "primary", creator, listener, G.rng(70),
        attestationSigner = { ByteArray(64) { 1 } },
        onAdvance = { next, previous, _, _ -> GroupCallCodec.newRevision(previous, next, G.NOW, G.rng(90)) },
    )
    private fun welcome(vararg parts: String) = """{"type":"welcome","v":1,"you":"$me","participants":[${parts.joinToString(",")}]}"""
    private fun part(pid: String, key: String, session: String?, device: String = "primary") =
        """{"pid":"$pid","pubkey":"$key","device":"$device","sessionId":${session?.let { "\"$it\"" } ?: "null"},"track":${session?.let { "\"mic\"" } ?: "null"},"muted":false}"""
    private fun joined(pid: String, key: String, session: String) = part(pid, key, session).replace("{", """{"type":"joined",""")
    private fun accept() { coordinator.onWire(G.alice, G.wire(start)); http.live = ::liveOk }

    @Test fun joinConfirmsTheRoomBeforeTheMicrophoneOrAnySubscription() = runBlocking {
        accept(); val c = core(this)
        c.join(start.room, start.joinSecret!!); c.idle()
        assertNull(fake.opened.single().pid)
        fake.opened[0].listener.onText(welcome(part(pidAlice, G.alice, "s-alice"))); c.idle()
        assertEquals("GET", http.calls.first { it.first == "GET" }.first)
        assertEquals("/v1/call/rooms/${start.room}?device=primary", http.calls.first { it.first == "GET" }.second)
        assertEquals(listOf("sessions-new", "tracks-new-local:s-me-1:0", "applyAnswer", "tracks-new-remote:s-alice", "answerRemote:OFFER", "renegotiate:ANS-OFFER"), api.log)
        assertEquals(1, peer.micOpens)
        c.dispose()
    }

    @Test fun roomThatCannotBeConfirmedNeverOpensTheMicrophone() = runBlocking {
        accept(); http.live = { HttpAnswer(404, "{}") }
        val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome(part(pidAlice, G.alice, "s-alice"))); c.idle()
        assertEquals(0, peer.micOpens); assertTrue(api.log.isEmpty())
        assertEquals(SessionEnd.ROOM_GONE, listener.finished?.first)
        assertNull(coordinator.liveBanner())
        // a hash mismatch (the server's room is bound to another roster) is refused the same way
        assertEquals(LiveVerdict.MISMATCH, GroupLiveState.verify(200, """{"v":1,"rostered":true,"revision":1,"rosterHash":"${"0".repeat(64)}"}""", roster, start.room))
        assertEquals(LiveVerdict.MISMATCH, GroupLiveState.verify(200, """{"v":1,"rostered":false}""", roster, start.room))
        assertEquals(LiveVerdict.UNKNOWN, GroupLiveState.verify(0, "", roster, start.room))
        assertEquals(LiveVerdict.GONE, GroupLiveState.verify(404, "{}", roster, start.room))
        assertEquals(LiveVerdict.MISMATCH, GroupLiveState.verify(200, """{"v":1,"rostered":true,"revision":1,"rosterHash":"${GroupCallCodec.rosterHash(roster)}"}""", roster, start.room))
        c.dispose()
    }

    @Test fun joinsAndLeavesDriveSubscribeUnsubscribeAndSessionReplacement() = runBlocking {
        accept(); val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle(); api.log.clear()
        val l = fake.opened[0].listener
        l.onText(joined(pidAlice, G.alice, "s-alice")); c.idle()
        assertEquals(listOf("tracks-new-remote:s-alice", "answerRemote:OFFER", "renegotiate:ANS-OFFER"), api.log); api.log.clear()
        l.onText(joined(pidAlice, G.alice, "s-alice2")); c.idle() // same pid, new session
        assertTrue(api.log.contains("tracks-close:m10")); assertTrue(api.log.contains("tracks-new-remote:s-alice2")); api.log.clear()
        l.onText("""{"type":"left","pid":"$pidAlice","pubkey":"${G.alice}","device":"primary"}"""); c.idle()
        assertEquals(listOf("tracks-close:m11"), api.log)
        c.dispose()
    }

    @Test fun aParticipantOutsideTheRosterIsNeverSubscribedAndIsReported() = runBlocking {
        accept(); val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle(); api.log.clear()
        fake.opened[0].listener.onText(joined(pidMal, G.mallory, "s-mal")); c.idle()
        assertTrue(api.log.none { it.startsWith("tracks-new-remote") }); assertEquals(listOf(pidMal), listener.warned)
        assertEquals(listOf(pidMal), listener.participants.map { it.pid })
        c.dispose()
    }

    @Test fun revokedClose4009TearsEverythingDownWithoutReconnecting() = runBlocking {
        accept(); val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome(part(pidAlice, G.alice, "s-alice"))); c.idle(); api.log.clear()
        fake.opened[0].listener.onClosed(4009, "removed"); c.idle(); fake.advance(120_000)
        assertEquals(SessionEnd.REVOKED, listener.finished?.first); assertEquals(1, fake.opened.size)
        assertEquals(1, peer.released); assertNull(sfu.localMid); assertNull(coordinator.liveBanner())
    }

    @Test fun leaveSendsLeaveReleasesTheMicAndFencesTheRoom() = runBlocking {
        accept(); val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle()
        c.leave(); c.idle()
        assertEquals(RoomFrames.LEAVE, fake.opened[0].sent.last())
        assertEquals(SessionEnd.LEFT, listener.finished?.first); assertEquals(1, peer.released)
        assertNull(coordinator.liveBanner())
    }

    @Test fun freshJoinAfterTheGraceRebuildsTheSfuSession() = runBlocking {
        accept(); val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle()
        fake.opened[0].listener.onClosed(1006, ""); c.idle()
        assertEquals(SessionPhase.RECONNECTING, listener.phases.last())
        fake.advance(31_000); fake.opened.last().listener.onFailure(null, null); fake.advance(60_000)
        val fresh = fake.opened.last(); assertNull(fresh.pid)
        fresh.listener.onText(welcome()); c.idle()
        assertEquals(1, peer.resets); assertEquals(2, peer.micOpens); assertEquals(SessionPhase.CONNECTED, listener.phases.last())
        c.dispose()
    }

    @Test fun muteKeepsTheTrackAndBroadcastsStateAndAStalledSenderIsRepublished() = runBlocking {
        accept(); val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle(); api.log.clear()
        c.mute(true); c.idle()
        assertEquals(false, peer.micEnabled); assertEquals("""{"type":"state","muted":true,"deafened":false}""", fake.opened[0].sent.last())
        assertTrue("no tracks-close on mute", api.log.none { it.startsWith("tracks-close") })
        var now = 0L; var packets = 0L
        repeat(60) { now += 1_000; packets += 3; c.sampleOutbound(now, packets) }; c.idle()
        assertEquals("sparse packets for 60 s muted: nothing republished", 0, peer.resets)
        repeat(25) { now += 1_000; c.sampleOutbound(now, packets) }; c.idle()
        assertEquals("stalled sender is republished once", 1, peer.resets); assertEquals(false, peer.micEnabled == true)
        c.dispose()
    }

    @Test fun deafenIsLocalPlaybackPlusAStateHint() = runBlocking {
        accept(); val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle()
        c.deafen(true); c.idle()
        assertEquals(true, listener.deafened); assertEquals("""{"type":"state","muted":false,"deafened":true}""", fake.opened[0].sent.last())
        c.dispose()
    }

    @Test fun creatorAdvancesTheLiveRevisionAndTheCallContinues() = runBlocking {
        // The group owner (bob) started this call, so it is bob's attestation that advances the room.
        val ownRoster = roster.copy(owner = G.bob)
        val coord = GroupCallCoordinator(ownRoster, G.bob, { G.NOW }, MemoryStore())
        val creatorStart = G.start(G.bob, ownRoster, seed = 3)
        coord.onWire(G.bob, G.wire(creatorStart))
        http.live = { HttpAnswer(200, """{"v":1,"room":"${creatorStart.room}","rostered":true,"revision":1,"rosterHash":"${GroupCallCodec.rosterHash(ownRoster)}"}""") }
        val c = GroupCallSessionCore(this, coord, http, fake, fake, sfu, { ownRoster }, G.bob, "primary", G.bob, listener, G.rng(70),
            attestationSigner = { ByteArray(64) { 1 } },
            onAdvance = { next, previous, _, _ -> GroupCallCodec.newRevision(previous, next, G.NOW, G.rng(90)) })
        c.join(creatorStart.room, creatorStart.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle()
        val next = ownRoster.copy(revision = 2, members = ownRoster.members.filter { it.key != G.carol })
        c.onRosterChanged(next, listOf(G.alice, G.bob, G.carol)); c.idle()
        val post = http.calls.single { it.second.startsWith("/v1/call/rooms/${creatorStart.room}/roster") }
        assertEquals("POST", post.first)
        val body = JSONObject(post.third!!)
        assertEquals(2L, body.getLong("revision"))
        val members = body.getJSONArray("members").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals("the removed member is not in the new set", listOf(G.alice, G.bob).sorted(), members)
        assertEquals(G.bob, body.getString("owner")); assertTrue(body.getString("rosterSig").isNotEmpty())
        assertTrue(body.getString("secretHash").matches(Regex("[0-9a-f]{64}")))
        assertEquals(RosterChangeOutcome(listOf(G.carol), closed = false, successorNeeded = false), c.lastRosterOutcome)
        assertEquals("the call stays live", 0, peer.released)
        assertNotNull("the room is still bound", coord.liveBanner())
        c.dispose(); c.idle()
    }

    @Test fun ifTheRevocationCannotBeDeliveredTheCreatorEndsTheRoomInstead() = runBlocking {
        val creatorStart = G.start(G.bob, roster, seed = 3); coordinator.onWire(G.bob, G.wire(creatorStart))
        http.live = { HttpAnswer(200, """{"v":1,"room":"${creatorStart.room}","rostered":true,"revision":1,"rosterHash":"${GroupCallCodec.rosterHash(roster)}"}""") }
        http.roster = { HttpAnswer(502, """{"v":1,"error":{"code":"upstream_failed","retryable":true}}""") }
        val c = core(this, creator = G.bob); c.join(creatorStart.room, creatorStart.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle()
        c.onRosterChanged(G.roster(2, listOf(G.alice, G.bob)), listOf(G.alice, G.bob, G.carol)); c.idle()
        assertTrue(http.calls.any { it.second.startsWith("/v1/call/rooms/${creatorStart.room}/end") })
        assertEquals(SessionEnd.FAILED, listener.finished?.first)
        assertEquals(1, peer.released)
    }

    @Test fun aNonCreatorWaitsForTheOwnersAdvanceInsteadOfClosingTheCall() = runBlocking {
        accept(); val c = core(this, creator = G.alice); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome()); c.idle()
        val next = G.roster(2, listOf(G.alice, G.bob))
        c.onRosterChanged(next, listOf(G.alice, G.bob, G.carol)); c.idle()
        assertTrue(http.calls.none { it.second.contains("/roster") || it.second.contains("/end") })
        assertEquals(RosterChangeOutcome(emptyList(), closed = false, successorNeeded = false), c.lastRosterOutcome)
        assertEquals("no teardown while waiting", 0, peer.released)
        assertNotNull(coordinator.liveBanner())
        // The owner's signed advance arrives (same room, revision 2): the call is re-bound, not ended.
        val advance = GroupCallCodec.newRevision(start, next, G.NOW, G.rng(91))
        val applied = coordinator.onWire(G.alice, G.wire(advance))
        assertTrue(applied is IngressResult.Accepted)
        assertTrue((applied as IngressResult.Accepted).effects.any { it is com.relay.app.call.group.GroupCallEffect.RebindRoom })
        assertEquals(next.revision, coordinator.roster().revision)
        c.dispose(); c.idle()
    }

    @Test fun disposeMidNegotiationReleasesEverything() = runBlocking {
        accept(); val c = core(this); c.join(start.room, start.joinSecret!!); c.idle()
        fake.opened[0].listener.onText(welcome(part(pidAlice, G.alice, "s-alice"))); c.idle()
        c.dispose(); c.idle(); c.dispose()
        assertEquals(1, peer.released); assertTrue(fake.opened[0].closed != null)
    }
}
