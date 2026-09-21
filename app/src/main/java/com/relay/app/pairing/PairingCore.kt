package com.relay.app.pairing

import org.json.JSONObject
import java.security.SecureRandom

/**
 * Rules for mutual in-person pairing, kept free of Android so they are unit-tested.
 *
 * The flow: B shows a code (QR or NFC) that carries a one-time [nonce]. A scans it and taps yes; A sends
 * B a [PairingMessage.Request] that repeats the nonce. Only someone who saw B's screen knows the nonce,
 * so B's phone can trust the request came from a person standing there. B taps yes and sends back a
 * [PairingMessage.Confirm]. When both have said yes inside the window, both sides are marked verified.
 */
object PairingRules {
    /** How long each person has to answer. */
    const val WINDOW_MS = 3 * 60 * 1000L

    /** Extra time the first person waits, so the second person's answer can still arrive over the network. */
    const val GRACE_MS = 45 * 1000L

    /** How long a displayed code stays usable if nobody scans it. */
    const val NONCE_TTL_MS = 10 * 60 * 1000L

    /** A's deadline for hearing back, counted from A's yes. */
    fun outgoingDeadline(yesAtMs: Long): Long = yesAtMs + WINDOW_MS + GRACE_MS

    /**
     * B's deadline for answering, counted from when B received the request. The sender's timestamp
     * shortens it when it is plausible, so B cannot answer after A has already given up; an implausible
     * timestamp (wrong clock, or from the future) is ignored.
     */
    fun incomingDeadline(sentAtMs: Long, receivedAtMs: Long): Long {
        val plausible = sentAtMs in (receivedAtMs - WINDOW_MS)..receivedAtMs
        val start = if (plausible) sentAtMs else receivedAtMs
        return start + WINDOW_MS
    }

    fun isExpired(deadlineMs: Long, nowMs: Long): Boolean = nowMs > deadlineMs

    /** A confirm counts only from the person we asked, for the code we sent, before the deadline. */
    fun confirmAccepted(pending: PendingPairing?, senderPubkeyHex: String, nonce: String, nowMs: Long): Boolean =
        pending != null &&
            pending.peerPubkeyHex.equals(senderPubkeyHex, ignoreCase = true) &&
            pending.nonce == nonce &&
            !isExpired(pending.deadlineMs, nowMs)

    /**
     * Both people scanned each other at the same moment: each already said yes to adding the other, so a
     * valid request from the person we are waiting on is itself their yes.
     */
    fun isCrossRequest(pending: PendingPairing?, senderPubkeyHex: String): Boolean =
        pending != null && pending.peerPubkeyHex.equals(senderPubkeyHex, ignoreCase = true)
}

/** A's side of a pairing that has been started but not yet confirmed by the other person. */
data class PendingPairing(
    val peerPubkeyHex: String,
    val nonce: String,
    val name: String,
    val publicKeyBase64: String,
    val signingPublicKeyBase64: String?,
    val relayHints: List<String>,
    /** True when the pairing created the contact row, so a failed pairing can remove it again. */
    val createdContact: Boolean,
    val contactId: Long,
    val deadlineMs: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("peer", peerPubkeyHex)
        put("nonce", nonce)
        put("name", name)
        put("key", publicKeyBase64)
        put("sig", signingPublicKeyBase64 ?: JSONObject.NULL)
        put("relays", org.json.JSONArray(relayHints))
        put("created", createdContact)
        put("contact", contactId)
        put("deadline", deadlineMs)
    }.toString()

    companion object {
        fun fromJson(json: String): PendingPairing? = runCatching {
            val o = JSONObject(json)
            val relays = o.getJSONArray("relays")
            PendingPairing(
                peerPubkeyHex = o.getString("peer"),
                nonce = o.getString("nonce"),
                name = o.getString("name"),
                publicKeyBase64 = o.getString("key"),
                signingPublicKeyBase64 = if (o.isNull("sig")) null else o.getString("sig"),
                relayHints = List(relays.length()) { relays.getString(it) },
                createdContact = o.getBoolean("created"),
                contactId = o.getLong("contact"),
                deadlineMs = o.getLong("deadline"),
            )
        }.getOrNull()
    }
}

