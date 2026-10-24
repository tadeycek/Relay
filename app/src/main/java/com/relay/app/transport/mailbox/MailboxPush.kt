package com.relay.app.transport.mailbox

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.relay.app.transport.nostr.NostrIdentity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Wake-up pushes (Firebase): this phone tells the delivery server its Firebase token, and the server
 * sends an empty "wake" whenever a message waits for it while the app has no open connection. Only
 * active when the build has both a delivery server and a Firebase config (google-services.json).
 */
object MailboxPush {

    private const val TAG = "MailboxPush"
    private const val UNIQUE_NAME = "relay_push_token"
    private val JSON = "application/json".toMediaType()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    fun available(context: Context): Boolean =
        MailboxConfig.available && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    /** Schedules registration of the latest token, retrying transient failures and admission delays. */
    fun refresh(context: Context) {
        if (!available(context) || com.relay.app.calculator.CalculatorOnly.enabled(context)) return
        val request = OneTimeWorkRequestBuilder<MailboxPushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Blocking; off the main thread. An empty [token] asks the server to stop pushing. */
    fun register(context: Context, token: String): Boolean {
        if (!MailboxConfig.available) return false
        return runCatching {
            val url = MailboxConfig.url + "/v1/push-token"
            val body = JSONObject().put("v", 1).put("token", token).toString().toByteArray(Charsets.UTF_8)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", MailboxAuth.header(AndroidNostrSigner { NostrIdentity.keys(context) }, "POST", url, body))
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use {
                Log.i(TAG, "push token registration: HTTP ${it.code}")
                it.isSuccessful
            }
        }.onFailure { Log.w(TAG, "push token not registered: ${it.message}") }.getOrDefault(false)
    }
}

class MailboxPushWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!MailboxPush.available(applicationContext) || com.relay.app.calculator.CalculatorOnly.enabled(applicationContext)) return Result.success()
        return try {
            val token = Tasks.await(FirebaseMessaging.getInstance().token, 30, TimeUnit.SECONDS)
            if (MailboxPush.register(applicationContext, token)) Result.success() else Result.retry()
        } catch (error: Exception) {
            Log.w("MailboxPush", "push token unavailable: ${error.message}")
            Result.retry()
        }
    }
}
