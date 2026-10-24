package com.relay.app.desktop

import com.relay.app.linking.LinkBundle
import com.relay.app.linking.LinkBundleCodec
import com.relay.app.linking.LinkCode
import com.relay.app.linking.LinkError
import com.relay.app.linking.LinkHpke
import com.relay.app.linking.LinkQr
import com.relay.app.linking.LinkResult
import kotlinx.coroutines.delay
import java.util.Base64

/**
 * The desktop half of WP05 part B, as a pure state machine: no Compose, no UI types, no real clock,
 * no real socket. Everything with a side effect is a seam ([LinkHttp], [LinkKeyProvider], [LinkClock],
 * [LinkImporter]), so every branch below is unit-tested with fakes and the same code runs in the app.
 *
 * Sequence (05 §4, PROTOCOL.md "Link slot"):
 *
 *  1. generate the desktop's ephemeral HPKE key pair and device-auth key pair (persisted 0600 by the
 *     key provider), a device id and a 32-byte [LinkCode];
 *  2. show `LinkQr.encode(code, deviceId, ephemeralPub, deviceAuthPub)`;
 *  3. poll `GET /v1/link/<sha256(code)>` with `x-relay-link-proof: <code>` until it returns bytes or
 *     the 10-minute TTL runs out;
 *  4. open the sealed bundle with the ephemeral private key ([LinkBundleCodec.open]);
 *  5. import the bundle durably (identity, contacts, groups, settings) — idempotently;
 *  6. only then `DELETE /v1/link/<slot>` to consume the slot. A failed commit leaves the slot in
 *     place, so the user can simply retry: GET is a lease, not a delete-on-read.
 *
 * The one-time code is proof of retrieval, never a key: it is not passed to HPKE and never written
 * to a log. No state produced here contains a private key; [LinkState] only carries public values.
 */
