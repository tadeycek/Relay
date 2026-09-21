package com.relay.app.pairing

import android.content.Context
import android.content.Intent
import android.util.Log
import com.relay.app.crypto.RelayCrypto
import com.relay.app.data.model.ContactTrustLevel
import com.relay.app.data.repository.ContactRepository
import com.relay.app.messaging.MessagingDb
import com.relay.app.messaging.MessagingRuntime
import com.relay.app.messaging.Outgoing
import com.relay.app.sms.QrContactExchange
import com.relay.app.util.QrContactCode
import com.relay.app.util.RelayPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** B saw A add them: waiting for B to answer. */
data class IncomingPairing(
    val peerPubkeyHex: String,
    val name: String,
    val publicKeyBase64: String,
    val signingPublicKeyBase64: String?,
    val nonce: String,
    val deadlineMs: Long,
)

sealed class PairingEvent {
    /** Both people said yes in time: the contact is now verified on this phone. */
    data class Verified(val contactId: Long, val name: String) : PairingEvent()

    /** The other person did not answer in time; nothing was kept. */
    data class TimedOut(val name: String) : PairingEvent()

    data class Cancelled(val name: String) : PairingEvent()

    /** Both said yes, but the contact already had a different key on file: it needs a manual review first. */
    data class NeedsKeyReview(val name: String) : PairingEvent()
}

/**
 * Runs mutual in-person pairing on this phone; the rules themselves are in [PairingRules] and are
 * unit-tested. Two roles, and one phone can be in both at once (both scan each other):
 *  - **Initiator**: scanned a code that carries a one-time code. After the user's yes it sends a request and
 *    waits (persisted, so a restart does not lose it) for the other person's confirmation.
 *  - **Recipient**: shows a code. A request that repeats a code this phone showed is genuine proof the sender
 *    was there, and is put to the user as a yes/no question.
 * Nothing is trusted or marked verified until both have said yes inside the window; if that does not happen,
 * a contact the pairing itself created is removed again.
 */
object PairingCoordinator {

    private const val TAG = "Pairing"
    private const val PREFS = "relay_pairing"
    private const val KEY_OUTGOING = "outgoing"

    val sessions = PairingSessions()

    private val _shownNonce = MutableStateFlow(sessions.current())

    /** The one-time code the "Show my code" screen must currently encode. */
    val shownNonce: StateFlow<String> = _shownNonce.asStateFlow()

    private val _incoming = MutableStateFlow<IncomingPairing?>(null)
    val incoming: StateFlow<IncomingPairing?> = _incoming.asStateFlow()

    private val _outgoing = MutableStateFlow<PendingPairing?>(null)
    val outgoing: StateFlow<PendingPairing?> = _outgoing.asStateFlow()

    private val _events = MutableSharedFlow<PairingEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PairingEvent> = _events.asSharedFlow()

    /** Called on a timer by the code screen so an unused code rotates instead of living forever. */
    fun refreshShownNonce() {
        _shownNonce.value = sessions.current()
    }

    /** Picks up a pairing that was waiting when the app last stopped. */
    @Synchronized
    fun restore(context: Context) {
        val stored = prefs(context).getString(KEY_OUTGOING, null)?.let { PendingPairing.fromJson(it) }
        if (stored == null) {
            prefs(context).edit().remove(KEY_OUTGOING).apply()
            return
        }
        _outgoing.value = stored
        scheduleOutgoingExpiry(context.applicationContext, stored)
    }

    // ---- initiator ------------------------------------------------------------------------------

