package com.relay.app.transport.mailbox

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import rust.nostr.sdk.Keys
import java.util.concurrent.TimeUnit

/** Result of asking the server with the stored admin key, used only to recover a lost migration reply. */
internal enum class AdminProbe { NOT_ATTEMPTED, ACCEPTED, REFUSED }

/** What the phone should persist after a migration attempt. */
internal enum class MigrationAction { PERSIST_MIGRATED, KEEP_SHARED_KEY }

/**
 * Pure decision for [PhoneDeviceEnrollment.migrate], so the recovery rule is unit-tested without an
 * Android `Context`. A `2xx` means the server migrated. A `401 auth_invalid` means the shared identity
 * key is already disabled: if a `GET /v1/devices` signed with the stored admin key then succeeds, the
 * migration committed and only the reply was lost, so the migrated flag must be persisted anyway.
 */
internal fun migrationAction(httpStatus: Int, errorCode: String?, adminProbe: AdminProbe): MigrationAction = when {
    httpStatus in 200..299 -> MigrationAction.PERSIST_MIGRATED
    httpStatus == 401 && errorCode == "auth_invalid" && adminProbe == AdminProbe.ACCEPTED -> MigrationAction.PERSIST_MIGRATED
    else -> MigrationAction.KEEP_SHARED_KEY
}

/**
 * Phone-side plumbing for the WP05/G1 device authorization contract.
 *
 * The phone holds a separate, phone-only administration key. Before migration the shared Nostr identity
 * key signs every mailbox request exactly as in 1.15. [migrate] installs the admin key with a kind-27235
 * request signed by the identity key; after that [authKeys] returns the admin key, so the transport signs
 * as the primary device. Linked desktop keys are enrolled by [enroll] and revoked by [revoke].
 *
 * This class deliberately lives behind the existing [MailboxAuth] seam: callers keep passing a
 * [rust.nostr.sdk.NostrSigner] to the transport and media code. It does not touch UI or the identity
 * storage, which keeps the untested UI layer out of the security change.
 */
object PhoneDeviceEnrollment {

    private const val TAG = "PhoneDeviceEnrollment"
    private const val PREF_FILE = "relay_device_admin"
    private const val KEY_ADMIN_SECRET = "admin_secret_hex"
    private const val KEY_MIGRATED = "identity_migrated"
    private const val PATH = "/v1/devices"

    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: Keys? = null

