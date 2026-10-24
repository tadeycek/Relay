package com.relay.app.linking

import android.content.Context
import com.relay.app.transport.Transports
import com.relay.app.transport.mailbox.MailboxConfig
import com.relay.app.transport.mailbox.MailboxTransport
import com.relay.app.transport.mailbox.PhoneDeviceEnrollment
import com.relay.app.transport.nostr.NostrIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The account screen's entry point for "Linked computers": links a computer, lists the linked ones
 * and removes one. Everything decided is in [PhoneLinkFlow]; this wires the real transport, the Tink
 * HPKE and this phone's data in, and keeps the work off the main thread.
 *
 * Linking needs Relay's own delivery server: a Nostr-relay-only build has no link slot to park the
 * bundle in, so the action is disabled ([available]) rather than failing halfway.
 */
object PhoneLinkCoordinator {

    fun available(context: Context): Boolean =
        MailboxConfig.available && Transports.get(context) is MailboxTransport

    /** Scans-to-linked: seal, publish, enrol, with the rollback rule from [PhoneLinkFlow]. */
    suspend fun link(context: Context, scannedText: String): PhoneLinkResult = withContext(Dispatchers.IO) {
        val transport = linkTransport(context)
            ?: return@withContext PhoneLinkResult.Failed(
                PhoneLinkError.NOT_CONFIGURED,
                "This build has no Relay delivery server, so a computer can't be linked. Connecting to a " +
                    "relay directly doesn't support linked computers.",
                retryable = false,
            )
        val result = PhoneLinkFlow(transport, AndroidLinkHpke).link(scannedText) {
            PhoneLinkBundleSource.build(context)
        }
        // Sent-copy sync only runs on installs that have linked a computer.
        if (result is PhoneLinkResult.Linked) com.relay.app.sync.SentSync.noteLinkedCount(context, 1)
        result
    }

    /** The computers this identity has linked, newest first is the server's order. */
    suspend fun devices(context: Context): Result<List<LinkedDevice>> = withContext(Dispatchers.IO) {
        val transport = linkTransport(context)
            ?: return@withContext Result.failure(IllegalStateException("no delivery server"))
        transport.listDevices().also { listed -> listed.getOrNull()?.let { com.relay.app.sync.SentSync.noteLinkedCount(context, it.size) } }
    }

    /**
     * Revokes one computer's access to the delivery server. It keeps the keys it already has — the
     * UI says so — so this is access removal, not cryptographic revocation (LP-D02).
     */
    suspend fun remove(context: Context, deviceId: String): LinkHttp = withContext(Dispatchers.IO) {
        val transport = linkTransport(context) ?: return@withContext LinkHttp(0)
        transport.revoke(deviceId)
    }

    private fun linkTransport(context: Context): MailboxLinkTransport? {
        if (!MailboxConfig.available) return null
        val mailbox = Transports.get(context) as? MailboxTransport ?: return null
        val appContext = context.applicationContext
        // Admin key after migration, shared identity key before it: the server accepts both for the
        // link slot, and only the admin key can bind a device's own auth_pubkey.
        return MailboxLinkTransport(mailbox) {
            PhoneDeviceEnrollment.authKeys(appContext) { NostrIdentity.keys(appContext) }
        }
    }
}
