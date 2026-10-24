package com.relay.app.messaging

import com.relay.app.util.SmsMessageParser
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ReadReceiptBodyTest {
    @Test fun exactIdsRoundTripAndKeepTheLegacyTimestamp() {
        val ids = List(3) { UUID.randomUUID().toString() }
        val wire = ReadReceiptBody.format(1234L, ids)
        assertEquals(1234L, SmsMessageParser.parseReadReceipt(wire))
        assertEquals(Classified.ReadReceipt(1234L, ids), IncomingClassifier.classify(wire))
    }

    @Test fun malformedExactIdsNeverFallBackToAWatermark() {
        val prefix = SmsMessageParser.formatReadReceipt(1234L) + "|IDS:"
        assertEquals(Classified.Ignore, IncomingClassifier.classify(prefix + "bad"))
        assertEquals(Classified.Ignore, IncomingClassifier.classify(prefix))
    }

    @Test fun batchesAreBoundedCanonicalAndUnique() {
        val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        assertNull(ReadReceiptBody.parseIds("$id,$id"))
        assertNull(ReadReceiptBody.parseIds(id.uppercase()))
        assertNull(ReadReceiptBody.parseIds(List(ReadReceiptBody.MAX_IDS + 1) { UUID.randomUUID().toString() }.joinToString(",")))
        val maximum = List(ReadReceiptBody.MAX_IDS) { UUID.randomUUID().toString() }
        assertEquals(maximum, ReadReceiptBody.parseIds(ReadReceiptBody.encodeIds(maximum)))
    }

    @Test fun oldReceiptsRemainReadable() {
        assertEquals(Classified.ReadReceipt(1234L), IncomingClassifier.classify(SmsMessageParser.formatReadReceipt(1234L)))
    }
}
