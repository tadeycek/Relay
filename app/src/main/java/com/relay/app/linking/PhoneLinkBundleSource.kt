package com.relay.app.linking

import android.content.Context
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.db.RelayDbHelper
import com.relay.app.data.repository.ContactRepository
import com.relay.app.data.repository.GroupRepository
import com.relay.app.groups.SharedGroups
import com.relay.app.transport.nostr.NostrIdentity
import com.relay.app.util.RelayPreferences
import java.util.Locale

/**
 * Reads this phone's state into the [LinkBundle] a computer needs: the shared identity (Nostr secret
 * plus the Tink HPKE keysets, so the computer can decrypt as this identity), every contact, the
 * shared groups' rosters, and the settings the desktop mirrors.
 *
 * Deliberately no message history: the model has no field for it and `LinkBundleCodec` rejects one
 * (LP-D06). Everything that could exceed a codec bound is trimmed by [LinkBundleMapping].
 *
 * Blocking database work: call off the main thread. Throws [LinkBundleException] only if the Tink
 * keyset cannot be exported, which the flow turns into a local error before anything is uploaded.
 */
object PhoneLinkBundleSource {

    suspend fun build(context: Context, now: Long = System.currentTimeMillis()): LinkBundle {
        val appContext = context.applicationContext
        val keys = NostrIdentity.keys(appContext)
        val prefs = RelayPreferences(appContext)
        val db = RelayDbHelper(appContext)

        val currentKeyset = RelayCrypto.exportKeysetBase64ForLink(appContext)
            ?: throw LinkBundleException("this phone's encryption key is unavailable, so it cannot be linked")
        val previousExpiry = prefs.previousKeyExpiresAt
        val previousKeyset = if (previousExpiry > now) RelayCrypto.exportPreviousKeysetBase64ForLink(appContext) else null

        val contacts = LinkBundleMapping.contacts(ContactRepository(db).getAllContacts())

        // Only shared groups carry the protocol UUID a computer can address the group by.
        val groups = GroupRepository(db).getAllGroups().mapNotNull { group ->
            val stored = SharedGroups.get(db, group.id) ?: return@mapNotNull null
            if (stored.hidden) return@mapNotNull null
            LinkBundleMapping.group(
                name = stored.state.name,
                protocolId = stored.state.id,
                owner = stored.state.owner,
                members = stored.state.members.map { it.key },
            )
        }

        return LinkBundle(
            createdAt = now,
            identity = LinkIdentity(
                nostrSecretHex = keys.secretKey().toHex(),
                nostrPubkeyHex = keys.publicKey().toHex(),
                // 'primary' is this phone's device id on the server; the computer's own id comes from
                // the QR it showed, which is what the enrolment call uses.
                deviceId = "primary",
                deviceLabel = prefs.myName.take(LinkBundleCodec.MAX_DEVICE_LABEL),
                hpkeKeysetBase64 = currentKeyset,
                hpkePreviousKeysetBase64 = previousKeyset,
                hpkeKeysetExpiresAt = if (previousKeyset != null) previousExpiry else 0,
            ),
            contacts = contacts,
            groups = groups,
            settings = LinkBundleMapping.settings(
                typingIndicators = prefs.typingIndicators,
                // The phone has one "notify me for messages" switch; it decides whether the phone
                // shows content at all, so it is the closest existing source for previews.
                notificationPreview = prefs.incomingMessageNotification,
                // Retention and media auto-download have no phone setting yet (the desktop defaults
                // are the phone's behaviour), so the codec defaults are passed through explicitly.
                retentionDays = LinkSettings().retentionDays,
                readReceipts = prefs.readReceipts,
                mediaAutoDownload = LinkSettings().mediaAutoDownload,
                theme = prefs.theme,
                locale = Locale.getDefault().toLanguageTag(),
            ),
        )
    }
}
