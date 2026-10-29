package com.relay.app.calls.group

import com.relay.app.call.group.GroupCallFenceSnapshot
import org.junit.Assert.*
import org.junit.Test

class GroupCallSnapshotCodecTest {
    @Test fun replayAndEndedRoomSurviveAnEncodingRoundTrip() {
        val snapshot = GroupCallFenceSnapshot(
            mapOf(("a".repeat(32) to G.bob) to G.NOW + 10_000),
            mapOf("b".repeat(32) to G.NOW + 20_000),
            "a".repeat(32) to G.bob,
        )
        assertEquals(snapshot, GroupCallSnapshotCodec.decode(GroupCallSnapshotCodec.encode(snapshot)))
    }

    @Test fun corruptOrOversizedSnapshotIsRejectedNotTreatedAsEmpty() {
        for (bad in listOf("", "{}", "{", "x".repeat(100_000), """{"v":2,"ended":[],"replayed":[],"active":null}""",
            """{"v":1,"ended":[],"replayed":[],"active":null,"joinSecret":"private"}""")) {
            assertThrows(IllegalArgumentException::class.java) { GroupCallSnapshotCodec.decode(bad) }
        }
    }
}
