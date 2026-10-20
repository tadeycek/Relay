package com.relay.app.groups

import com.relay.app.messaging.ReadReceiptBody
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupReadProtocolTest {
    private val owner = "a".repeat(64)
    private val reader = "b".repeat(64)
    private val stranger = "c".repeat(64)
    private val state = GroupProtocol.State(UUID.randomUUID().toString(), owner, "Friends", 1,
        listOf(GroupProtocol.Member(owner, "Owner"), GroupProtocol.Member(reader, "Reader")))

    @Test fun receiptsRoundTripAndRequireTheAuthenticatedCurrentMember() {
        val ids = List(3) { UUID.randomUUID().toString() }
        val packet = GroupProtocol.Event("read", GroupProtocol.ref(state), UUID.randomUUID().toString(), reader,
            ReadReceiptBody.encodeIds(ids), 12345)
        assertEquals(packet, GroupProtocol.parse(GroupProtocol.format(packet)))
        assertEquals(GroupProtocol.Verdict.ACCEPT, GroupProtocol.checkEvent(reader, state, packet))
        assertEquals(GroupProtocol.Verdict.REJECT, GroupProtocol.checkEvent(stranger, state, packet))
        val removed = state.copy(revision = 2, members = state.members.filterNot { it.key == reader })
        assertEquals(GroupProtocol.Verdict.REJECT, GroupProtocol.checkEvent(reader, removed, packet))
    }

    @Test fun malformedReceiptIdsAreNotValidGroupPackets() {
        val packet = GroupProtocol.Event("read", GroupProtocol.ref(state), UUID.randomUUID().toString(), reader, "bad", 12345)
        assertTrue(runCatching { GroupProtocol.format(packet) }.isFailure)
    }
}
