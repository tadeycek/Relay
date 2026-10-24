package com.relay.app.desktop

import com.relay.app.linking.LinkBundle
import rust.nostr.sdk.Keys

/**
 * The durable half of linking: writes the phone's keys into this install and imports contacts,
 * groups and settings into the encrypted store.
 *
 * Order matters for the "failed commit leaves the slot unconsumed" rule: every step below can throw,
 * and [DesktopLinkFlow] only sends `DELETE /v1/link/<slot>` after [import] returns normally. A throw
 * therefore means the bundle is still at the slot and the user can retry (GET is a lease, not a
 * delete-on-read).
 *
 * Idempotency: the store's import is keyed and timestamped (see
 * [DesktopMessageStore.importLinkBundle]); [DesktopIdentity.adopt] and [DesktopKeyMaterial.adoptKeyset]
 * rewrite the same values for the same bundle. Re-running a successful import changes nothing.
 */
class DesktopStoreLinkImporter(
    private val identity: DesktopIdentity,
    private val keyMaterial: DesktopKeyMaterial,
    private val store: DesktopMessageStore,
    private val linkKeys: DesktopLinkKeys,
    private val clock: LinkClock = SystemLinkClock,
) : LinkImporter {

    override fun import(bundle: LinkBundle, device: DesktopLinkDevice): LinkImportReport {
        val phoneIdentity = bundle.identity

        // 1. The bundle must be self-consistent before anything is written: the secret it carries
        //    has to derive the public key it claims. A mismatch is a corrupt or hostile bundle.
        val adopted = try {
            Keys.parse(phoneIdentity.nostrSecretHex)
        } catch (e: Exception) {
            throw LinkImportException(LinkFailure.CORRUPT_BUNDLE, "The bundle's identity key is not a valid secret key.")
        }
        if (adopted.publicKey().toHex() != phoneIdentity.nostrPubkeyHex) {
            throw LinkImportException(
                LinkFailure.CORRUPT_BUNDLE,
                "The bundle's identity key pair does not match, so it cannot be this account.",
            )
        }

        // 2. Refuse to silently replace a link to a different account. Same account (a retry) is fine.
        linkKeys.linkedDevice()?.let { existing ->
            if (existing.identityPubkeyHex != phoneIdentity.nostrPubkeyHex) {
                throw LinkImportException(
                    LinkFailure.ALREADY_LINKED,
                    "This computer is already linked to ${existing.identityPubkeyHex.take(8)}…. " +
                        "Unlink it on the phone first if you really want to switch accounts.",
                )
            }
        }

        // 3. Keys: the identity's HPKE keyset first (so a message that arrives during the import can
        //    already be opened), then the Nostr secret.
        keyMaterial.adoptKeyset(
            currentKeysetBase64 = phoneIdentity.hpkeKeysetBase64,
            previousKeysetBase64 = phoneIdentity.hpkePreviousKeysetBase64,
            previousExpiresAt = phoneIdentity.hpkeKeysetExpiresAt,
        )
        identity.adopt(phoneIdentity.nostrSecretHex)

        // 4. Contacts, groups, settings, in one SQLite transaction. The phone's own device label is
        //    kept for the "Linked to …" line; the bundle's createdAt is the version marker, so a
        //    re-import of the same bundle never overwrites a newer local row.
        val counts = store.importLinkBundle(bundle, appliedAt = bundle.createdAt)
        store.setMeta("link.identityLabel", phoneIdentity.deviceLabel)

        // 5. Record the link last: until this row exists the UI keeps offering "link this computer".
        linkKeys.markLinked(phoneIdentity.nostrPubkeyHex, clock.now())

        return LinkImportReport(
            identityPubkeyHex = phoneIdentity.nostrPubkeyHex,
            contactsInserted = counts.inserted,
            contactsUpdated = counts.updated,
            groups = counts.groups,
            settingsApplied = counts.settingsApplied,
        )
    }
}
