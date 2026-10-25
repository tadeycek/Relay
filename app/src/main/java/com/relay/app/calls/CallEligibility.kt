package com.relay.app.calls

import com.relay.app.data.model.Contact
import com.relay.app.data.model.ContactTrustLevel

/** Same rule the host applies to incoming senders (`CallController.isAllowedPeer`), applied to the call button. */
object CallEligibility {
    fun canCall(contact: Contact?): Boolean =
        contact != null && !contact.isDeleted && contact.nostrPubkey != null &&
            contact.qrVerified && contact.trustLevel != ContactTrustLevel.BLOCKED
}
