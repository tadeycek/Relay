package com.relay.app.desktop

import com.relay.app.transport.RelayPayload
import com.relay.app.transport.mailbox.NostrSigner
import org.json.JSONObject
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Keys
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.UnwrappedGift
import rust.nostr.sdk.giftWrap
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Nostr identity for this desktop install. WP06 deliberately does NOT implement phone linking
 * (that is WP05 part B): the identity is generated locally and the link screen is a seam. When
 * linking lands, the same file will be replaced with the phone-exported bundle and the rest of the
 * stack (signer, mailbox auth, gift wraps) needs no change.
 *
 * The secret key is sealed with the [SecretStore] master key and the sealed file is 0600. The
 * secret key is never logged or written in clear.
 */
class DesktopIdentity(
    private val paths: DesktopPaths,
    private val secrets: SecretStore,
    /**
     * Optional pre-generated keys. Tests inject an identity here instead of persisting one; the
     * phone-link import ([DesktopStoreLinkImporter]) installs the phone's keys with [adopt].
     */
    private val injectedKeys: Keys? = null,
    /** When supplied, [isLocalOnly] reflects whether this computer completed a phone link. */
    private val linkKeys: DesktopLinkKeys? = null,
) {
    private val lock = Any()

    @Volatile private var keys: Keys? = null

    /** This install's Nostr keys. Uses [injectedKeys] when supplied, else loads or generates one. */
    fun keys(): Keys = synchronized(lock) {
        keys?.let { return it }
        injectedKeys?.let {
            keys = it
            return it
        }
        val raw = DesktopPaths.readPrivateFile(paths.identityFile)
        val loaded = if (raw == null) {
            val fresh = Keys.generate()
            persist(fresh)
            fresh
        } else {
            decode(raw)
        }
        keys = loaded
        loaded
    }

    fun publicKeyHex(): String = keys().publicKey().toHex()

    /**
     * Installs the identity the phone exported in a link bundle: the same Nostr secret, sealed in
     * this install's [SecretStore] and written 0600. Idempotent — adopting the same secret twice is
     * a rewrite of the same value; adopting a different one replaces this install's identity, which
     * is what "link this computer" means.
     */
    fun adopt(secretHex: String) = synchronized(lock) {
        val adopted = Keys.parse(secretHex)
        persist(adopted)
        keys = adopted
    }

    /** True when this install has never been linked to a phone (its identity was generated here). */
    fun isLocalOnly(): Boolean = linkKeys?.linkedDevice() == null

    fun signer(): NostrSigner = DesktopNostrSigner(keys())

    private fun decode(raw: ByteArray): Keys {
        val envelope = JSONObject(raw.toString(Charsets.UTF_8))
        require(envelope.optInt("v", -1) == 1) { "Unsupported identity file version" }
        val master = secrets.getOrCreateKey(IDENTITY_KEY_ID)
        val secretHex = open(envelope.getString("sealed"), master)
        return Keys.parse(secretHex)
    }

    private fun persist(keys: Keys) {
        val master = secrets.getOrCreateKey(IDENTITY_KEY_ID)
        val envelope = JSONObject()
            .put("v", 1)
            .put("sealed", seal(keys.secretKey().toHex(), master))
        DesktopPaths.writePrivateFile(paths.identityFile, envelope.toString().toByteArray(Charsets.UTF_8))
        require(DesktopPaths.isPrivate(paths.identityFile)) { "Could not set 0600 on ${paths.identityFile}" }
    }

    private fun seal(plaintext: String, key: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    private fun open(sealedBase64: String, key: ByteArray): String {
        val all = Base64.getDecoder().decode(sealedBase64)
        require(all.size > 12) { "Sealed identity is too short" }
        val iv = all.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    }

    companion object {
        private const val IDENTITY_KEY_ID = "identity"
        private val AAD = "relay-desktop-identity-v1".toByteArray(Charsets.UTF_8)
    }
}

/** [NostrSigner] over rust-nostr's JVM bindings. Builds the same kind-27235 NIP-98 event as the phone. */
class DesktopNostrSigner(private val keys: Keys) : NostrSigner {
    override fun signEventJson(kind: Int, content: String, tags: List<List<String>>): String {
        val event = EventBuilder(Kind(kind.toUShort()), content)
            .tags(tags.map { Tag.parse(it) })
            .signWithKeys(keys)
        return event.asJson()
    }
}

/** NIP-17 gift wrap/unwrap using the same rust-nostr API as the phone's MailboxTransport. */
object DesktopGiftWrap {

    private const val GIFT_WRAP_KIND: UShort = 1059u
    private const val CHAT_MESSAGE_KIND: UShort = 14u

    suspend fun wrap(keys: Keys, recipientHex: String, payload: RelayPayload): String {
        val receiver = PublicKey.parse(recipientHex)
        val rumor = EventBuilder.privateMsgRumor(receiver, com.relay.app.transport.PayloadCodec.encode(payload))
            .build(keys.publicKey())
        return giftWrap(rust.nostr.sdk.NostrSigner.keys(keys), receiver, rumor, emptyList()).asJson()
    }

    /** Returns sender pubkey + payload, or null when the envelope is not for us / malformed. */
    suspend fun unwrap(keys: Keys, envelope: String): Pair<String, RelayPayload>? = try {
        val event = Event.fromJson(envelope)
        if (event.kind().asU16() != GIFT_WRAP_KIND) null
        else {
            val gift = UnwrappedGift.fromGiftWrap(rust.nostr.sdk.NostrSigner.keys(keys), event)
            val rumor = gift.rumor()
            val sender = gift.sender().toHex()
            if (rumor.kind().asU16() != CHAT_MESSAGE_KIND || rumor.author().toHex() != sender) null
            else com.relay.app.transport.PayloadCodec.decode(rumor.content())?.let { sender to it }
        }
    } catch (e: Exception) {
        null
    }
}