    /**
     * The user said yes to adding a scanned contact. Sends the request and starts waiting. Returns false
     * if it could not be started (no keys yet, or the code has no pairing field).
     */
    @Synchronized
    fun startOutgoing(context: Context, scanned: QrContactCode.ScannedContact): Boolean {
        val appContext = context.applicationContext
        val nonce = scanned.pairNonce ?: return false
        val peer = scanned.nostrPubkeyHex ?: return false
        val myKey = RelayCrypto.myPublicKeyBase64(appContext) ?: return false

        _outgoing.value?.let { dropOutgoing(appContext, it, PairingEvent.Cancelled(it.name)) }

        val repo = repo(appContext)
        val existed = repo.findByNostrPubkeySync(peer) != null
        val contact = repo.findOrCreateByNostrSync(peer, scanned.name, scanned.relayHints)
        val now = System.currentTimeMillis()
        val pending = PendingPairing(
            peerPubkeyHex = peer,
            nonce = nonce,
            name = scanned.name,
            publicKeyBase64 = scanned.publicKeyBase64,
            signingPublicKeyBase64 = scanned.signingPublicKeyBase64,
            relayHints = scanned.relayHints,
            createdContact = !existed,
            contactId = contact.id,
            deadlineMs = PairingRules.outgoingDeadline(now),
        )
        val body = PairingMessages.formatRequest(
            nonce = nonce,
            name = RelayPreferences(appContext).myName,
            publicKeyBase64 = myKey,
            signingPublicKeyBase64 = RelayCrypto.mySigningPublicKeyBase64(appContext),
        )
        if (Outgoing.enqueue(appContext, contact, body, seal = false) == null) {
            if (!existed) repo.deleteIfUnusedSync(contact.id)
            return false
        }
        // The request carries our keys, so the usual key handshake is not needed as well.
        repo.markSentPubkeySync(contact.id)
        save(appContext, pending)
        _outgoing.value = pending
        scheduleOutgoingExpiry(appContext, pending)
        return true
    }

    @Synchronized
    fun cancelOutgoing(context: Context) {
        val pending = _outgoing.value ?: return
        dropOutgoing(context.applicationContext, pending, PairingEvent.Cancelled(pending.name))
    }

    private fun scheduleOutgoingExpiry(context: Context, pending: PendingPairing) {
        MessagingRuntime.launchIo {
            delay((pending.deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L) + 50L)
            expireOutgoing(context, pending.nonce)
        }
    }

    @Synchronized
    private fun expireOutgoing(context: Context, nonce: String) {
        val pending = _outgoing.value?.takeIf { it.nonce == nonce } ?: return
        if (!PairingRules.isExpired(pending.deadlineMs, System.currentTimeMillis())) return
        dropOutgoing(context, pending, PairingEvent.TimedOut(pending.name))
    }

    /** Forgets a pairing that did not complete, removing a contact the pairing created if nothing else uses it. */
    private fun dropOutgoing(context: Context, pending: PendingPairing, event: PairingEvent) {
        if (pending.createdContact) repo(context).deleteIfUnusedSync(pending.contactId)
        clearOutgoing(context)
        _events.tryEmit(event)
        broadcastContactsChanged(context, pending.contactId)
    }

    private fun clearOutgoing(context: Context) {
        _outgoing.value = null
        prefs(context).edit().remove(KEY_OUTGOING).apply()
    }

    private fun completeOutgoing(context: Context, pending: PendingPairing) {
        val scanned = QrContactCode.ScannedContact(
            phone = null,
            name = pending.name,
            publicKeyBase64 = pending.publicKeyBase64,
            signingPublicKeyBase64 = pending.signingPublicKeyBase64,
            nostrPubkeyHex = pending.peerPubkeyHex,
            relayHints = pending.relayHints,
        )
        val contact = QrContactExchange.onScanned(context, repo(context), scanned, sendHandshake = false)
        clearOutgoing(context)
        _events.tryEmit(
            if (contact.qrVerified) PairingEvent.Verified(contact.id, contact.name) else PairingEvent.NeedsKeyReview(contact.name)
        )
        broadcastContactsChanged(context, contact.id)
    }

    // ---- both roles: messages from the transport -------------------------------------------------

