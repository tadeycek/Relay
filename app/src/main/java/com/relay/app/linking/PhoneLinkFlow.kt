package com.relay.app.linking

import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's decisions when linking a computer (05 §4, WP05 part B).
 *
 * Everything that can go wrong is decided here, with no Android types, no clock and no network: the
 * caller supplies a [PhoneLinkTransport] (the Android glue wraps the mailbox's signed-request path in
 * one) and a [LinkHpke] (Tink on the phone). That keeps the whole matrix unit-testable on the JVM.
 *
 * The flow, in order:
 *
 * 1. parse the scanned QR ([LinkQr.decode]) — a legacy v1 code and a damaged code are refused
 *    before anything is sealed or sent;
 * 2. build the bundle (identity keys, contacts, groups, settings; never messages — enforced by
 *    [LinkBundleCodec]) and HPKE-seal it to the desktop's ephemeral key from the QR;
 * 3. `PUT /v1/link/<slot>` with the sealed bytes, `<slot>` = [LinkCode.slotId];
 * 4. enrol the desktop device with its own auth key, `POST /v1/devices?device=primary`;
 * 5. report success. **The slot is deliberately not deleted**: the desktop still has to fetch it.
 *
 * If step 4 fails, the half-linked state is rolled back by `DELETE /v1/link/<slot>` with the proof
 * header, so no sealed bundle is left waiting on the server. Only if that rollback fails too is the
 * slot left behind, and the result says so ([PhoneLinkResult.Failed.slotLeftBehind]).
 *
 * One attempt at a time: a second call while one is running returns
 * [PhoneLinkError.ALREADY_LINKING] without touching the network.
 */
