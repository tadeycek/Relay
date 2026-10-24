package com.relay.app.media

import android.content.Context
import com.relay.app.transport.mailbox.AndroidNostrSigner
import com.relay.app.transport.mailbox.MailboxConfig
import com.relay.app.transport.mailbox.MailboxMedia
import com.relay.app.transport.mailbox.NostrSigner
import okhttp3.OkHttpClient
import com.relay.app.transport.mailbox.PhoneDeviceEnrollment
import com.relay.app.transport.nostr.NostrIdentity
import java.io.File

/** Keep the un-migrated wire URL unchanged; migration binds admin authorization to primary. */
internal fun mediaDevice(migrated: Boolean): String? = if (migrated) "primary" else null

internal fun mediaOriginMayConfirm(sendAccepted: Boolean, linked: Boolean, grantPublished: Boolean): Boolean =
    sendAccepted && (!linked || grantPublished)

internal fun mediaRecipients(others: List<String>, own: String): List<String> =
    (others + own).distinct().also { recipients ->
        require(recipients.isNotEmpty() && recipients.size <= 16 && recipients.all { Regex("[0-9a-f]{64}").matches(it) }) {
            "Invalid media recipients"
        }
    }

/** Shared streaming client cap is slightly stricter than the server; GCM adds IV and tag. */
internal fun legacyStoredBytes(plain: Long, wrapped: Boolean): Long {
    require(plain > 0 && plain <= MediaBody.MAX_MEDIA_BYTES)
    val stored = if (wrapped) MediaContainer.wrappedSize((plain + 28).toInt()).toLong() else plain + 28
    require(stored <= MailboxMedia.MAX_STORED_BYTES) { "File is too large" }
    return stored
}

/** Pure seam: the signer is the per-device auth signer; the URL is signed exactly as requested. */
internal fun phoneMediaClient(signer: NostrSigner, migrated: Boolean, base: String = MailboxConfig.url,
                              http: OkHttpClient? = null): MailboxMedia.Client {
    val ctx = MailboxMedia.RequestContext(signer, mediaDevice(migrated))
    return if (http == null) MailboxMedia.Client(ctx, base) else MailboxMedia.Client(ctx, base, http)
}

internal fun phoneMedia(context: Context): MailboxMedia.Client {
    val app = context.applicationContext
    return phoneMediaClient(
        AndroidNostrSigner { PhoneDeviceEnrollment.authKeys(app) { NostrIdentity.keys(app) } },
        PhoneDeviceEnrollment.isMigrated(app))
}

/** Bounded staged download; the shared client verifies the stored digest before publishing the file. */
internal fun downloadPhoneMedia(context: Context, url: String, sha: String, max: Long): ByteArray? {
    if (max < 1) return null
    val dir = File(context.cacheDir, "media-staging").also { it.mkdirs() }
    val file = File(dir, java.util.UUID.randomUUID().toString())
    return try {
        phoneMedia(context).downloadTo(url, sha, file, minOf(max, MailboxMedia.MAX_STORED_BYTES))
        file.inputStream().use { stream ->
            val bytes = ByteArray(file.length().toInt())
            var offset = 0
            while (offset < bytes.size) {
                val n = stream.read(bytes, offset, bytes.size - offset)
                if (n < 0) return null
                offset += n
            }
            bytes
        }
    } catch (_: Exception) { null } finally { file.delete() }
}