    /** Handles a pairing message from [senderPubkeyHex] (already authenticated by the Nostr seal). */
    @Synchronized
    fun onMessage(context: Context, senderPubkeyHex: String, message: PairingMessage, sentAtMs: Long, nowMs: Long) {
        val appContext = context.applicationContext
        val known = repo(appContext).findByNostrPubkeySync(senderPubkeyHex)
        if (known?.trustLevel == ContactTrustLevel.BLOCKED) return
        when (message) {
            is PairingMessage.Request -> onRequest(appContext, senderPubkeyHex, message, sentAtMs, nowMs)
            is PairingMessage.Confirm -> {
                val pending = _outgoing.value
                if (PairingRules.confirmAccepted(pending, senderPubkeyHex, message.nonce, nowMs)) {
                    completeOutgoing(appContext, pending!!)
                } else {
                    Log.i(TAG, "ignored a confirm that does not match a pending pairing")
                }
            }
        }
    }

    private fun onRequest(context: Context, sender: String, req: PairingMessage.Request, sentAtMs: Long, nowMs: Long) {
        val deadline = PairingRules.incomingDeadline(sentAtMs, nowMs)
        if (PairingRules.isExpired(deadline, nowMs)) return
        // Only a code this phone is showing (or showed, and has not yet used) proves the sender saw the screen.
        if (!sessions.consume(req.nonce)) {
            Log.i(TAG, "ignored a pairing request with an unknown or used code")
            return
        }
        _shownNonce.value = sessions.current()

        val incoming = IncomingPairing(sender, req.name, req.publicKeyBase64, req.signingPublicKeyBase64, req.nonce, deadline)
        if (PairingRules.isCrossRequest(_outgoing.value, sender)) {
            // We already said yes to adding them, and they said yes to adding us: nothing left to ask.
            _outgoing.value?.let { clearOutgoing(context) }
            completeIncoming(context, incoming)
            return
        }
        _incoming.value = incoming
        MessagingRuntime.launchIo {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0L) + 50L)
            expireIncoming(incoming.nonce)
        }
    }

    // ---- recipient ------------------------------------------------------------------------------

    /** The user answered yes to "added you, add them back?". */
    @Synchronized
    fun acceptIncoming(context: Context) {
        val incoming = _incoming.value ?: return
        _incoming.value = null
        if (PairingRules.isExpired(incoming.deadlineMs, System.currentTimeMillis())) {
            _events.tryEmit(PairingEvent.TimedOut(displayName(incoming)))
            return
        }
        completeIncoming(context.applicationContext, incoming)
    }

    @Synchronized
    fun declineIncoming() {
        _incoming.value = null
    }

    @Synchronized
    private fun expireIncoming(nonce: String) {
        val incoming = _incoming.value?.takeIf { it.nonce == nonce } ?: return
        _incoming.value = null
        _events.tryEmit(PairingEvent.TimedOut(displayName(incoming)))
    }

    private fun displayName(i: IncomingPairing) = i.name.ifBlank { "Contact ${i.peerPubkeyHex.take(8)}" }

    private fun completeIncoming(context: Context, incoming: IncomingPairing) {
        val repo = repo(context)
        val scanned = QrContactCode.ScannedContact(
            phone = null,
            name = displayName(incoming),
            publicKeyBase64 = incoming.publicKeyBase64,
            signingPublicKeyBase64 = incoming.signingPublicKeyBase64,
            nostrPubkeyHex = incoming.peerPubkeyHex,
        )
        val contact = QrContactExchange.onScanned(context, repo, scanned, sendHandshake = false)
        // Their scan of our code already gave them our keys; the confirm is what completes it for them.
        repo.markSentPubkeySync(contact.id)
        if (contact.qrVerified) {
            Outgoing.enqueue(context, contact, PairingMessages.formatConfirm(incoming.nonce), seal = false)
            _events.tryEmit(PairingEvent.Verified(contact.id, contact.name))
        } else {
            _events.tryEmit(PairingEvent.NeedsKeyReview(contact.name))
        }
        broadcastContactsChanged(context, contact.id)
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private fun repo(context: Context) = ContactRepository(MessagingDb.get(context))

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun save(context: Context, pending: PendingPairing) {
        prefs(context).edit().putString(KEY_OUTGOING, pending.toJson()).apply()
    }

    private fun broadcastContactsChanged(context: Context, contactId: Long) {
        context.sendBroadcast(Intent("com.relay.app.NEW_MESSAGE").apply {
            putExtra("contact_id", contactId)
            setPackage(context.packageName)
        })
    }
}