class PhoneLinkFlow(
    private val transport: PhoneLinkTransport,
    private val hpke: LinkHpke,
    /** Label shown next to the computer on this phone; bounded by the server to 64 chars. */
    private val label: String = DEFAULT_LABEL,
) {

    private val running = AtomicBoolean(false)

    /**
     * Runs one link attempt. [bundle] is invoked only after the QR parsed and the flow is not
     * already busy, so a refused scan never reads the database. It may suspend (the phone reads the
     * database for it); tests pass a plain lambda.
     */
    suspend fun link(scannedText: String?, bundle: suspend () -> LinkBundle): PhoneLinkResult {
        val parsed = parse(scannedText)
        if (parsed is Parsed.Refused) return PhoneLinkResult.Failed(parsed.error, parsed.message, retryable = false)

        val ready = parsed as Parsed.Ready
        if (!running.compareAndSet(false, true)) {
            return PhoneLinkResult.Failed(
                PhoneLinkError.ALREADY_LINKING,
                "A link is already under way. Wait for it to finish.",
                retryable = false,
            )
        }
        try {
            return attempt(ready, bundle)
        } finally {
            running.set(false)
        }
    }

    private suspend fun attempt(ready: Parsed.Ready, bundle: suspend () -> LinkBundle): PhoneLinkResult {
        val sealed = try {
            LinkBundleCodec.seal(hpke, ready.ephemeralKeyBase64, bundle())
        } catch (e: LinkBundleException) {
            // The codec throws for a schema/bound violation and for a refused HPKE call; both mean
            // "nothing usable could be produced", and neither is retryable without changing the data.
            return if (e.message?.contains("seal", ignoreCase = true) == true) {
                PhoneLinkResult.Failed(
                    PhoneLinkError.SEAL_FAILED,
                    "Couldn't encrypt your details for this computer. Nothing was uploaded; try a fresh code.",
                    retryable = true,
                )
            } else {
                PhoneLinkResult.Failed(
                    PhoneLinkError.BUNDLE_TOO_LARGE,
                    "There is too much to copy at once — your contacts, groups and settings must fit 128 KiB. " +
                        "Nothing was uploaded.",
                    retryable = false,
                )
            }
        } catch (e: Exception) {
            return PhoneLinkResult.Failed(
                PhoneLinkError.SEAL_FAILED,
                "Couldn't prepare the details for this computer. Nothing was uploaded.",
                retryable = true,
            )
        }

        val published = transport.publish(ready.slot, sealed)
        if (!published.ok) {
            return when (val error = mapPublishFailure(published)) {
                PhoneLinkError.BUNDLE_TOO_LARGE -> PhoneLinkResult.Failed(
                    error,
                    "This phone has too much to copy in one go (128 KiB). Nothing was uploaded.",
                    retryable = false,
                )
                PhoneLinkError.SLOT_BUSY -> PhoneLinkResult.Failed(
                    error,
                    "A computer is already waiting to link with this phone. Finish that link, or wait " +
                        "up to ten minutes for it to expire, then try again.",
                    retryable = false,
                )
                PhoneLinkError.SLOT_EXPIRED -> PhoneLinkResult.Failed(
                    error,
                    "This link code has expired. Ask the computer to show a new one.",
                    retryable = false,
                )
                PhoneLinkError.AUTH_INVALID -> PhoneLinkResult.Failed(
                    error,
                    "Relay's server refused this phone's key. Reconnect the app, then try again.",
                    retryable = false,
                )
                PhoneLinkError.RATE_LIMITED -> PhoneLinkResult.Failed(
                    error,
                    "Too many attempts. Wait a minute, then try again.",
                    retryable = true,
                )
                PhoneLinkError.NETWORK -> PhoneLinkResult.Failed(
                    error,
                    "Couldn't reach Relay's server. Check the connection and try again. Nothing was uploaded.",
                    retryable = true,
                )
                else -> PhoneLinkResult.Failed(
                    error,
                    "Relay's server couldn't take the link (HTTP ${published.status}). Try a fresh code.",
                    retryable = published.status >= 500,
                )
            }
        }

        val enrolled = transport.enrol(ready.deviceId, ready.deviceAuthKeyHex, label)
        if (enrolled.ok) {
            return PhoneLinkResult.Linked(
                deviceId = ready.deviceId,
                slot = ready.slot,
                bundleBytes = sealed.size,
            )
        }

        val (error, message, retryable) = enrolmentFailure(enrolled)
        // A sealed bundle waiting under a code the user has been told not to use is a half-linked
        // state: erase it with the proof so the slot is not left behind.
        val discarded = transport.discard(ready.slot, ready.proof)
        return PhoneLinkResult.Failed(
            error,
            if (discarded.ok) message
            else "$message The link bundle may stay on the server for up to ten minutes before it expires.",
            retryable = retryable,
            slotLeftBehind = !discarded.ok,
        )
    }

    /** One decided outcome of `PUT /v1/link/<slot>`. */
    internal fun mapPublishFailure(http: LinkHttp): PhoneLinkError = when {
        http.status == 0 -> PhoneLinkError.NETWORK
        http.status == 409 || http.errorCode == "link_busy" -> PhoneLinkError.SLOT_BUSY
        http.status == 404 || http.status == 410 -> PhoneLinkError.SLOT_EXPIRED
        http.status == 401 -> PhoneLinkError.AUTH_INVALID
        http.status == 413 -> PhoneLinkError.BUNDLE_TOO_LARGE
        http.status == 429 -> PhoneLinkError.RATE_LIMITED
        else -> PhoneLinkError.SERVER_ERROR
    }

    private fun enrolmentFailure(http: LinkHttp): Triple<PhoneLinkError, String, Boolean> = when {
        http.status == 0 -> Triple(
            PhoneLinkError.NETWORK,
            "The computer couldn't be registered: Relay's server was unreachable. Nothing was left behind.",
            true,
        )
        http.errorCode == "device_limit" -> Triple(
            PhoneLinkError.DEVICE_LIMIT,
            "This phone already has three linked computers. Remove one, then link this one again.",
            false,
        )
        http.errorCode == "auth_key_in_use" || http.errorCode == "admin_key_in_use" -> Triple(
            PhoneLinkError.AUTH_KEY_IN_USE,
            "That computer's key is already in use. Restart Relay on the computer to get a fresh code.",
            false,
        )
        http.status == 401 -> Triple(
            PhoneLinkError.AUTH_INVALID,
            "Relay's server refused this phone's key, so the computer wasn't registered.",
            false,
        )
        else -> Triple(
            PhoneLinkError.ENROLMENT_REFUSED,
            "Relay's server wouldn't register this computer (HTTP ${http.status}).",
            http.status >= 500,
        )
    }

    // --- scanning -------------------------------------------------------------------------------

    private sealed interface Parsed {
        data class Ready(
            val slot: String,
            val proof: String,
            val deviceId: String,
            val ephemeralKeyBase64: String,
            /** The desktop's own NIP-98 key as 64 lowercase hex, which is what the server stores. */
            val deviceAuthKeyHex: String,
        ) : Parsed

        data class Refused(val error: PhoneLinkError, val message: String) : Parsed
    }

    private fun parse(scannedText: String?): Parsed {
        val raw = scannedText?.trim()
        if (raw.isNullOrEmpty() || !raw.startsWith(QR_PREFIX)) {
            return Parsed.Refused(
                PhoneLinkError.NOT_A_LINK_CODE,
                "That isn't a Relay link code. On the computer choose \"Link this computer\" and scan its code.",
            )
        }
        val payload = LinkQr.decode(raw) ?: return Parsed.Refused(
            PhoneLinkError.MALFORMED_CODE,
            "This link code is damaged or incomplete. Ask the computer to show a fresh one.",
        )
        if (!payload.isLinkable) {
            return Parsed.Refused(
                PhoneLinkError.LEGACY_CODE,
                "This computer is showing an older link code that a phone can't link. Update Relay on the " +
                    "computer and scan the new code.",
            )
        }
        val deviceId = payload.deviceId!!
        val ephemeral = payload.ephemeralPublicKey!!
        val authKeyHex = pubkeyHex(payload.deviceAuthPublicKey!!) ?: return Parsed.Refused(
            PhoneLinkError.BAD_DEVICE_KEY,
            "This link code carries an unusable computer key. Update Relay on the computer and scan a " +
                "fresh code.",
        )
        val ephemeralBase64 = LinkQrKeys.toStandardBase64(ephemeral) ?: return Parsed.Refused(
            PhoneLinkError.MALFORMED_CODE,
            "This link code carries an unusable encryption key. Ask the computer to show a fresh one.",
        )
        return Parsed.Ready(
            slot = LinkCode.slotId(payload.code),
            proof = LinkCode.proofValue(payload.code),
            deviceId = deviceId,
            ephemeralKeyBase64 = ephemeralBase64,
            deviceAuthKeyHex = authKeyHex,
        )
    }

    /** base64url(32 raw bytes) → 64 lowercase hex; a keyset blob or any other length is refused. */
    private fun pubkeyHex(base64Url: String): String? {
        val bytes = runCatching { Base64.getUrlDecoder().decode(base64Url) }.getOrNull() ?: return null
        if (bytes.size != 32) return null
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_LABEL = "Computer"

        private const val QR_PREFIX = "RELAYLINK:"
    }
}