class DesktopLinkFlow(
    private val http: LinkHttp,
    private val hpke: LinkHpke,
    private val keys: LinkKeyProvider,
    private val importer: LinkImporter,
    private val clock: LinkClock = SystemLinkClock,
    /** How long the QR is good for; the server's slot TTL is 10 minutes. */
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
    /** Injectable so a deterministic harness can pin the code; production uses SecureRandom. */
    private val codeSource: () -> String = { LinkCode.generate() },
    /** Injectable so a test can make the durable commit fail once. */
    private val deviceLabelOverride: String? = null,
) {

    private var session: DesktopLinkSession? = null
    private var code: String = ""
    private var slot: String = ""
    private var expiresAt: Long = 0
    private var attempt: Int = 0

    /** The QR text to render, or null before [begin]. */
    var qrText: String? = null
        private set

    /** Human-grouped code, for the manual fallback path. */
    var humanCode: String? = null
        private set

    val deviceId: String? get() = session?.deviceId

    val deviceLabel: String? get() = session?.deviceLabel

    /** True when a bundle has been durably imported. */
    var committed: Boolean = false
        private set

    /** True when the slot was consumed with DELETE after the commit. */
    var slotCompleted: Boolean = false
        private set

    /**
     * Generates the desktop key pairs (loads them if a previous attempt already made some) and the
     * link code, and returns the QR state. Calling it twice re-renders the same QR.
     */
    fun begin(): LinkState {
        val active = session ?: keys.loadOrCreate(deviceLabelOverride).also { session = it }
        if (code.isEmpty()) {
            code = codeSource()
            slot = LinkCode.slotId(code)
            expiresAt = clock.now() + ttlMs
            humanCode = LinkCode.human(code)
            qrText = LinkQr.encode(
                code = code,
                deviceId = active.deviceId,
                ephemeralPublicKey = active.ephemeralPublicKeyBase64Url,
                deviceAuthPublicKey = active.deviceAuthPublicKeyHex,
            )
        }
        return LinkState.Showing(
            deviceId = active.deviceId,
            deviceLabel = active.deviceLabel,
            expiresAt = expiresAt,
        )
    }

    /**
     * Polls once. Returns [LinkState.Waiting] while the slot is still empty, a terminal state on
     * success/expiry/failure. Safe to call in a loop; every call re-reads the slot (the server's GET
     * is repeatable until DELETE).
     */
    suspend fun step(): LinkState {
        val active = session ?: return LinkState.Failed(LinkFailure.NOT_STARTED, "Start the link first.")
        if (code.isEmpty()) return LinkState.Failed(LinkFailure.NOT_STARTED, "Start the link first.")
        if (clock.now() >= expiresAt) return LinkState.Expired(expiresAt)

        attempt++
        return when (val response = http.get(slot, LinkCode.proofValue(code))) {
            is LinkHttp.Response.Body -> accept(response.bytes, active)
            is LinkHttp.Response.Refused -> when (response.status) {
                404 -> {
                    // The slot only exists after the phone publishes it, so an early 404 is normal.
                    if (clock.now() >= expiresAt) LinkState.Expired(expiresAt)
                    else LinkState.Waiting(attempt, expiresAt)
                }
                401, 403 -> LinkState.Failed(
                    LinkFailure.WRONG_CODE,
                    "The server didn't accept this code. Show a new code on this computer and scan it again.",
                )
                429 -> LinkState.Failed(
                    LinkFailure.RATE_LIMITED,
                    "Too many link attempts from this network. Wait a minute, then show a new code.",
                )
                else -> LinkState.Failed(
                    LinkFailure.SERVER_UNREACHABLE,
                    "The mailbox answered ${response.status}${response.code?.let { " ($it)" } ?: ""}.",
                )
            }
            is LinkHttp.Response.Unreachable -> {
                if (clock.now() >= expiresAt) LinkState.Expired(expiresAt)
                else LinkState.Waiting(attempt, expiresAt)
            }
        }
    }

    /** Polls until a terminal state, honouring the TTL. */
    suspend fun await(): LinkState {
        begin()
        while (true) {
            when (val state = step()) {
                is LinkState.Waiting -> clock.sleep(pollIntervalMs)
                else -> return state
            }
        }
    }

    private suspend fun accept(sealed: ByteArray, active: DesktopLinkSession): LinkState {
        val opened = LinkBundleCodec.open(hpke, privateKeyBase64(active), sealed)
        val bundle = when (opened) {
            is LinkResult.Err -> return failureFor(opened.error, opened.detail)
            is LinkResult.Ok -> opened.bundle
        }

        val report = try {
            importer.import(bundle, DesktopLinkDevice(active.deviceId, active.deviceLabel, active.deviceAuthPublicKeyHex))
        } catch (e: LinkImportException) {
            // The durable commit did not happen. Do NOT consume the slot: GET is a lease, so the
            // user can retry and the bundle is still there.
            return LinkState.Failed(e.failure, e.message ?: "Couldn't finish linking on this computer.")
        } catch (e: Exception) {
            // Never echo an exception message here: it can contain local paths or key fragments.
            return LinkState.Failed(
                LinkFailure.COMMIT_FAILED,
                "Couldn't save the linked account on this computer (${e.javaClass.simpleName}). " +
                    "The code is still valid — try again.",
            )
        }

        committed = true
        // Completion is best-effort: the keys are already durable, so a 404/network failure here
        // only means the phone's slot will expire on its own.
        val completion = runCatching { http.delete(slot, LinkCode.proofValue(code)) }.getOrNull()
        // DELETE answers 200 with a small JSON body; DesktopLinkHttp maps any 200 body to Body.
        slotCompleted = completion is LinkHttp.Response.Body

        return LinkState.Linked(
            identityPubkeyHex = report.identityPubkeyHex,
            identityLabel = bundle.identity.deviceLabel.ifBlank { "your phone" },
            deviceId = active.deviceId,
            deviceLabel = active.deviceLabel,
            contactsInserted = report.contactsInserted,
            contactsUpdated = report.contactsUpdated,
            groups = report.groups,
            settingsApplied = report.settingsApplied,
            slotCompleted = slotCompleted,
        )
    }

    /**
     * Base64 of the ephemeral **private** Tink keyset JSON, the argument [LinkBundleCodec.open]
     * passes to [LinkHpke.decryptWith]. It lives in memory only and is never logged.
     */
    private fun privateKeyBase64(session: DesktopLinkSession): String = Base64.getEncoder()
        .encodeToString(session.ephemeralPrivateKeysetJson.toByteArray(Charsets.UTF_8))

    private fun failureFor(error: LinkError, detail: String?): LinkState.Failed = when (error) {
        LinkError.CRYPTO_FAILED -> LinkState.Failed(
            LinkFailure.TAMPERED_BUNDLE,
            "The bundle didn't decrypt. It was sealed to a different computer's key or was modified in transit.",
        )
        LinkError.HISTORY_FORBIDDEN -> LinkState.Failed(
            LinkFailure.HISTORY_PRESENT,
            "The phone tried to send message history. Linking carries only keys, contacts, groups and settings.",
        )
        LinkError.UNSUPPORTED_VERSION, LinkError.UNSUPPORTED_SUITE -> LinkState.Failed(
            LinkFailure.VERSION_MISMATCH,
            "This bundle comes from a newer Relay version than this computer understands (${detail ?: error.name}).",
        )
        LinkError.TOO_LARGE -> LinkState.Failed(
            LinkFailure.BUNDLE_TOO_LARGE,
            "The bundle is larger than the 128 KiB link limit.",
        )
        LinkError.BAD_MAGIC, LinkError.TRUNCATED, LinkError.TRAILING_BYTES,
        LinkError.MALFORMED, LinkError.FIELD_TOO_LARGE,
        -> LinkState.Failed(
            LinkFailure.CORRUPT_BUNDLE,
            "The bundle is corrupt (${error.name}). Ask the phone to show a new link code.",
        )
    }

    companion object {
        /** The server's `LINK_TTL_MS` for a slot. */
        const val DEFAULT_TTL_MS = 10L * 60 * 1000L
        const val DEFAULT_POLL_MS = 1_500L
    }
}

