package com.relay.app.transport

import android.content.Context
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
            val created = NostrTransport(
                context = appContext,
                relays = { RelayPreferences(appContext).nostrRelays },
            )
            instance = created
            return created
        }
    }
}
