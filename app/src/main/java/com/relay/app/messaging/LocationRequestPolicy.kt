package com.relay.app.messaging

import com.relay.app.data.model.ContactTrustLevel

/**
 * What to do when a contact asks for our location. Pure so the rules are unit-tested.
 *
 * The important rule: a stranger who merely knows our public key (not [verified] by scanning their
 * QR) can never trigger an *automatic* share, even with "auto-approve" switched on. They can still
 * ask, and the user decides from the notification. Explicitly marking a contact Trusted is a
 * deliberate user action and does allow automatic sharing.
 */
object LocationRequestPolicy {

    enum class Decision { DROP, AUTO_SHARE, ASK }

    fun decide(
        fromNobody: Boolean,
        autoApproveAll: Boolean,
        trust: ContactTrustLevel,
        verified: Boolean,
        inDndWindow: Boolean,
    ): Decision = when {
        fromNobody -> Decision.DROP
        trust == ContactTrustLevel.BLOCKED -> Decision.DROP
        inDndWindow -> Decision.DROP
        trust == ContactTrustLevel.TRUSTED || (autoApproveAll && verified) -> Decision.AUTO_SHARE
        else -> Decision.ASK
    }
}