/** What went wrong, in terms a person can act on. */
enum class LinkFailure {
    NOT_STARTED,
    /** The server rejected the code (401/403) — the scan was of a different/permanent code. */
    WRONG_CODE,
    RATE_LIMITED,
    SERVER_UNREACHABLE,
    /** Failed the envelope or JSON schema checks. */
    CORRUPT_BUNDLE,
    /** Failed HPKE: wrong ephemeral key or modified ciphertext. */
    TAMPERED_BUNDLE,
    /** The producer smuggled message history into a bundle (LP-D06). */
    HISTORY_PRESENT,
    VERSION_MISMATCH,
    BUNDLE_TOO_LARGE,
    /** The durable local commit failed; the slot was left unconsumed so a retry can work. */
    COMMIT_FAILED,
    /** This computer already belongs to a different Relay identity. */
    ALREADY_LINKED,
}

sealed interface LinkState {
    /** The QR is on screen (and the slot is being polled). */
    data class Showing(val deviceId: String, val deviceLabel: String, val expiresAt: Long) : LinkState

    /** Polled; nothing published to the slot yet (or a transient network failure). */
    data class Waiting(val attempts: Int, val expiresAt: Long) : LinkState

    data class Expired(val expiredAt: Long) : LinkState

    data class Linked(
        val identityPubkeyHex: String,
        val identityLabel: String,
        val deviceId: String,
        val deviceLabel: String,
        val contactsInserted: Int,
        val contactsUpdated: Int,
        val groups: Int,
        val settingsApplied: Boolean,
        val slotCompleted: Boolean,
    ) : LinkState

    data class Failed(val failure: LinkFailure, val message: String) : LinkState
}

/** The two unauthenticated slot calls. GET and DELETE both carry the proof header. */
interface LinkHttp {

    suspend fun get(slot: String, proof: String): Response

    suspend fun delete(slot: String, proof: String): Response

    sealed interface Response {
        /** 200 with the sealed bundle bytes. */
        class Body(val bytes: ByteArray) : Response

        /** Any non-2xx answer. [code] is the server's `error.code` when it had one. */
        class Refused(val status: Int, val code: String? = null) : Response

        /** Connection failure/timeout. */
        class Unreachable(val reason: String? = null) : Response
    }
}

/** Clock seam so the TTL and retry cadence are testable without sleeping. */
interface LinkClock {
    fun now(): Long
    suspend fun sleep(ms: Long)
}

object SystemLinkClock : LinkClock {
    override fun now(): Long = System.currentTimeMillis()
    override suspend fun sleep(ms: Long) = delay(ms)
}

/** Key material seam: production is [DesktopLinkKeys], tests use an in-memory implementation. */
interface LinkKeyProvider {
    fun loadOrCreate(deviceLabel: String?): DesktopLinkSession
}

/** Durable import seam: production is [DesktopStoreLinkImporter]. */
interface LinkImporter {
    /**
     * Commits the bundle and the device enrolment. Must be idempotent: importing the same bundle
     * twice inserts nothing twice and does not overwrite newer local settings. Throws
     * [LinkImportException] for a bundle that must not be imported and any other exception for an
     * I/O failure (both leave the slot unconsumed).
     */
    fun import(bundle: LinkBundle, device: DesktopLinkDevice): LinkImportReport
}

/** What the importer did, for the "Linked to …" screen and for tests. */
data class LinkImportReport(
    val identityPubkeyHex: String,
    val contactsInserted: Int,
    val contactsUpdated: Int,
    val groups: Int,
    val settingsApplied: Boolean,
)

/** The enrolled device as the server will see it. */
data class DesktopLinkDevice(
    val deviceId: String,
    val deviceLabel: String,
    val authPublicKeyHex: String,
)

/** A bundle that must be refused; [failure] is the user-facing reason. */
class LinkImportException(val failure: LinkFailure, message: String) : Exception(message)
