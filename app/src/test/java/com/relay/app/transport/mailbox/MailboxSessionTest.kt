package com.relay.app.transport.mailbox

import com.relay.app.transport.mailbox.MailboxSession.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxSessionTest {

    private fun m(seq: Long) = MailboxMessage(seq, "wrap-$seq")
    private fun acks(actions: List<Action>) = actions.filterIsInstance<Action.Ack>().map { it.through }

    @Test
    fun startsByCatchingUpFromTheBeginning() {
        assertEquals(listOf(Action.Sync(after = 0)), MailboxSession().onReady())
    }

    @Test
    fun pushBeforeTheCatchUpCompletesIsStoredButNotAcknowledged() {
        val s = MailboxSession()
        s.onReady()
        // Message 9 was stored after this socket connected; 5..8 are still on their way in the batch.
        val push = s.onPush(m(9))
        assertEquals(listOf(Action.Store(listOf(m(9)))), push)
        assertTrue("acknowledging 9 now would delete 5..8 unread", acks(push).isEmpty())

        val batch = s.onBatch(listOf(m(5), m(6), m(7), m(8), m(9)), more = false, cursor = 9)
        assertEquals(Action.Store(listOf(m(5), m(6), m(7), m(8), m(9))), batch.first())
        assertEquals(listOf(9L), acks(batch))
    }

    @Test
    fun pagesThroughTheCatchUpBeforeAcknowledging() {
        val s = MailboxSession()
        s.onReady()
        val first = s.onBatch(listOf(m(1), m(2)), more = true, cursor = 2)
        assertEquals(listOf(Action.Store(listOf(m(1), m(2))), Action.Sync(after = 2)), first)
        val second = s.onBatch(listOf(m(3)), more = false, cursor = 3)
        assertEquals(listOf(Action.Store(listOf(m(3))), Action.Ack(through = 3)), second)
    }

    @Test
    fun acknowledgesEachPushOnceCaughtUp() {
        val s = MailboxSession()
        s.onReady()
        s.onBatch(emptyList(), more = false, cursor = 0)
        assertEquals(listOf(Action.Store(listOf(m(12))), Action.Ack(through = 12)), s.onPush(m(12)))
        assertEquals(listOf(13L), acks(s.onPush(m(13))))
    }

    @Test
    fun anEmptyCatchUpSendsNoReceipt() {
        val s = MailboxSession()
        s.onReady()
        assertEquals(emptyList<Action>(), s.onBatch(emptyList(), more = false, cursor = 0))
    }

    @Test
    fun neverAcknowledgesTheSameRangeTwice() {
        val s = MailboxSession()
        s.onReady()
        s.onBatch(listOf(m(4)), more = false, cursor = 4)
        // A late duplicate of an already-acknowledged message is stored again (and deduplicated by
        // the store), but needs no second receipt.
        assertTrue(acks(s.onPush(m(4))).isEmpty())
    }

    @Test
    fun theStoreAlwaysComesBeforeTheReceiptThatCoversIt() {
        val s = MailboxSession()
        s.onReady()
        s.onBatch(emptyList(), more = false, cursor = 0)
        val actions = s.onPush(m(20))
        assertTrue(actions.indexOfFirst { it is Action.Store } < actions.indexOfFirst { it is Action.Ack })
    }
}