/** The one-time codes this phone has shown and will still accept a request for. */
class PairingSessions(
    private val clock: () -> Long = System::currentTimeMillis,
    private val newNonce: () -> String = PairingMessages::randomNonce,
) {
    private val live = LinkedHashMap<String, Long>()

    /** A fresh code, valid for [PairingRules.NONCE_TTL_MS]. */
    @Synchronized
    fun issue(): String {
        prune()
        val nonce = newNonce()
        live[nonce] = clock() + PairingRules.NONCE_TTL_MS
        return nonce
    }

    /** The newest code that is still valid, or a new one when none is. */
    @Synchronized
    fun current(): String {
        prune()
        return live.keys.lastOrNull() ?: issue()
    }

    /** Uses a code up: true once for a valid code, false for an unknown, expired or already used one. */
    @Synchronized
    fun consume(nonce: String): Boolean {
        prune()
        return live.remove(nonce) != null
    }

    private fun prune() {
        val now = clock()
        live.entries.removeAll { it.value < now }
    }
}

sealed class PairingMessage {
    abstract val nonce: String

    /** A -> B: "I added you", carrying A's keys so B can add A back. */
    data class Request(
        override val nonce: String,
        val name: String,
        val publicKeyBase64: String,
        val signingPublicKeyBase64: String?,
    ) : PairingMessage()

    /** B -> A: "I added you back". */
    data class Confirm(override val nonce: String) : PairingMessage()
}

object PairingMessages {
    private val NONCE = Regex("""[0-9a-f]{32}""")
    private val BASE64 = Regex("""[A-Za-z0-9+/=]+""")

    fun randomNonce(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidNonce(s: String?): Boolean = s != null && NONCE.matches(s)

    private fun clean(s: String) = s.replace(Regex("""[\p{Cc}\p{Cf}]"""), "").replace("|", "").trim().take(30)

    fun formatRequest(nonce: String, name: String, publicKeyBase64: String, signingPublicKeyBase64: String?): String {
        var msg = "TYPE:PAIR_REQUEST|NONCE:$nonce|KEY:$publicKeyBase64"
        if (!signingPublicKeyBase64.isNullOrEmpty()) msg += "|SIG:$signingPublicKeyBase64"
        val safe = clean(name)
        if (safe.isNotEmpty()) msg += "|NAME:$safe"
        return msg
    }

    fun formatConfirm(nonce: String): String = "TYPE:PAIR_CONFIRM|NONCE:$nonce"

    /** Null unless [body] is a well-formed pairing message. Never throws on hostile input. */
    fun parse(body: String): PairingMessage? {
        val parts = body.trim().split('|')
        val type = parts.firstOrNull()?.takeIf { it.startsWith("TYPE:") }?.removePrefix("TYPE:") ?: return null
        if (type != "PAIR_REQUEST" && type != "PAIR_CONFIRM") return null
        val fields = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val i = p.indexOf(':')
            if (i <= 0) return null
            fields[p.substring(0, i)] = p.substring(i + 1)
        }
        val nonce = fields["NONCE"]?.takeIf { NONCE.matches(it) } ?: return null
        if (type == "PAIR_CONFIRM") return PairingMessage.Confirm(nonce)

        val key = fields["KEY"]?.takeIf { it.isNotEmpty() && BASE64.matches(it) } ?: return null
        val sig = fields["SIG"]?.takeIf { it.isNotEmpty() }
        if (sig != null && !BASE64.matches(sig)) return null
        return PairingMessage.Request(nonce, clean(fields["NAME"] ?: ""), key, sig)
    }

    fun isPairingMessage(body: String): Boolean = parse(body) != null
}
