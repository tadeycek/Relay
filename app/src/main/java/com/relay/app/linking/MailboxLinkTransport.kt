package com.relay.app.linking

import com.relay.app.transport.mailbox.MailboxTransport
import org.json.JSONObject
import rust.nostr.sdk.Keys

/**
 * [PhoneLinkTransport] over the mailbox's one signed-request path, so the link slot and device calls
 * are NIP-98-signed exactly like every message (`MailboxAuth` + `AndroidNostrSigner`, replay-protected
 * server-side by the same event-id table).
 *
 * [keys] is the signer for these calls: the phone-only admin key once the identity has migrated,
 * the shared identity key before that — the server accepts both for `PUT /v1/link` and the legacy
 * path of `POST /v1/devices`, which is what [com.relay.app.transport.mailbox.PhoneDeviceEnrollment.authKeys]
 * returns.
 */
class MailboxLinkTransport(
    private val transport: MailboxTransport,
    private val keys: () -> Keys,
) : PhoneLinkTransport {

    override suspend fun publish(slot: String, sealed: ByteArray): LinkHttp =
        http(transport.signedRequest("PUT", "/v1/link/$slot", sealed, keys = keys()))

    override suspend fun discard(slot: String, proof: String): LinkHttp =
        http(
            transport.signedRequest(
                "DELETE",
                "/v1/link/$slot",
                headers = mapOf(LinkCode.PROOF_HEADER to proof),
                keys = keys(),
            ),
        )

    override suspend fun enrol(deviceId: String, authPubkeyHex: String, label: String): LinkHttp {
        val body = JSONObject()
            .put("v", 1)
            .put("device", deviceId)
            .put("label", label.take(LinkBundleCodec.MAX_DEVICE_LABEL))
            .put("auth_pubkey", authPubkeyHex)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return http(
            transport.signedRequest(
                "POST",
                "/v1/devices?device=primary",
                body,
                mapOf("Content-Type" to "application/json"),
                keys = keys(),
            ),
        )
    }

    /** `GET /v1/devices?device=primary`: the computers this identity has linked. */
    suspend fun listDevices(): Result<List<LinkedDevice>> {
        val response = transport.signedRequest("GET", "/v1/devices?device=primary", keys = keys())
        if (!response.ok) return Result.failure(IllegalStateException("HTTP ${response.status} ${response.errorCode ?: ""}"))
        return LinkedDeviceList.parse(response.body)
    }

    /** `DELETE /v1/devices/<id>?device=primary`: revokes one computer's access. */
    suspend fun revoke(deviceId: String): LinkHttp {
        if (!DEVICE_ID.matches(deviceId)) return LinkHttp(0)
        return http(transport.signedRequest("DELETE", "/v1/devices/$deviceId?device=primary", keys = keys()))
    }

    private fun http(response: MailboxTransport.SignedResponse) = LinkHttp(response.status, response.errorCode)

    private companion object {
        /** The server's linked-device id shape (`LINKED_DEVICE_ID`). */
        val DEVICE_ID = Regex("[0-9a-f]{32}")
    }
}
