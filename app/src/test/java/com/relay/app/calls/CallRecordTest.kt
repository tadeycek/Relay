package com.relay.app.calls

import com.relay.app.call.CallOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local call event that appears in the thread (WP13: "a missed-call record in the thread"). */
class CallRecordTest {

    private val call = "0123456789abcdef0123456789abcdef"

    @Test fun aMissedIncomingCallRoundTripsAndReadsAsMissed() {
        val body = CallRecord.encode(call, incoming = true, outcome = CallOutcome.MISSED, reason = null, durationMs = 0, atMs = 1_800_000_000_000)
        assertTrue(CallRecord.isCallLog(body))
        val entry = CallRecord.decode(body)!!
        assertEquals(call, entry.callId)
        assertTrue(entry.incoming)
        assertEquals(CallOutcome.MISSED, entry.outcome)
        assertEquals(1_800_000_000_000, entry.atMs)
        assertEquals("Missed call", CallRecord.label(entry, "Alice"))
    }

    @Test fun everyRecordedOutcomeHasItsOwnSentenceWithTheRightDirection() {
        fun label(outcome: CallOutcome, incoming: Boolean, durationMs: Long = 0) = CallRecord.label(
            CallRecord.decode(CallRecord.encode(call, incoming, outcome, null, durationMs, 1))!!, "Alice",
        )
        assertEquals("Missed call", label(CallOutcome.MISSED, incoming = true))
        assertEquals("Missed call", label(CallOutcome.MISSED, incoming = false))
        assertEquals("Alice declined your call", label(CallOutcome.DECLINED, incoming = false))
        assertEquals("You declined the call", label(CallOutcome.DECLINED, incoming = true))
        assertEquals("Alice was busy", label(CallOutcome.BUSY, incoming = false))
        assertEquals("No answer", label(CallOutcome.NO_ANSWER, incoming = false))
        assertEquals("Call cancelled", label(CallOutcome.CANCELLED, incoming = false))
        assertEquals("Call ended", label(CallOutcome.COMPLETED, incoming = false))
        assertEquals("Call ended · 1:05", label(CallOutcome.COMPLETED, incoming = false, durationMs = 65_000))
        assertEquals("Call failed", label(CallOutcome.FAILED, incoming = false))
    }

    @Test fun aRecordSurvivesAMalformedFieldAndIsNeverMistakenForACallSignal() {
        assertFalse(CallRecord.isCallLog("TYPE:CALL|J:eyJ2IjoxfQ=="))
        assertFalse(CallRecord.isCallLog("hello"))
        assertNull(CallRecord.decode("TYPE:CALLLOG|J:not-base64"))
        assertNull(CallRecord.decode("TYPE:CALLLOG|J:" + java.util.Base64.getEncoder().encodeToString("""{"v":2}""".toByteArray())))
        assertNull(CallRecord.decode("TYPE:CALLLOG|J:" + java.util.Base64.getEncoder().encodeToString("""{"v":1,"call":"nope"}""".toByteArray())))
    }

    @Test fun theMessageIdIsOnePerCallSoARecordIsWrittenOnce() {
        assertEquals("call:$call", CallRecord.messageId(call))
        // Same id for every attempt at the same call: the insert guard is hasMsgIdSync.
        assertEquals(CallRecord.messageId(call), CallRecord.messageId(call))
    }
}
