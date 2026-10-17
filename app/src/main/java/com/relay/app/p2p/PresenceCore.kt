package com.relay.app.p2p

import java.security.SecureRandom

/**
 * Rules and wire format for an on-demand "are you online, and how do I reach you" check — the first
 * step toward a direct phone-to-phone photo/video transfer that never touches a media server. Pure so
 * it is unit-tested without Android; see [com.relay.app.p2p.PresenceCoordinator] for the runtime.
 *
 * This is deliberately on-demand rather than a standing presence broadcast: nothing about being online
 * is revealed to anyone until they are actively trying to send something, the same way a location
 * request already works, rather than a persistent "online" status leaking to every contact all the time.
 */
object PresenceRules {
    /** How long to wait for a Pong before giving up and treating the contact as offline. */
    const val TIMEOUT_MS = 8_000L

    /**
     * How long a phone that answered a Ping still honours it — separate from [TIMEOUT_MS], which only
     * governs how long the *pinger* waits for a reply. Someone who sees "online" needs time to pick a
     * photo and hit send, not just the few seconds a liveness check itself takes.
     */
    const val PONGED_TTL_MS = 5 * 60_000L

    fun isExpired(deadlineMs: Long, nowMs: Long): Boolean = nowMs > deadlineMs
}

sealed class PresenceMessage {
    abstract val nonce: String

    /** "Are you there, and if so, how do I reach you directly?" */
    data class Ping(override val nonce: String) : PresenceMessage()

    /** "Yes — try me at one of these." Empty when nothing reachable could be found. */
    data class Pong(override val nonce: String, val candidates: List<String>) : PresenceMessage()
}

object PresenceMessages {
    private val NONCE = Regex("""[0-9a-f]{32}""")
    /** host:port, IPv4 or IPv6-in-brackets, e.g. "192.168.1.12:41210" or "[fe80::1]:41210". */
    private val CANDIDATE = Regex("""^(\[[0-9a-fA-F:]+]|[0-9.]+):(\d{1,5})$""")
    private const val MAX_CANDIDATES = 6

    fun randomNonce(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidNonce(s: String?): Boolean = s != null && NONCE.matches(s)

    fun isValidCandidate(s: String): Boolean {
        if (s.length > 64) return false
        val port = CANDIDATE.matchEntire(s)?.groupValues?.get(2)?.toIntOrNull() ?: return false
        return port in 1..65535
    }

    fun formatPing(nonce: String): String = "TYPE:PING|NONCE:$nonce"

    fun formatPong(nonce: String, candidates: List<String>): String {
        val clean = candidates.filter { isValidCandidate(it) }.distinct().take(MAX_CANDIDATES)
        var msg = "TYPE:PONG|NONCE:$nonce"
        if (clean.isNotEmpty()) msg += "|ADDR:" + clean.joinToString(",")
        return msg
    }

    /** Null unless [body] is a well-formed presence message. Never throws on hostile input. */
    fun parse(body: String): PresenceMessage? {
        val parts = body.trim().split('|')
        val type = parts.firstOrNull()?.takeIf { it.startsWith("TYPE:") }?.removePrefix("TYPE:") ?: return null
        if (type != "PING" && type != "PONG") return null
        val fields = HashMap<String, String>()
        for (p in parts.drop(1)) {
            val i = p.indexOf(':')
            if (i <= 0) return null
            fields[p.substring(0, i)] = p.substring(i + 1)
        }
        val nonce = fields["NONCE"]?.takeIf { NONCE.matches(it) } ?: return null
        if (type == "PING") return PresenceMessage.Ping(nonce)

        val candidates = fields["ADDR"]?.split(',').orEmpty()
            .filter { it.isNotEmpty() && isValidCandidate(it) }
            .take(MAX_CANDIDATES)
        return PresenceMessage.Pong(nonce, candidates)
    }

    fun isPresenceMessage(body: String): Boolean = parse(body) != null
}
