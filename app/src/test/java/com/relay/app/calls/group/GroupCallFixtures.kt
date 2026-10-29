package com.relay.app.calls.group

import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallFenceSnapshot
import com.relay.app.groups.GroupProtocol

object G {
    fun key(c: Char) = c.toString().repeat(64)
    val alice = key('a'); val bob = key('b'); val carol = key('c'); val mallory = key('d')
    const val GROUP = "11111111-2222-3333-4444-555555555555"
    const val NOW = 1_800_000_000_000L
    fun roster(revision: Long = 1, members: List<String> = listOf(alice, bob, carol)) =
        GroupProtocol.State(GROUP, alice, "Crew", revision, members.map { GroupProtocol.Member(it, "n-" + it.take(2)) })
    /** Deterministic bytes for tests only; production passes SecureRandom. */
    fun rng(seed: Int = 1): (Int) -> ByteArray { var n = seed; return { size -> ByteArray(size) { (n++ and 0xff).toByte() } } }
    fun start(caller: String = bob, roster: GroupProtocol.State = roster(), now: Long = NOW, seed: Int = 1): GroupAnnouncement =
        GroupCallCodec.newStart(roster, caller, "primary", now, rng(seed))
    fun wire(a: GroupAnnouncement) = GroupCallWire.wrap(GroupCallCodec.encode(a))
}

class MemoryStore(var snapshot: GroupCallFenceSnapshot? = null) : GroupCallStore {
    var failNext = false
    val commits = mutableListOf<GroupCallFenceSnapshot>()
    override fun load() = snapshot
    override fun commit(snapshot: GroupCallFenceSnapshot) {
        if (failNext) { failNext = false; throw java.io.IOException("disk full") }
        commits += snapshot; this.snapshot = snapshot
    }
}
