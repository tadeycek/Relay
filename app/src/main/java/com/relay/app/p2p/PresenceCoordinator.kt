package com.relay.app.p2p

import android.content.Context
import android.util.Log
import com.relay.app.data.model.Contact
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.CooldownLimiter
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.MessagingRuntime
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.Route
import com.relay.app.transport.TorControl
import com.relay.app.transport.Transports
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** Whether a contact can be reached right now for a direct transfer, and how. */
sealed class PresenceState {
    data object Unknown : PresenceState()
    data object Checking : PresenceState()

    /** [nonce] must accompany the eventual transfer offer — see [P2pOfferMessages]. */
    data class Online(val candidates: List<String>, val nonce: String) : PresenceState()
    data object Offline : PresenceState()

    /** P2P is never attempted through Tor (see the class doc); the UI explains this instead of checking. */
    data object TorBlocksThis : PresenceState()
}

/**
 * Runs the on-demand "are they reachable right now" check that gates a direct phone-to-phone media
 * transfer. Deliberately checked once per chat visit rather than broadcast continuously — nothing about
 * being online is revealed to anyone until someone is actively trying to send them something, the same
 * way a location request already works. See [PresenceRules] for the pure timing rules.
 *
 * A direct transfer reveals this phone's IP address to the peer by design, and its NAT-traversal step
 * needs raw UDP, which cannot be routed through Tor's SOCKS proxy — so unlike every other feature, there
 * is no "wait for Tor" state here: with Tor on, this is switched off outright, never attempted, never
 * silently bypassing the app's fail-closed privacy guarantee.
 */
object PresenceCoordinator {

    private const val TAG = "Presence"

    private data class Pending(val contactId: Long, val peerPubkeyHex: String)

    private val states = HashMap<Long, MutableStateFlow<PresenceState>>()
    /** nonce -> who we expect a Pong from, so a Pong is trusted only from the peer we actually pinged. */
    private val pendingNonces = HashMap<String, Pending>()

    /**
     * nonce -> (contact who pinged us, expiry) for a Ping we already answered. An offer is only kept
     * (see [onOffer]) if it names a nonce found here — the nonce only ever reached that contact through
     * the Nostr-authenticated ping/pong exchange, so this is that contact's proof they were really the
     * one who was pinged, exactly the role [com.relay.app.pairing.PairingSessions.consume] plays for
     * pairing.
     */
    private val pongedNonces = HashMap<String, Pair<Long, Long>>() // nonce -> (contactId, expiresAtMs)

    private data class PendingOffer(val contactId: Long, val offer: P2pOfferMessages.Offer, val expiresAtMs: Long)
    /** nonce -> the metadata (including the decryption key) for an offer whose socket hasn't arrived yet. */
    private val pendingOffers = HashMap<String, PendingOffer>()

    /**
     * A verified contact is trusted, but a compromised or misbehaving one flooding Pings should not be
     * able to grow [pongedNonces] without bound or repeatedly force this phone through UPnP discovery
     * (a few seconds of network I/O each time) — one reply per contact per window is plenty for the
     * check's actual purpose.
     */
    private val pingCooldown = CooldownLimiter(2_000L)

    private fun flowFor(contactId: Long): MutableStateFlow<PresenceState> =
        synchronized(states) { states.getOrPut(contactId) { MutableStateFlow(PresenceState.Unknown) } }

    fun stateOf(contactId: Long): StateFlow<PresenceState> = flowFor(contactId).asStateFlow()

    /** Starts (or restarts) an on-demand check. No-op — state left at [PresenceState.TorBlocksThis] — under Tor. */
    fun ping(context: Context, contact: Contact) {
        val appContext = context.applicationContext
        val peer = contact.nostrPubkey ?: return
        val flow = flowFor(contact.id)

        if (TorControl.route(appContext) != Route.Direct) {
            flow.value = PresenceState.TorBlocksThis
            return
        }

        val nonce = PresenceMessages.randomNonce()
        synchronized(pendingNonces) { pendingNonces[nonce] = Pending(contact.id, peer.lowercase()) }
        flow.value = PresenceState.Checking

        MessagingRuntime.launchIo {
            val payload = RelayPayload(id = PayloadCodec.newId(), ts = System.currentTimeMillis(), body = PresenceMessages.formatPing(nonce))
            val result = runCatching { Transports.get(appContext).send(peer, payload, contact.relayHints) }
            if (result.isFailure) {
                Log.i(TAG, "ping send failed: ${result.exceptionOrNull()?.message}")
            }
            delay(PresenceRules.TIMEOUT_MS)
            resolveTimeout(contact.id, nonce)
        }
    }

    @Synchronized
    private fun resolveTimeout(contactId: Long, nonce: String) {
        val stillPending = synchronized(pendingNonces) { pendingNonces[nonce]?.contactId == contactId }
        if (!stillPending) return // a Pong already resolved it
        synchronized(pendingNonces) { pendingNonces.remove(nonce) }
        val flow = flowFor(contactId)
        if (flow.value == PresenceState.Checking) flow.value = PresenceState.Offline
    }

