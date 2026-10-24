package com.relay.app.desktop

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.config.TinkConfig
import com.relay.app.linking.LinkBundleCodec
import com.relay.app.linking.LinkHpke
import com.relay.app.linking.LinkQr
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The desktop's link key material, before and after it becomes a linked device.
 *
 * Two key pairs, both generated on this computer and both private halves sealed with the
 * [SecretStore] master key in [DesktopPaths.linkFile] (mode 0600):
 *
 *  - **ephemeral HPKE key** — Tink `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM`, the same
 *    template as [DesktopKeyMaterial]. The phone seals the link bundle to it. It is not part of any
 *    identity keyset and works before an identity exists.
 *  - **device auth key** — Ed25519 via rust-nostr `Keys`. The phone enrols its public key with the
 *    server (`auth_pubkey`); after that this computer signs every mailbox request with it. The wire
 *    value in the QR is the 64-hex Nostr public key, which is also a legal unpadded-base64url token
 *    for [LinkQr]'s field and exactly what the server wants for `auth_pubkey`.
 *
 * No private key is ever logged, stringified or put in an exception message: [toString] is
 * overridden on purpose and [DesktopLinkSession] exposes only public values.
 */
class DesktopLinkSession internal constructor(
    /** Lowercase hex, the id the phone enrols and every device request carries as `?device=`. */
    val deviceId: String,
    val deviceLabel: String,
    /** Unpadded base64url of the Tink **public** keyset — the QR's ephemeral-key field. */
    val ephemeralPublicKeyBase64Url: String,
    /** 64 lowercase hex Ed25519 public key — QR device-auth field and server `auth_pubkey`. */
    val deviceAuthPublicKeyHex: String,
    /** Cleartext Tink **private** keyset JSON. In memory only; sealed on disk by [DesktopLinkKeys]. */
    internal val ephemeralPrivateKeysetJson: String,
    /** 64 lowercase hex Ed25519 secret key. In memory only; sealed on disk by [DesktopLinkKeys]. */
    internal val deviceAuthSecretHex: String,
) {
    /** True when this id has the 32-hex shape the server's `devices` table accepts. */
    val isServerDeviceId: Boolean get() = SERVER_DEVICE_ID.matches(deviceId)

    override fun toString(): String =
        "DesktopLinkSession(deviceId=$deviceId, device=$deviceLabel, authPub=${deviceAuthPublicKeyHex.take(8)}…)"

    companion object {
        /** Server `LINKED_DEVICE_ID` (`server/cloudflare/src/protocol.ts`): 32 lowercase hex. */
        val SERVER_DEVICE_ID = Regex("[0-9a-f]{32}")
    }
}

/**
 * Loads, creates and seals the desktop link keys. [loadOrCreate] is stable: a QR rendered before a
 * restart still opens the bundle the phone sealed to it, because the key file is reused rather than
 * regenerated.
 *
 * The file also records the link result ([markLinked]) so a restart knows this computer is a linked
 * device and which identity it belongs to.
 */
