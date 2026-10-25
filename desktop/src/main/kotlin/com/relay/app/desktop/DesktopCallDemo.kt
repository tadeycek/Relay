package com.relay.app.desktop

import com.relay.app.call.CallOutcome
import com.relay.app.linking.LinkBundle
import com.relay.app.linking.LinkContact
import com.relay.app.linking.LinkIdentity
import com.relay.app.linking.LinkSettings

/**
 * Diagnostics only (`--demo-call`, requires `--show-chat-ui` and explicit dirs). It imports one QR-verified demo contact and
 * a local call-log row, and renders a FIXED in-call state so a screenshot can show the Call button, the in-call bar and the log.
 * The displayed call is NOT a real call; no network, no microphone, no signalling.
 */
internal object DesktopCallDemo {
    val PEER = "5e".repeat(32)

    fun seed(engine: DesktopEngine) {
        engine.store.importLinkBundle(
            LinkBundle(createdAt = 1, identity = LinkIdentity("0".repeat(64), engine.identity.publicKeyHex(), "primary", "demo", "demo"),
                contacts = listOf(LinkContact(name = "Bea (demo)", phone = "", hasRelay = true, trustLevel = "trusted", nostrPubkey = PEER, qrVerified = true)),
                groups = emptyList(), settings = LinkSettings()), 1,
        )
        engine.store.ensureConversation(PEER, "Bea (demo)")
        engine.store.recordCall(DesktopCallRecord("0123456789abcdef0123456789abcdef", PEER, true, CallOutcome.MISSED, null, 0, System.currentTimeMillis() - 3_600_000))
    }

    fun ui() = DesktopCallUi(
        stage = DesktopCallStage.ACTIVE, callId = "0".repeat(32), peer = PEER, peerName = "Bea (demo)",
        connectedAtMs = System.currentTimeMillis() - 65_000, muted = false, status = "Connected", notice = null,
    )
}
