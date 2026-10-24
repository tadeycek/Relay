package com.relay.app.transport.mailbox

/** One waiting message as the mailbox hands it over: still the sender's opaque gift wrap. */
data class MailboxMessage(val seq: Long, val envelope: String)

/**
 * Receipt rules for one socket connection. Pure, so unit-tested.
 *
 * A receipt is cumulative: "through 41" deletes every waiting message up to 41 on the server. Right
 * after connecting, a live push for 41 can arrive before the catch-up batch that still carries older
 * messages (stored while this phone was offline); acknowledging the push then would delete those
 * unread. So nothing is acknowledged until the catch-up has completed, and a receipt only ever covers
 * messages this phone has already stored durably. After the catch-up, pushes arrive in order, so each
 * one can be acknowledged as soon as it is stored.
 *
 * The caller performs the returned actions in order and stops at the first failed [Action.Store]
 * (then drops the connection), so a receipt is never sent for something that wasn't stored.
 */
class MailboxSession {

    sealed interface Action {
        data class Store(val messages: List<MailboxMessage>) : Action
        data class Sync(val after: Long) : Action
        data class Ack(val through: Long) : Action
    }

    private var caughtUp = false
    private var highestStored = 0L
    private var acknowledged = 0L

    /** The server's greeting: start catching up from the beginning (the server only holds what is unacknowledged). */
    fun onReady(): List<Action> = listOf(Action.Sync(after = 0))

    fun onBatch(messages: List<MailboxMessage>, more: Boolean, cursor: Long): List<Action> = buildList {
        if (messages.isNotEmpty()) {
            add(Action.Store(messages))
            highestStored = maxOf(highestStored, messages.maxOf { it.seq })
        }
        if (more) {
            add(Action.Sync(after = cursor))
        } else {
            caughtUp = true
            receipt()?.let(::add)
        }
    }

    fun onPush(message: MailboxMessage): List<Action> = buildList {
        add(Action.Store(listOf(message)))
        highestStored = maxOf(highestStored, message.seq)
        receipt()?.let(::add)
    }

    private fun receipt(): Action.Ack? {
        if (!caughtUp || highestStored <= acknowledged) return null
        acknowledged = highestStored
        return Action.Ack(through = highestStored)
    }
}
