package com.relay.app.transport

import android.content.Context
import com.relay.app.transport.mailbox.MailboxConfig
import com.relay.app.transport.mailbox.MailboxTransport
import com.relay.app.transport.nostr.NostrTransport
import com.relay.app.util.RelayPreferences

/** Process-wide access to the single active [Transport]. */
object Transports {

    @Volatile private var instance: Transport? = null

    fun get(context: Context): Transport {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val appContext = context.applicationContext
            val nostr by lazy {
                NostrTransport(
                    context = appContext,
                    relays = { RelayPreferences(appContext).nostrRelays },
                    route = { TorControl.route(appContext) },
                )
            }
            // A build with Relay's own delivery server uses only that: no public relay is contacted at all
            // (decided 2026-10-18). Builds without one (local development) keep the Nostr relays.
            val created = if (MailboxConfig.available) MailboxTransport(appContext, MailboxConfig.url) else nostr
            instance = created
            return created
        }
    }
}
