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
            val nostr = NostrTransport(
                context = appContext,
                relays = { RelayPreferences(appContext).nostrRelays },
                route = { TorControl.route(appContext) },
            )
            // Builds with a pilot delivery server receive on both routes; the Settings switch picks the sending route.
            val created = if (MailboxConfig.available) {
                HybridTransport(nostr, MailboxTransport(appContext, MailboxConfig.url)) { RelayPreferences(appContext).mailboxEnabled }
            } else nostr
            instance = created
            return created
        }
    }
}