    /** A presence message from [senderPubkeyHex] (already authenticated by the Nostr seal). */
    fun onMessage(context: Context, senderPubkeyHex: String, message: PresenceMessage) {
        when (message) {
            is PresenceMessage.Ping -> onPing(context, senderPubkeyHex, message)
            is PresenceMessage.Pong -> onPong(senderPubkeyHex, message)
        }
    }

    private fun onPing(context: Context, senderPubkeyHex: String, ping: PresenceMessage.Ping) {
        val appContext = context.applicationContext
        // A Ping received while Tor is on still gets no reply: replying would itself confirm we're
        // reachable and, if answered honestly with real addresses, defeats the point of Tor being on.
        if (TorControl.route(appContext) != Route.Direct) return

        // Only reply to a contact verified in person, same rule MediaReceiver already enforces before
        // fetching a media URL: a stranger who merely knows our key must not learn our reachable address.
        val contact = ContactRepository(MessagingDb.get(appContext)).findByNostrPubkeySync(senderPubkeyHex)
        if (contact == null || !contact.qrVerified) return
        if (!pingCooldown.allow(contact.id, System.currentTimeMillis())) return

        synchronized(pongedNonces) {
            prunePonged()
            pongedNonces[ping.nonce] = contact.id to (System.currentTimeMillis() + PresenceRules.PONGED_TTL_MS)
        }
        MessagingRuntime.launchIo {
            val body = PresenceMessages.formatPong(ping.nonce, localCandidates())
            val payload = RelayPayload(id = PayloadCodec.newId(), ts = System.currentTimeMillis(), body = body)
            runCatching { Transports.get(appContext).send(senderPubkeyHex, payload) }
        }
    }

    private fun prunePonged() {
        val now = System.currentTimeMillis()
        pongedNonces.entries.removeAll { it.value.second < now }
    }

    /**
     * A P2P_OFFER arrived over the normal encrypted message channel: the sender is about to open a
     * transfer socket carrying this nonce. Only kept if this phone actually answered a Ping with that
     * exact nonce for that same contact -- an offer alone, without a matching ponged nonce, is dropped.
     */
    @Synchronized
    fun onOffer(senderContactId: Long, offer: P2pOfferMessages.Offer) {
        val expected = synchronized(pongedNonces) { pongedNonces[offer.nonce] }
        if (expected == null || expected.first != senderContactId) {
            Log.i(TAG, "ignored a P2P offer with no matching presence check")
            return
        }
        synchronized(pendingOffers) {
            prunePendingOffers()
            pendingOffers[offer.nonce] = PendingOffer(senderContactId, offer, System.currentTimeMillis() + PresenceRules.PONGED_TTL_MS)
        }
    }

    /** Single-use: the P2P listener calls this once it has read the socket's nonce header. */
    fun consumeOffer(nonce: String): Pair<Long, P2pOfferMessages.Offer>? = synchronized(pendingOffers) {
        prunePendingOffers()
        pendingOffers.remove(nonce)?.let { it.contactId to it.offer }
    }

    private fun prunePendingOffers() {
        val now = System.currentTimeMillis()
        pendingOffers.entries.removeAll { it.value.expiresAtMs < now }
    }

    @Synchronized
    private fun onPong(senderPubkeyHex: String, pong: PresenceMessage.Pong) {
        val pending = synchronized(pendingNonces) { pendingNonces[pong.nonce] }
        // Trust a Pong only from the exact peer this nonce was sent to -- a nonce is secret and unique
        // per ping, but the sender's identity is still checked explicitly rather than relied on implicitly.
        if (pending == null || !pending.peerPubkeyHex.equals(senderPubkeyHex, ignoreCase = true)) {
            Log.i(TAG, "ignored a pong that does not match a pending ping")
            return
        }
        synchronized(pendingNonces) { pendingNonces.remove(pong.nonce) }
        flowFor(pending.contactId).value =
            if (pong.candidates.isEmpty()) PresenceState.Offline else PresenceState.Online(pong.candidates, pong.nonce)
    }

    /**
     * This phone's reachable addresses, offered as candidates: its own LAN address(es) first (the
     * reliable case — same-network transfers never depend on the next part), then a best-effort UPnP
     * router mapping so a transfer can sometimes still connect from a different network. UPnP is tried
     * against the first LAN address only and quietly skipped if there is none or it fails — see
     * [UpnpPortMapper]'s class doc for why that is expected, not a bug.
     */
    private fun localCandidates(port: Int = P2P_PORT): List<String> {
        val lan = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { it.hostAddress!! }
        }.getOrElse { emptyList() }

        val external = lan.firstOrNull()?.let { runCatching { UpnpPortMapper.requestMapping(port, it) }.getOrNull() }

        return (lan.map { "$it:$port" } + listOfNotNull(external)).distinct()
    }

    /** The fixed port the receive listener binds on this phone's LAN address (see P2pTransfer). */
    const val P2P_PORT = 41_200
}
