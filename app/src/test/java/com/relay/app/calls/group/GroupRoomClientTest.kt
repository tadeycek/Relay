package com.relay.app.calls.group

import com.relay.app.call.group.GroupParticipant
import org.junit.Assert.*
import org.junit.Test

/** WP16 step 2 (joining): the NIP-98 room socket, pid reconnect inside the grace, 4009 = never auto-reconnect. */
class GroupRoomClientTest {
    private val room = "a".repeat(32); private val secret = "b".repeat(64)
    private val me = "0123456789abcdef"; private val pidBob = "1111111111111111"; private val pidCarol = "2222222222222222"

    class Sock(val pid: String?, val listener: RoomSocketListener) : RoomSocketHandle {
        val sent = mutableListOf<String>(); var closed: Pair<Int, String>? = null
        override fun send(text: String): Boolean { sent += text; return closed == null }
        override fun close(code: Int, reason: String) { closed = code to reason }
    }
    class Fake : RoomSocketPort, RoomTimers, RoomClientListener {
        var t = 1_000_000L
        val opened = mutableListOf<Sock>()
        private class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()
        val welcomes = mutableListOf<Pair<String, Boolean>>(); val terminals = mutableListOf<RoomTerminal>(); var reconnecting = 0
        var participants: List<GroupParticipant> = emptyList(); var deafened: Map<String, Boolean> = emptyMap()
        override fun open(room: String, secret: String, pid: String?, listener: RoomSocketListener) = Sock(pid, listener).also { opened += it }
        override fun now() = t
        override fun after(delayMs: Long, task: () -> Unit): () -> Unit { val x = Task(t + delayMs, task); tasks += x; return { x.cancelled = true } }
        fun advance(ms: Long) { val end = t + ms
            while (true) { val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(next); t = maxOf(t, next.at); next.run() }
            t = end }
        override fun onWelcome(pid: String, fresh: Boolean) { welcomes += pid to fresh }
        override fun onParticipants(participants: List<GroupParticipant>, deafened: Map<String, Boolean>) { this.participants = participants; this.deafened = deafened }
        override fun onReconnecting(attempt: Int) { reconnecting = attempt }
        override fun onTerminal(reason: RoomTerminal, by: String?) { terminals += reason }
    }

    private fun welcome(vararg who: String) = """{"type":"welcome","v":1,"you":"$me","participants":[${who.joinToString(",") { """{"pid":"$it","pubkey":"${G.bob}","device":"primary","sessionId":null,"track":null,"muted":false}""" }}]}"""
    private fun fixture(): Pair<Fake, RoomClient> { val f = Fake(); return f to RoomClient(f, f, f) }

    @Test fun firstJoinHasNoPidAndWelcomeStoresOwnPidAndRoster() {
        val (f, c) = fixture(); c.join(room, secret)
        assertNull(f.opened.single().pid)
        f.opened[0].listener.onOpen(); f.opened[0].listener.onText(welcome(pidBob))
        assertEquals(me, c.pid); assertEquals(listOf(me to false), f.welcomes)
        assertEquals(listOf(pidBob), f.participants.map { it.pid })
    }

    @Test fun joinedLeftAndStateFramesDriveTheParticipantSnapshot() {
        val (f, c) = fixture(); c.join(room, secret); val l = f.opened[0].listener
        l.onText(welcome(pidBob))
        l.onText("""{"type":"joined","pid":"$pidCarol","pubkey":"${G.carol}","device":"primary","sessionId":"sess-c","track":"mic","muted":false}""")
        assertEquals(setOf(pidBob, pidCarol), f.participants.map { it.pid }.toSet())
        l.onText("""{"type":"joined","pid":"$pidCarol","pubkey":"${G.carol}","device":"primary","sessionId":"sess-c2","track":"mic","muted":false}""")
        assertEquals("sess-c2", f.participants.single { it.pid == pidCarol }.sessionId)
        l.onText("""{"type":"state","pid":"$pidCarol","muted":true,"deafened":true}""")
        assertTrue(f.participants.single { it.pid == pidCarol }.muted); assertEquals(true, f.deafened[pidCarol])
        l.onText("""{"type":"left","pid":"$pidBob","pubkey":"${G.bob}","device":"primary"}""")
        assertEquals(listOf(pidCarol), f.participants.map { it.pid })
    }

    @Test fun droppedSocketReconnectsWithTheSamePidInsideTheGraceAndRefreshesTheRoster() {
        val (f, c) = fixture(); c.join(room, secret); f.opened[0].listener.onText(welcome(pidBob))
        f.opened[0].listener.onClosed(1006, "")
        assertTrue(f.terminals.isEmpty()); assertEquals(1, f.reconnecting)
        f.advance(2_000)
        assertEquals(2, f.opened.size); assertEquals(me, f.opened[1].pid)
        f.opened[1].listener.onText(welcome(pidBob, pidCarol))
        assertEquals(listOf(me to false, me to false), f.welcomes)
        assertEquals(2, f.participants.size)
    }

