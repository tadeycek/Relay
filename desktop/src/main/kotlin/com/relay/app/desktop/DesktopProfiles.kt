package com.relay.app.desktop

import com.relay.app.media.ProfileNameBody
import com.relay.app.media.ProfilePhotoBody
import com.relay.app.transport.SendResult
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Declared (width, height) from the first SOF segment, parsed from the header bytes only so a tiny JPEG
 * claiming a huge image is never allocated. Null when no well-formed SOF precedes the scan.
 */
internal fun jpegDeclaredSize(jpeg: ByteArray): Pair<Int, Int>? {
    var i = 2
    while (i + 3 < jpeg.size) {
        if (jpeg[i] != 0xff.toByte()) return null
        val marker = jpeg[i + 1].toInt() and 0xff
        if (marker == 0xff) { i++; continue }
        if (marker == 0x01 || marker in 0xd0..0xd8) { i += 2; continue }
        if (marker == 0xd9 || marker == 0xda) return null
        val len = ((jpeg[i + 2].toInt() and 0xff) shl 8) or (jpeg[i + 3].toInt() and 0xff)
        if (len < 2) return null
        if (marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
            if (len < 8 || i + 9 >= jpeg.size) return null
            val h = ((jpeg[i + 5].toInt() and 0xff) shl 8) or (jpeg[i + 6].toInt() and 0xff)
            val w = ((jpeg[i + 7].toInt() and 0xff) shl 8) or (jpeg[i + 8].toInt() and 0xff)
            return w to h
        }
        i += 2 + len
    }
    return null
}

/** Size/magic, declared dimensions 1..256 (header parse first), then a real decode. */
internal fun validDesktopAvatar(jpeg: ByteArray?): Boolean {
    if (jpeg == null) return true
    if (jpeg.size !in 4..8192 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte() ||
        jpeg[jpeg.lastIndex - 1] != 0xff.toByte() || jpeg.last() != 0xd9.toByte()) return false
    val (w, h) = jpegDeclaredSize(jpeg) ?: return false
    if (w !in 1..256 || h !in 1..256) return false
    return runCatching {
        MemoryCacheImageInputStream(ByteArrayInputStream(jpeg)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use false
            val reader = readers.next()
            try {
                if (!reader.formatName.equals("JPEG", ignoreCase = true)) return@use false
                reader.input = input
                reader.getWidth(0) in 1..256 && reader.getHeight(0) in 1..256 && reader.read(0) != null
            } finally {
                reader.dispose()
            }
        }
    }.getOrDefault(false)
}

/**
 * Display names and avatars for this install and for peers, carried by the phone's existing
 * [ProfileNameBody] / [ProfilePhotoBody] controls. Only the newer revision of a name or photo is
 * applied, so a replayed control cannot rename a contact.
 *
 * Photo bytes stay in the encrypted database; nothing is fetched from a URL.
 */
class DesktopProfiles(
    private val store: DesktopMessageStore,
    private val ownPubkey: String,
    private val nameProvider: () -> String,
    private val sender: GroupSender,
    /** Answers a peer's photo request without blocking the receive loop. */
    private val onReply: (peer: String, body: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class SendReport(val sent: Int, val failed: Int, val firstError: String?)

    /** This install's display name, or "" before the user chooses one. */
    fun ownName(): String = store.profile(ownPubkey)?.name ?: nameProvider().trim()

    fun ownAvatar(): ByteArray? = store.profile(ownPubkey)?.avatar

    /** The best honest name for [pubkey]: stored profile, then [fallback]. */
    fun displayName(pubkey: String, fallback: String): String =
        store.profile(pubkey)?.name?.takeIf { it.isNotBlank() } ?: fallback

    fun avatar(pubkey: String): ByteArray? = store.profile(pubkey)?.avatar

    /** The stored row (name, avatar, revisions) or null for someone we have never heard from. */
    fun profileOf(pubkey: String): DesktopProfile? = store.profile(pubkey)

    suspend fun publishName(name: String): SendReport {
        val clean = name.trim()
        // Format first: the codec owns the 1–30 character rule, and a rejected name is never stored.
        val revision = (store.profile(ownPubkey)?.nameRevision ?: -1L) + 1
        val body = ProfileNameBody.format(ProfileNameBody.Update(revision, clean))
        store.saveProfileName(ownPubkey, clean, revision, now = clock())
        return fanOut(body)
    }

    suspend fun publishAvatar(jpeg: ByteArray?): SendReport {
        require(validDesktopAvatar(jpeg)) { "That picture cannot be used as an avatar" }
        val revision = (store.profile(ownPubkey)?.avatarRevision ?: -1L) + 1
        val body = ProfilePhotoBody.format(ProfilePhotoBody.Update(revision, jpeg))
        store.saveProfileAvatar(ownPubkey, jpeg, revision, now = clock())
        return fanOut(body)
    }

    /** Asks one peer for its current name and picture. */
    suspend fun request(peer: String): SendReport {
        val contact = store.contacts().firstOrNull { it.nostrPubkey == peer && it.eligibleForGroups() }
            ?: return SendReport(0, 0, null)
        val result = sender.send(peer, contact.publicKey, contact.relayHints, ProfilePhotoBody.REQUEST)
        return when (result) {
            is SendResult.Sent -> SendReport(1, 0, null)
            is SendResult.Failed -> SendReport(0, 1, result.reason)
        }
    }

    /** Returns true when [body] was a profile control (valid or not), so it never renders as chat. */
    fun handle(senderKey: String, body: String): Boolean {
        if (!ProfilePhotoBody.isControl(body) && !ProfileNameBody.isControl(body)) return false
        if (store.contacts().none { it.nostrPubkey == senderKey && it.eligibleForGroups() }) return true
        when {
            body == ProfilePhotoBody.REQUEST -> {
                // Reply with what we have; both bodies are optional.
                runCatching { ProfileNameBody.format(ProfileNameBody.Update(store.profile(ownPubkey)?.nameRevision ?: 0, ownName())) }
                    .getOrNull()?.let { onReply(senderKey, it) }
                runCatching {
                    ProfilePhotoBody.format(ProfilePhotoBody.Update(store.profile(ownPubkey)?.avatarRevision ?: 0, ownAvatar()))
                }.getOrNull()?.let { onReply(senderKey, it) }
            }
            ProfileNameBody.isControl(body) -> ProfileNameBody.parse(body)?.let { update ->
                store.saveProfileName(senderKey, update.name, update.revision, now = clock())
            }
            else -> ProfilePhotoBody.parse(body)?.takeIf { validDesktopAvatar(it.jpeg) }?.let { update ->
                store.saveProfileAvatar(senderKey, update.jpeg, update.revision, now = clock())
            }
        }
        return true
    }

    private suspend fun fanOut(body: String): SendReport {
        var sent = 0
        var failed = 0
        var firstError: String? = null
        for (contact in store.contacts()) {
            if (!contact.eligibleForGroups()) continue
            when (val result = sender.send(contact.nostrPubkey!!, contact.publicKey, contact.relayHints, body)) {
                is SendResult.Sent -> sent++
                is SendResult.Failed -> {
                    failed++
                    if (firstError == null) firstError = result.reason
                }
            }
        }
        return SendReport(sent, failed, firstError)
    }
}
