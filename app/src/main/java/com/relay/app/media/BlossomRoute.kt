package com.relay.app.media

import android.content.Context
import com.relay.app.transport.Route
import com.relay.app.transport.TorControl

/** Tor-required-but-down message shown when a media transfer is refused rather than made directly. */
const val TOR_UNAVAILABLE_MESSAGE = "Tor is turned on but isn't running. Start Orbot, or turn Tor off in Settings."

/**
 * A [BlossomClient] that follows the user's Tor setting, or null if Tor is required but unavailable
 * (fail closed: a media transfer must never quietly bypass Tor and reveal the user's IP).
 */
/**
 * How a received media reference is fetched: from Relay's own server when this build has one (and
 * nothing else is ever fetched then), otherwise from Blossom. Null when it can't be fetched at all.
 */
internal fun mediaFetcher(context: Context, url: String): ((String, String, Long) -> ByteArray?)? =
    if (com.relay.app.transport.mailbox.MailboxConfig.available) {
        if (com.relay.app.transport.mailbox.MailboxMedia.isOurs(url)) { u, sha, max -> com.relay.app.transport.mailbox.MailboxMedia.download(context, u, sha, max) }
        else null
    } else blossomClientFor(context)?.let { client -> { u, sha, max -> client.download(u, sha, max) } }

internal fun blossomClientFor(context: Context): BlossomClient? = when (val route = TorControl.route(context)) {
    Route.Direct -> BlossomClient()
    is Route.Socks -> BlossomClient(proxy = TorControl.javaProxy(route))
    is Route.Blocked -> null
}
