package com.relay.app.lab

import android.content.Context
import com.relay.app.transport.nostr.DefaultRelays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rust.nostr.sdk.Client
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnwrappedGift
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 0 spike (debug builds only). Answers the two questions the rebuild depends on before any
 * production code is written:
 *
 *  1. Do public relays actually keep NIP-17 gift wraps while the recipient is offline, and for how
 *     long? -> [sendProbe] now, then [checkProbe] again after 1h / 24h / 72h / 7d.
 *  2. Does the SDK work on-device at all (connect, publish, fetch, unwrap)?
 *
 * Two throwaway identities (A = sender, B = recipient) live in SharedPreferences so the check can
 * be run hours or days after the send, even after the app process died.
 */
object RelayLab {

    private const val PREFS = "relay_lab"
    private const val KEY_A = "a_secret"
    private const val KEY_B = "b_secret"
    private const val KEY_LAST_SENT = "last_sent_secs"
    private const val KEY_LAST_TEXT = "last_text"

    private fun keys(context: Context, slot: String): Keys {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(slot, null)?.let { return Keys.parse(it) }
        val fresh = Keys.generate()
        prefs.edit().putString(slot, fresh.secretKey().toHex()).apply()
        return fresh
    }

    /** Publishes one gift-wrapped DM from A to B to every relay in [relays] and reports per relay. */
    suspend fun sendProbe(context: Context, relays: List<String> = DefaultRelays.CLEARNET): String =
        withContext(Dispatchers.IO) {
            val a = keys(context, KEY_A)
            val b = keys(context, KEY_B)
            val text = "relay-lab probe ${System.currentTimeMillis()}"
            val client = Client(NostrSigner.keys(a))
            val report = StringBuilder()
            try {
                relays.forEach { client.addRelay(RelayUrl.parse(it)) }
                client.connect()
                val rumor = EventBuilder.privateMsgRumor(b.publicKey(), text).build(a.publicKey())
                val started = System.currentTimeMillis()
                val out = client.giftWrapTo(relays.map { RelayUrl.parse(it) }, b.publicKey(), rumor, emptyList())
                val ms = System.currentTimeMillis() - started
                report.appendLine("Sent \"$text\" in ${ms} ms")
                report.appendLine("Recipient (B): ${b.publicKey().toBech32()}")
                out.success.forEach { report.appendLine("  OK    $it") }
                out.failed.forEach { (url, err) -> report.appendLine("  FAIL  $url: $err") }
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong(KEY_LAST_SENT, System.currentTimeMillis() / 1000)
                    .putString(KEY_LAST_TEXT, text)
                    .apply()
            } catch (e: Exception) {
                report.appendLine("Send failed: ${e.message}")
            } finally {
                runCatching { client.disconnect() }
            }
            report.toString()
        }

    /**
     * Asks each relay separately whether it still returns the gift wraps addressed to B, and tries
     * to unwrap them. NIP-17 fuzzes wrap timestamps by up to two days, so no `since` filter is
     * used here — the relay's own retention is what is being measured.
     */
    suspend fun checkProbe(context: Context, relays: List<String> = DefaultRelays.CLEARNET): String =
        withContext(Dispatchers.IO) {
            val b = keys(context, KEY_B)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val sentAt = prefs.getLong(KEY_LAST_SENT, 0L)
            val report = StringBuilder()
            if (sentAt == 0L) return@withContext "No probe sent yet."
            val ageMin = (System.currentTimeMillis() / 1000 - sentAt) / 60
            report.appendLine("Last probe sent $ageMin min ago")

            val signer = NostrSigner.keys(b)
            val client = Client(signer)
            try {
                relays.forEach { client.addRelay(RelayUrl.parse(it)) }
                client.connect()
                for (url in relays) {
                    val filter = Filter().kind(Kind(1059u)).pubkey(b.publicKey())
                    try {
                        val events = client.fetchEventsFrom(listOf(RelayUrl.parse(url)), filter, 10.seconds).toVec()
                        var unwrapped = 0
                        var matched = false
                        for (ev in events) {
                            val gift = runCatching { UnwrappedGift.fromGiftWrap(signer, ev) }.getOrNull() ?: continue
                            unwrapped++
                            if (gift.rumor().content() == prefs.getString(KEY_LAST_TEXT, null)) matched = true
                        }
                        report.appendLine(
                            "  $url: ${events.size} wraps, $unwrapped unwrapped, last probe ${if (matched) "PRESENT" else "MISSING"}"
                        )
                    } catch (e: Exception) {
                        report.appendLine("  $url: fetch failed (${e.message})")
                    }
                }
            } catch (e: Exception) {
                report.appendLine("Check failed: ${e.message}")
            } finally {
                runCatching { client.disconnect() }
            }
            report.toString()
        }
}
