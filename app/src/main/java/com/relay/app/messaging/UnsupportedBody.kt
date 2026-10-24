package com.relay.app.messaging

/**
 * Decides whether a decrypted body is a control/attachment type this build cannot render, so the chat
 * can show a short notice instead of raw `TYPE:...|key:value` wire text.
 *
 * The first attempt at this used `body.startsWith("TYPE:")` and swallowed every incoming reply
 * (`TYPE:REPLY|{json}`), which the chat renders as an ordinary reply — a regression of normal text
 * behaviour. The rule is therefore an allow-list: a body is only "unsupported" when it carries a
 * `TYPE:` token that no other component in this build handles. Anything this build understands, and
 * anything that merely contains the word `TYPE:` inside a sentence, is left exactly as it is.
 *
 * Pure (no Android types) so the decision is unit-tested on the JVM.
 */
object UnsupportedBody {

    const val NOTICE = "[Unsupported message type]"

    /**
     * Every `TYPE:` prefix some component in this build handles, with where it is handled. A prefix
     * missing from here becomes a notice; one that is present is left for its owner.
     */
    val KNOWN: Set<String> = setOf(
        "REPLY",                 // ReplyBody -> rendered as a reply
        "MEDIA",                 // MediaBody -> photo/video bubble
        "FILE",                  // FileBody (handled before this check, kept for safety)
        "LOCATION",              // SmsMessageParser.parseLocation -> pin bubble
        "LOCATION_REQUEST",      // IncomingClassifier.LocationRequest
        "LOCATION_DECLINED",     // IncomingClassifier.LocationDeclined
        "PUBKEY",                // SmsMessageParser.isPublicKeyMessage -> key handshake
        "READ_RECEIPT",          // IncomingClassifier.ReadReceipt
        "PING", "PONG",          // keep-alive control
        "PAIR_REQUEST", "PAIR_CONFIRM", // pairing
        "PROFILE_PHOTO", "PROFILE_PHOTO_REQUEST", // contact photos
        "CALL",                  // call signalling (TYPE:CALL|J:...)
        "SYNC",                  // own-device self-sync envelope
        "ENC",                   // sealed body, unwrapped before this point
        "P",                     // pairing payload
    )

    /**
     * `TYPE:` followed by an upper-case token and then a separator or end. Requiring upper case keeps
     * a sentence like `TYPE: this is what I meant` out of the notice path; requiring a separator stops
     * `TYPE:REPLYING` matching REPLY.
     */
    private val CONTROL = Regex("""^TYPE:([A-Z][A-Z0-9_]{0,39})(?:\||$)""")

    /** The notice to store instead of [body], or null when the body must be handled normally. */
    fun notice(body: String): String? {
        val token = CONTROL.find(body)?.groupValues?.get(1) ?: return null
        return if (token in KNOWN) null else NOTICE
    }
}
