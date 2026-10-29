package com.relay.app.calls.group

import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupParticipant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GroupCallModelTest {
    private fun p(pid: String, key: String, device: String = "primary", session: String? = "s-$pid", muted: Boolean = false) =
        GroupParticipant(pid.padEnd(16, '0'), key, device, session, session?.let { "mic" }, muted)
    private val roster = G.roster()
    private fun model(parts: List<GroupParticipant>, speaking: Set<String> = emptySet(), volumes: Map<String, Float> = emptyMap(), creator: String? = G.bob,
        deafMap: Map<String, Boolean> = emptyMap()) =
        GroupCallUiModelFactory.build(roster, G.bob, "9".repeat(16), "primary", parts, deafMap, speaking, volumes, false, false, creator)

    @Test fun securityLabelIsAlwaysTheHonestOne() {
        assertEquals("Group calls are encrypted in transit, not end to end.", model(emptyList()).securityLabel)
    }

    @Test fun deviceRowsAreGroupedPerPersonAndSelfComesFirst() {
        val m = model(listOf(p("a1", G.carol), p("a2", G.alice), p("a3", G.alice, device = "desk")))
        assertEquals(G.bob, m.people.first().pubkey); assertTrue(m.people.first().isSelf)
        val alice = m.people.single { it.pubkey == G.alice }
        assertEquals(2, alice.devices.size); assertEquals(setOf("primary", "desk"), alice.devices.map { it.device }.toSet())
        assertEquals(3, m.people.size)
    }

    @Test fun aParticipantOutsideTheLocalRosterIsFlaggedNotInThisGroup() {
        val m = model(listOf(p("m1", G.mallory), p("a1", G.alice)))
        val row = m.people.single { it.pubkey == G.mallory }
        assertFalse(row.inGroup); assertEquals("Not in this group", row.warning)
        assertTrue(m.people.single { it.pubkey == G.alice }.inGroup); assertNull(m.people.single { it.pubkey == G.alice }.warning)
    }

    @Test fun speakingIsPerDeviceAndRollsUpToThePerson() {
        val a1 = p("a1", G.alice); val a2 = p("a2", G.alice, "desk")
        val m = model(listOf(a1, a2), speaking = setOf(a2.pid))
        val alice = m.people.single { it.pubkey == G.alice }
        assertTrue(alice.speaking); assertEquals(listOf(false, true), alice.devices.sortedBy { it.pid }.map { it.speaking })
    }

    @Test fun volumeIsPerPersonWithUnityDefaultAndMuteDeafenAreShown() {
        val m = model(listOf(p("a1", G.alice, muted = true)), volumes = mapOf(G.alice to 0.4f), deafMap = mapOf("a1".padEnd(16, '0') to true))
        val alice = m.people.single { it.pubkey == G.alice }
        assertEquals(0.4f, alice.volume); assertTrue(alice.devices.single().muted); assertTrue(alice.devices.single().deafened)
        assertEquals(1f, model(listOf(p("c1", G.carol))).people.single { it.pubkey == G.carol }.volume)
    }

    @Test fun onlyTheCreatorMayEnd() {
        assertTrue(model(emptyList(), creator = G.bob).canEnd); assertFalse(model(emptyList(), creator = G.alice).canEnd)
    }

    @Test fun speakingHasAThresholdAndAHangover() {
        val t = SpeakingTracker(threshold = 0.02, hangoverMs = 300)
        t.update("p", 0.001, 0); assertTrue(t.speaking(10).isEmpty())
        t.update("p", 0.2, 100); assertEquals(setOf("p"), t.speaking(100))
        t.update("p", 0.0, 200); assertEquals("still inside the 300 ms hangover", setOf("p"), t.speaking(350))
        assertTrue(t.speaking(401).isEmpty())
        t.update("q", 0.5, 500); t.retainOnly(emptySet()); assertTrue(t.speaking(500).isEmpty())
    }

    @Test fun watchdogRepublishesOnlyWhenPacketsStallBeforeTheSfuExpiry() {
        val w = PacketFlowWatchdog(stallMs = 20_000)
        var packets = 0L; var now = 0L
        // Muted for 60 s with sparse DTX/comfort-noise packets: never a republish.
        while (now < 60_000) { now += 1_000; packets += 3; assertEquals(FlowDecision.OK, w.onSample(now, packets)) }
        // Now the packets stop.
        val stalledAt = now
        var decision = FlowDecision.OK
        while (decision == FlowDecision.OK && now < stalledAt + 40_000) { now += 1_000; decision = w.onSample(now, packets) }
        assertEquals(FlowDecision.REPUBLISH, decision)
        assertTrue("republish must come before the SFU's 30 s no-packet expiry", now - stalledAt in 19_000..25_000)
        w.reset(now)
        assertEquals(FlowDecision.OK, w.onSample(now + 5_000, 0)) // a new peer restarts the counter
    }

    @Test fun guardRefusesHonestlyWhenUnlinkedOfflineOrWithoutAKey() {
        val r = roster
        fun block(linked: Boolean = true, online: Boolean = true, mic: Boolean = true, busy: Boolean = false, enabled: Boolean = true, roster: com.relay.app.groups.GroupProtocol.State? = r, reachable: Int = 2) =
            GroupCallGuard.startBlock(linked, online, mic, busy, enabled, roster, G.bob, reachable)
        assertEquals(GroupStartBlock.NONE, block())
        assertEquals(GroupStartBlock.PROFILE_LOCKED, block(enabled = false))
        assertEquals(GroupStartBlock.UNLINKED, block(linked = false))
        assertEquals(GroupStartBlock.BUSY, block(busy = true))
        assertEquals(GroupStartBlock.OFFLINE, block(online = false))
        assertEquals(GroupStartBlock.MIC_DENIED, block(mic = false))
        assertEquals(GroupStartBlock.NOT_IN_GROUP, block(roster = null))
        assertEquals(GroupStartBlock.NOT_IN_GROUP, block(roster = G.roster(members = listOf(G.alice, G.carol))))
        assertEquals("no member can be reached end to end", GroupStartBlock.NO_KEY, block(reachable = 0))
        for (b in GroupStartBlock.values().filter { it != GroupStartBlock.NONE }) assertNotNull(b.name, GroupCallGuard.message(b))
        assertNull(GroupCallGuard.message(GroupStartBlock.NONE))
        assertEquals(GroupStartBlock.UNLINKED, GroupCallGuard.joinBlock(false, true, true, false, true))
    }

    @Test fun removalPlanListsOnlyDroppedMembersAndNeverTheCreator() {
        val frozen = listOf(G.alice, G.bob, G.carol)
        assertEquals(listOf(G.carol), GroupRosterChange.removed(frozen, G.roster(2, listOf(G.alice, G.bob)), G.bob))
        assertEquals(emptyList<String>(), GroupRosterChange.removed(frozen, G.roster(2, listOf(G.alice, G.bob, G.carol, G.mallory)), G.bob))
        assertNull("the creator itself left the group", GroupRosterChange.removed(frozen, G.roster(2, listOf(G.alice, G.carol)), G.bob))
        assertEquals(listOf(G.alice, G.carol), GroupRosterChange.removed(listOf(G.carol, G.alice, G.bob), G.roster(2, listOf(G.bob)), G.bob))
    }

    @Test fun revocationBodyIsSortedUniqueWithAFreshSecretHash() {
        val r = GroupRosterChange.revocation(listOf(G.carol, G.alice, G.carol), G.rng(60))
        val json = JSONObject(r.body)
        assertEquals(listOf(G.alice, G.carol), (0 until json.getJSONArray("remove").length()).map { json.getJSONArray("remove").getString(it) })
        assertTrue(r.secret.matches(Regex("[0-9a-f]{64}")))
        assertEquals(GroupCallCodec.secretHash(r.secret), json.getString("secretHash"))
        assertFalse(r.body.contains(r.secret)); assertEquals(1, json.getInt("v"))
    }
}