class DesktopLinkKeys(
    private val paths: DesktopPaths,
    private val secrets: SecretStore,
    private val random: SecureRandom = SecureRandom(),
) {

    private val lock = Any()

    @Volatile private var cached: LinkFile? = null

    /** The recorded link state, or null when this install never started a link. */
    fun load(): LinkFile? = synchronized(lock) {
        cached?.let { return it }
        val raw = DesktopPaths.readPrivateFile(paths.linkFile) ?: return null
        decode(raw).also { cached = it }
    }

    /** The current link session, generating both key pairs and a device id on first use. */
    fun loadOrCreate(deviceLabel: String = defaultDeviceLabel()): DesktopLinkSession = synchronized(lock) {
        val file = load() ?: newFile(deviceLabel).also { persist(it) }
        file.session
    }

    /** Records a durable link. Called only after the identity and store commit succeeded. */
    fun markLinked(identityPubkeyHex: String, linkedAt: Long) = synchronized(lock) {
        val file = load() ?: error("No link keys to mark as linked")
        val next = file.copy(identityPubkey = identityPubkeyHex, linkedAt = linkedAt)
        persist(next)
        cached = next
    }

    /** Non-null once this computer completed a link. */
    fun linkedDevice(): LinkedDevice? = load()?.let { file ->
        val pub = file.identityPubkey ?: return null
        LinkedDevice(
            identityPubkeyHex = pub,
            deviceId = file.session.deviceId,
            deviceLabel = file.session.deviceLabel,
            linkedAt = file.linkedAt,
        )
    }

    /** Wipes the link file (start over after an aborted link). */
    fun clear() = synchronized(lock) {
        java.nio.file.Files.deleteIfExists(paths.linkFile)
        cached = null
    }

    data class LinkFile(
        val session: DesktopLinkSession,
        val identityPubkey: String? = null,
        val linkedAt: Long = 0,
    )

    private fun newFile(deviceLabel: String): LinkFile {
        TinkConfig.register()
        val ephemeral = KeysetHandle.generateNew(KeyTemplates.get(DesktopKeyMaterial.TEMPLATE))
        val publicBytes = ByteArrayOutputStream().also {
            ephemeral.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(it))
        }.toByteArray()
        val privateJson = ByteArrayOutputStream().also {
            CleartextKeysetHandle.write(ephemeral, JsonKeysetWriter.withOutputStream(it))
        }.toString(Charsets.UTF_8)
        val authSecretHex = rust.nostr.sdk.Keys.generate().secretKey().toHex()
        val authPubHex = rust.nostr.sdk.Keys.parse(authSecretHex).publicKey().toHex()
        val deviceId = newDeviceId()

        return LinkFile(
            session = DesktopLinkSession(
                deviceId = deviceId,
                deviceLabel = deviceLabel.take(LinkBundleCodec.MAX_DEVICE_LABEL),
                ephemeralPublicKeyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(publicBytes),
                deviceAuthPublicKeyHex = authPubHex,
                ephemeralPrivateKeysetJson = privateJson,
                deviceAuthSecretHex = authSecretHex,
            ),
        )
    }

    private fun persist(file: LinkFile) {
        val master = secrets.getOrCreateKey(LINK_MASTER_KEY_ID)
        val envelope = JSONObject()
            .put("v", 1)
            .put("deviceId", file.session.deviceId)
            .put("deviceLabel", file.session.deviceLabel)
            .put("ephemeral", seal(file.session.ephemeralPrivateKeysetJson, master))
            .put("auth", seal(file.session.deviceAuthSecretHex, master))
        file.identityPubkey?.let { envelope.put("identityPubkey", it) }
        if (file.linkedAt > 0) envelope.put("linkedAt", file.linkedAt)
        DesktopPaths.writePrivateFile(paths.linkFile, envelope.toString().toByteArray(Charsets.UTF_8))
        require(DesktopPaths.isPrivate(paths.linkFile)) { "Could not set 0600 on ${paths.linkFile}" }
    }

    private fun decode(raw: ByteArray): LinkFile {
        val envelope = JSONObject(raw.toString(Charsets.UTF_8))
        require(envelope.optInt("v", -1) == 1) { "Unsupported link file version" }
        val master = secrets.getOrCreateKey(LINK_MASTER_KEY_ID)
        val ephemeralJson = open(envelope.getString("ephemeral"), master)
        val authSecretHex = open(envelope.getString("auth"), master)
        val authKeys = rust.nostr.sdk.Keys.parse(authSecretHex)
        val ephemeral = CleartextKeysetHandle.read(JsonKeysetReader.withString(ephemeralJson))
        val publicBytes = ByteArrayOutputStream().also {
            ephemeral.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(it))
        }.toByteArray()
        return LinkFile(
            session = DesktopLinkSession(
                deviceId = envelope.getString("deviceId"),
                deviceLabel = envelope.optString("deviceLabel"),
                ephemeralPublicKeyBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(publicBytes),
                deviceAuthPublicKeyHex = authKeys.publicKey().toHex(),
                ephemeralPrivateKeysetJson = ephemeralJson,
                deviceAuthSecretHex = authSecretHex,
            ),
            identityPubkey = envelope.optString("identityPubkey").takeIf { it.isNotEmpty() },
            linkedAt = envelope.optLong("linkedAt", 0),
        )
    }

    /** AES-256-GCM, random 12-byte IV; output = base64(iv || ciphertext+tag). */
    private fun seal(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    private fun open(sealedBase64: String, key: ByteArray): String {
        val all = Base64.getDecoder().decode(sealedBase64)
        require(all.size > 12) { "Sealed link key material is too short" }
        val iv = all.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }

    /** [LinkQr.DEVICE_ID_CHARS] random hex characters. */
    private fun newDeviceId(): String {
        val bytes = ByteArray(LinkQr.DEVICE_ID_CHARS / 2).also(random::nextBytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val LINK_MASTER_KEY_ID = "link"
        private val AAD = "relay-desktop-link-v1".toByteArray(Charsets.UTF_8)

        /** Best-effort label ("host os"), bounded by the bundle schema. */
        fun defaultDeviceLabel(): String {
            val host = System.getenv("HOSTNAME")?.trim().orEmpty()
                .ifEmpty { runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("") }
            val os = System.getProperty("os.name").orEmpty()
            return listOf(host, os).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Desktop" }
                .take(LinkBundleCodec.MAX_DEVICE_LABEL)
        }
    }
}

/** A completed link as recorded on disk. */
data class LinkedDevice(
    val identityPubkeyHex: String,
    val deviceId: String,
    val deviceLabel: String,
    val linkedAt: Long,
)

/**
 * [LinkHpke] over the desktop's ephemeral link key. [LinkBundleCodec.open] passes the private key
 * as base64 text; this implementation accepts that form (the cleartext Tink JSON keyset), never
 * writing it anywhere.
 */
class DesktopLinkHpke(private val privateKeysetJson: () -> String) : LinkHpke {

    override fun encryptTo(recipientPublicKeyBase64: String, plaintext: ByteArray): ByteArray? = try {
        val bytes = try {
            Base64.getUrlDecoder().decode(recipientPublicKeyBase64)
        } catch (e: IllegalArgumentException) {
            Base64.getDecoder().decode(recipientPublicKeyBase64)
        }
        KeysetHandle.readNoSecret(BinaryKeysetReader.withBytes(bytes))
            .getPrimitive(HybridEncrypt::class.java)
            .encrypt(plaintext, ByteArray(0))
    } catch (e: Exception) {
        null
    }

    override fun decryptWith(recipientPrivateKeyBase64: String, ciphertext: ByteArray): ByteArray? = try {
        val json = String(Base64.getDecoder().decode(recipientPrivateKeyBase64), Charsets.UTF_8)
        CleartextKeysetHandle.read(JsonKeysetReader.withString(json))
            .getPrimitive(HybridDecrypt::class.java)
            .decrypt(ciphertext, ByteArray(0))
    } catch (e: Exception) {
        null
    }
}
