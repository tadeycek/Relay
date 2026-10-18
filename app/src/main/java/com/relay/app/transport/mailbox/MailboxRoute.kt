package com.relay.app.transport.mailbox

import com.relay.app.transport.RelayPayload
import com.relay.app.transport.Transport

/** The mailbox as a route: a [Transport] that can also say why it didn't take a message. */
interface MailboxRoute : Transport {
    /** Hands one message to the mailbox. Safe to call again with the same payload. */
    suspend fun deliver(recipient: String, payload: RelayPayload): MailboxProtocol.Outcome
}