    /** The phone-only administration key, generated once and kept under the Keystore-backed preferences. */
    fun adminKeys(context: Context): Keys {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = prefs(context.applicationContext)
            val existing = prefs.getString(KEY_ADMIN_SECRET, null)
            val keys = if (existing != null) Keys.parse(existing) else {
                val fresh = Keys.generate()
                prefs.edit().putString(KEY_ADMIN_SECRET, fresh.secretKey().toHex()).commit()
                fresh
            }
            cached = keys
            return keys
        }
    }

    /** Whether this install has migrated the shared identity to the admin key. */
    fun isMigrated(context: Context): Boolean = prefs(context.applicationContext).getBoolean(KEY_MIGRATED, false)

    /**
     * The signer the mailbox transport should use. Before migration this is the shared identity key, so
     * legacy clients are unchanged; afterwards it is the phone-only admin key, which the server accepts
     * only as `primary`. Callers pass this where they currently pass `NostrIdentity.keys`.
     */
    fun authKeys(context: Context, identity: () -> Keys): Keys =
        if (isMigrated(context)) adminKeys(context) else identity()

    /**
     * `POST /v1/devices/admin` signed by the shared identity key. On success the identity is migrated and
     * the shared identity key no longer authenticates mailbox device operations.
     */
    fun migrate(context: Context, baseUrl: String, identity: Keys): Boolean {
        // Idempotent: a repeated call after success never touches the network or the shared key again.
        if (isMigrated(context)) return true
        val admin = adminKeys(context)
        if (admin.publicKey().toHex() == identity.publicKey().toHex()) {
            Log.e(TAG, "admin key must differ from the shared identity key")
            return false
        }
        val body = JSONObject().put("v", 1).put("admin_pubkey", admin.publicKey().toHex()).toString()
        val result = postForStatus(baseUrl, "/v1/devices/admin", body.toByteArray(Charsets.UTF_8), identity)
        // A 401 auth_invalid can mean a previous attempt committed but its reply was lost. Probe with
        // the admin key before giving up, and treat a successful probe as a completed migration.
        val probe = if (result.status == 401 && result.errorCode == "auth_invalid") {
            if (adminCanListDevices(baseUrl, admin)) AdminProbe.ACCEPTED else AdminProbe.REFUSED
        } else {
            AdminProbe.NOT_ATTEMPTED
        }
        if (migrationAction(result.status, result.errorCode, probe) == MigrationAction.PERSIST_MIGRATED) {
            prefs(context.applicationContext).edit().putBoolean(KEY_MIGRATED, true).commit()
            return true
        }
        return false
    }

    /**
     * `POST /v1/devices?device=primary` signed by the admin key. Binds a linked device's id and its own
     * authentication key to this identity. [deviceKeys] never leave this device except as the public key.
     */
    fun enroll(context: Context, baseUrl: String, deviceId: String, deviceKeys: Keys, label: String): Boolean {
        require(deviceId.matches(Regex("^[0-9a-f]{32}$"))) { "device id must be 32 lowercase hex" }
        val admin = adminKeys(context)
        val body = JSONObject()
            .put("v", 1)
            .put("device", deviceId)
            .put("label", label)
            .put("auth_pubkey", deviceKeys.publicKey().toHex())
            .toString()
        return post(context, baseUrl, "$PATH?device=primary", body.toByteArray(Charsets.UTF_8), admin)
    }

    /** `DELETE /v1/devices/<id>?device=primary` signed by the admin key: revoke a linked device. */
    fun revoke(context: Context, baseUrl: String, deviceId: String): Boolean {
        require(deviceId.matches(Regex("^[0-9a-f]{32}$"))) { "device id must be 32 lowercase hex" }
        val admin = adminKeys(context)
        val url = baseUrl.trimEnd('/') + "$PATH/$deviceId?device=primary"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(AndroidNostrSigner { admin }, "DELETE", url))
            .delete()
            .build()
        return try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "revoke failed: ${e.message}")
            false
        }
    }

    /** A fresh 32-lowercase-hex device id, generated when linking a computer (WP05 part B). */
    fun newDeviceId(): String =
        (1..32).map { "0123456789abcdef"[java.security.SecureRandom().nextInt(16)] }.joinToString("")

    private fun post(context: Context, baseUrl: String, path: String, body: ByteArray, keys: Keys): Boolean {
        return postForStatus(baseUrl, path, body, keys).status in 200..299
    }

    private data class PostResult(val status: Int, val errorCode: String?)

    private fun postForStatus(baseUrl: String, path: String, body: ByteArray, keys: Keys): PostResult {
        val url = baseUrl.trimEnd('/') + path
        val request = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(AndroidNostrSigner { keys }, "POST", url, body))
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "$path refused: ${response.code}")
                val text = response.body?.string().orEmpty()
                val code = try {
                    JSONObject(text).optJSONObject("error")?.optString("code")?.takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
                PostResult(response.code, code)
            }
        } catch (e: Exception) {
            Log.w(TAG, "$path failed: ${e.message}")
            PostResult(0, null)
        }
    }

    /** `GET /v1/devices?device=primary` with the admin key: true when the server accepts it. */
    private fun adminCanListDevices(baseUrl: String, admin: Keys): Boolean {
        val url = baseUrl.trimEnd('/') + "$PATH?device=primary"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", MailboxAuth.header(AndroidNostrSigner { admin }, "GET", url))
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "admin probe failed: ${e.message}")
            false
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREF_FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