/**
 * One HTTP outcome as the flow sees it. `status == 0` means the request never got an answer (no
 * route, DNS failure, timeout) and [errorCode] is the server's `error.code` when it answered.
 */
data class LinkHttp(val status: Int, val errorCode: String? = null) {
    val ok: Boolean get() = status in 200..299
}

/** The three signed calls the phone makes while linking. Implemented over the mailbox transport. */
interface PhoneLinkTransport {
    /** `PUT /v1/link/<slot>`: parks the sealed bundle under the one-time code. */
    suspend fun publish(slot: String, sealed: ByteArray): LinkHttp

    /** `DELETE /v1/link/<slot>` with the proof header: erases a bundle that must not be used. */
    suspend fun discard(slot: String, proof: String): LinkHttp

    /** `POST /v1/devices?device=primary`: binds the desktop's id and its own auth key. */
    suspend fun enrol(deviceId: String, authPubkeyHex: String, label: String): LinkHttp
}

enum class PhoneLinkError {
    /** This build has no Relay delivery server, so there is nowhere to park a link. */
    NOT_CONFIGURED,

    /** The scan wasn't a Relay link QR at all. */
    NOT_A_LINK_CODE,

    /** It had the right prefix but a field was damaged. */
    MALFORMED_CODE,

    /** A version-1 QR: code only, no desktop keys, so it cannot be linked. */
    LEGACY_CODE,

    /** A link attempt is already running on this phone. */
    ALREADY_LINKING,

    /** The QR's device key is not a 32-byte public key. */
    BAD_DEVICE_KEY,

    /** Contacts + groups + settings (or the sealed form) do not fit the 128 KiB slot. */
    BUNDLE_TOO_LARGE,

    /** Another slot for this identity is live (`409 link_busy`). */
    SLOT_BUSY,

    /** The desktop's slot is gone or expired (`404` / `410`). */
    SLOT_EXPIRED,

    /** The server refused this phone's key (`401 auth_invalid` / `auth_revoked`). */
    AUTH_INVALID,

    RATE_LIMITED,

    /** Three computers are already linked (`409 device_limit`). */
    DEVICE_LIMIT,

    /** The desktop's key is bound elsewhere (`409 auth_key_in_use`). */
    AUTH_KEY_IN_USE,

    ENROLMENT_REFUSED,

    /** No answer from the server. */
    NETWORK,

    SERVER_ERROR,

    /** HPKE refused to seal the bundle. */
    SEAL_FAILED,
}

sealed interface PhoneLinkResult {
    /** The bundle is waiting under the code and the desktop device is enrolled. */
    data class Linked(val deviceId: String, val slot: String, val bundleBytes: Int) : PhoneLinkResult

    data class Failed(
        val error: PhoneLinkError,
        val message: String,
        val retryable: Boolean,
        /** The enrollment failed and the slot could not be erased; it expires within ten minutes. */
        val slotLeftBehind: Boolean = false,
    ) : PhoneLinkResult
}

/**
 * The two base64 spellings the link QR uses, kept in one place so they are testable on the JVM:
 * the QR is base64url (header/QR safe) while Tink and the mailbox take standard base64.
 */
object LinkQrKeys {
    fun toStandardBase64(base64Url: String): String? {
        val bytes = runCatching { Base64.getUrlDecoder().decode(base64Url) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return Base64.getEncoder().encodeToString(bytes)
    }
}
