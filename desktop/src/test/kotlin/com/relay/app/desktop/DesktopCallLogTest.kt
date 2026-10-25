package com.relay.app.desktop

import com.relay.app.call.CallOutcome
import com.relay.app.transport.RelayPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Call-log rows are written only by the local call host, in their own table; remote text cannot forge one. */
class DesktopCallLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val peer = "b".repeat(64)
    private val callId = "0123456789abcdef0123456789abcdef"

    private fun store(): DesktopMessageStore {
        val paths = DesktopPaths(tmp.newFolder("cfg").toPath(), tmp.newFolder("data").toPath())
        return DesktopMessageStore(paths.databaseFile, FileSecretStore(paths.secretFile))
    }
    private fun record(id: String = callId, outcome: CallOutcome = CallOutcome.MISSED, at: Long = 5_000L) =
        DesktopCallRecord(id, peer, incoming = true, outcome = outcome, reason = null, durationMs = 0, atMs = at)

    @Test fun recordIsStoredOncePerCallIdAndCreatesTheConversationWithoutRetitling() {
        val s = store()
        s.ensureConversation(peer, "Bea")
        assertTrue(s.recordCall(record()))
        assertFalse("same call id twice", s.recordCall(record(outcome = CallOutcome.COMPLETED)))
        assertEquals(listOf(CallOutcome.MISSED), s.callRecords(peer).map { it.outcome })
        assertEquals("Bea", s.conversation(peer)!!.title)
    }

    @Test fun recordForAPeerWithoutAConversationCreatesOne() {
        val s = store()
        assertTrue(s.recordCall(record()))
        assertTrue(s.conversation(peer) != null)
    }

    @Test fun malformedCallIdOrReasonIsRefused() {
        val s = store()
        assertFalse(runCatching { s.recordCall(record(id = "short")) }.getOrDefault(false))
        assertFalse(runCatching { s.recordCall(record().copy(reason = "Bad Reason!")) }.getOrDefault(false))
        assertEquals(0, s.callRecords(peer).size)
    }

    @Test fun aRemoteMessageWithCallLogTextOrACallIdIsNeverACallRow() {
        val s = store()
        // A peer sends text that looks exactly like a local call log, even reusing the local row id scheme.
        s.insertMessage(DesktopMessage("call:$callId", peer, peer, "TYPE:CALLLOG|J:eyJ2IjoxfQ==", 4_000L, false))
        val rows = DesktopUi.buildThread(s.messages(peer), "a".repeat(64), { "Bea" }, { null }, 10_000L, calls = s.callRecords(peer), contactName = "Bea")
        assertTrue(rows.none { it.callLog })
        // The genuine local record with the same call id is still stored independently and rendered as a call row.
        assertTrue(s.recordCall(record()))
        val after = DesktopUi.buildThread(s.messages(peer), "a".repeat(64), { "Bea" }, { null }, 10_000L, calls = s.callRecords(peer), contactName = "Bea")
        assertEquals(1, after.count { it.callLog })
        assertEquals("Missed call", after.single { it.callLog }.text.substringBefore(" ·"))
    }

    @Test fun callRowsInterleaveByTimeAndDoNotBreakSenderGrouping() {
        val s = store()
        s.insertMessage(DesktopMessage("m1", peer, peer, "before", 1_000L, false))
        s.recordCall(record(at = 2_000L))
        s.insertMessage(DesktopMessage("m2", peer, peer, "after", 3_000L, false))
        val rows = DesktopUi.buildThread(s.messages(peer), "a".repeat(64), { "Bea" }, { null }, 10_000L, calls = s.callRecords(peer), contactName = "Bea")
        assertEquals(listOf(false, true, false), rows.map { it.callLog })
        assertTrue("a message after a call row starts with a header", rows[2].showHeader)
    }

    @Test fun labelsAreHonestPerOutcome() {
        fun l(o: CallOutcome, incoming: Boolean, dur: Long = 0) = DesktopCallLog.label(DesktopCallRecord(callId, peer, incoming, o, null, dur, 0), "Bea")
        assertEquals("Missed call", l(CallOutcome.MISSED, true))
        assertEquals("Bea declined your call", l(CallOutcome.DECLINED, false))
        assertEquals("You declined the call", l(CallOutcome.DECLINED, true))
        assertEquals("No answer", l(CallOutcome.NO_ANSWER, false))
        assertEquals("Call failed", l(CallOutcome.FAILED, false))
        assertEquals("Call ended · 1:05", l(CallOutcome.COMPLETED, false, 65_000))
        assertEquals("Call ended", l(CallOutcome.COMPLETED, false, 0))
    }

    @Test fun aMessageWithTheOldCallLogRowIdCannotCollideWithACallRow() {
        val s = store()
        // DELIVERY_ID allows "call-log-" + 32 hex, so a contact can pick exactly the id the call row used to have.
        s.insertMessage(DesktopMessage("call-log-$callId", peer, peer, "hi", 1_000L, false))
        assertTrue(s.recordCall(record(at = 2_000L)))
        val rows = DesktopUi.buildThread(s.messages(peer), "a".repeat(64), { "Bea" }, { null }, 10_000L, calls = s.callRecords(peer), contactName = "Bea")
        assertEquals(2, rows.size)
        assertEquals("row ids must be unique: ${rows.map { it.id }}", rows.size, rows.map { it.id }.toSet().size)
        assertTrue(rows.single { it.callLog }.id.contains(':'))
    }

    @Test fun buildThreadKeepsRowKeysUniqueEvenForRepeatedMessageIds() {
        val msgs = listOf(
            DesktopMessage("dup", peer, peer, "one", 1_000L, false),
            DesktopMessage("dup", peer, peer, "two", 2_000L, false),
        )
        val rows = DesktopUi.buildThread(msgs, "a".repeat(64), { "Bea" }, { null }, 10_000L)
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.id }.toSet().size)
    }
}