    @Test fun close4009MeansRevokedAndNeverReconnects() {
        val (f, c) = fixture(); c.join(room, secret); f.opened[0].listener.onText(welcome())
        f.opened[0].listener.onClosed(4009, "removed")
        f.advance(120_000)
        assertEquals(listOf(RoomTerminal.REVOKED), f.terminals); assertEquals(1, f.opened.size)
    }

    @Test fun httpRefusalsAreTerminalAndSpecific() {
        val cases = listOf(Triple(403, "not_in_roster", RoomTerminal.NOT_IN_ROSTER), Triple(404, "not_found", RoomTerminal.NOT_FOUND),
            Triple(403, "forbidden", RoomTerminal.WRONG_SECRET), Triple(409, "room_full", RoomTerminal.ROOM_FULL), Triple(401, "auth_revoked", RoomTerminal.REVOKED))
        for ((status, code, expected) in cases) {
            val (f, c) = fixture(); c.join(room, secret)
            f.opened[0].listener.onFailure(status, code); f.advance(60_000)
            assertEquals("$status $code", listOf(expected), f.terminals); assertEquals(1, f.opened.size)
        }
    }

    @Test fun transportFailuresRetryUntilTheGraceThenAFreshJoinWithoutPidAndNotFoundEndsIt() {
        val (f, c) = fixture(); c.join(room, secret); f.opened[0].listener.onText(welcome())
        f.opened[0].listener.onClosed(1006, "")
        repeat(40) { f.opened.last().listener.onFailure(null, null); f.advance(1_000) }
        assertTrue("a fresh join without pid was attempted after the 30 s grace", f.opened.any { it.pid == null && it !== f.opened[0] })
        f.opened.last().listener.onFailure(404, "not_found")
        assertEquals(listOf(RoomTerminal.NOT_FOUND), f.terminals)
    }

    @Test fun leaveSendsLeaveFrameAndSettlesAsLeftWithoutReconnect() {
        val (f, c) = fixture(); c.join(room, secret); val s = f.opened[0]; s.listener.onText(welcome())
        c.leave()
        assertEquals(RoomFrames.LEAVE, s.sent.last()); assertEquals(listOf(RoomTerminal.LEFT), f.terminals)
        s.listener.onClosed(1000, "left"); f.advance(60_000)
        assertEquals(1, f.opened.size); assertEquals(listOf(RoomTerminal.LEFT), f.terminals)
    }

    @Test fun endFrameFromTheServerIsTerminalAndAServerSideNormalCloseIsNotReconnected() {
        val (f, c) = fixture(); c.join(room, secret); f.opened[0].listener.onText(welcome())
        f.opened[0].listener.onText("""{"type":"end","by":"${G.alice}","reason":"superseded"}""")
        f.opened[0].listener.onClosed(1000, "ended"); f.advance(60_000)
        assertEquals(listOf(RoomTerminal.ENDED), f.terminals); assertEquals(1, f.opened.size)
    }

    @Test fun framesFromASupersededSocketAreIgnored() {
        val (f, c) = fixture(); c.join(room, secret); f.opened[0].listener.onText(welcome(pidBob))
        f.opened[0].listener.onClosed(1006, ""); f.advance(2_000)
        f.opened[1].listener.onText(welcome(pidCarol))
        f.opened[0].listener.onText("""{"type":"left","pid":"$pidCarol"}""")
        f.opened[0].listener.onClosed(4009, "removed")
        assertEquals(listOf(pidCarol), f.participants.map { it.pid }); assertTrue(f.terminals.isEmpty())
    }

    @Test fun garbageFramesAreIgnoredAndTheSocketStaysUsable() {
        val (f, c) = fixture(); c.join(room, secret); val l = f.opened[0].listener; l.onText(welcome(pidBob))
        for (junk in listOf("", "not json", "[]", """{"type":5}""", """{"type":"joined"}""", """{"type":"state","pid":"zz"}""", "x".repeat(70_000))) l.onText(junk)
        assertEquals(listOf(pidBob), f.participants.map { it.pid }); assertTrue(f.terminals.isEmpty())
    }

    @Test fun stateFrameCarriesMuteAndDeafenAndHeartbeatsRunWhileJoinedOnly() {
        val (f, c) = fixture(); c.join(room, secret); val s = f.opened[0]; s.listener.onOpen(); s.listener.onText(welcome())
        c.sendState(true, false); assertEquals("""{"type":"state","muted":true,"deafened":false}""", s.sent.last())
        f.advance(35_000); assertTrue(s.sent.count { it == RoomFrames.HEARTBEAT } >= 3)
        val beats = s.sent.size; c.leave(); f.advance(60_000); assertEquals(beats + 1, s.sent.size) // only the leave frame
    }
}
