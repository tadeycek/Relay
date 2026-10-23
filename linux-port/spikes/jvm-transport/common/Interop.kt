package relay.spike

import com.google.crypto.tink.*
import com.google.crypto.tink.config.TinkConfig
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import rust.nostr.sdk.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Isolated synthetic identities only. Never use a production identity or personal messages. */
object Interop {
    const val TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    private val b64 = Base64.getEncoder()
    fun init() = TinkConfig.register()
    fun fresh(): KeysetHandle = KeysetHandle.generateNew(KeyTemplates.get(TEMPLATE))
    fun publicKey(h: KeysetHandle): String {
        val out = java.io.ByteArrayOutputStream()
        h.publicKeysetHandle.writeNoSecret(BinaryKeysetWriter.withOutputStream(out))
        return b64.encodeToString(out.toByteArray())
    }
    // Private fixture serialization only in this standalone debug experiment, never app code.
    fun privateKey(h: KeysetHandle): String {
        val out = java.io.ByteArrayOutputStream()
        CleartextKeysetHandle.write(h, JsonKeysetWriter.withOutputStream(out))
        return out.toString("UTF-8")
    }
    fun importKey(json: String): KeysetHandle = CleartextKeysetHandle.read(JsonKeysetReader.withString(json))
    fun encrypt(public: String, text: String): String {
        val h = KeysetHandle.readNoSecret(BinaryKeysetReader.withBytes(Base64.getDecoder().decode(public)))
        return "TYPE:ENC|CT:" + b64.encodeToString(h.getPrimitive(HybridEncrypt::class.java).encrypt(text.toByteArray(), byteArrayOf()))
    }
    fun decrypt(h: KeysetHandle, body: String): String {
        require(body.startsWith("TYPE:ENC|CT:"))
        return String(h.getPrimitive(HybridDecrypt::class.java).decrypt(Base64.getDecoder().decode(body.substringAfter("TYPE:ENC|CT:")), byteArrayOf()), Charsets.UTF_8)
    }
    fun wrap(keys: Keys, recipient: String, body: String, id: String = UUID.randomUUID().toString()): String = runBlocking {
        val receiver = PublicKey.parse(recipient)
        val payload = PayloadCodec.encode(RelayPayload(id, System.currentTimeMillis(), body))
        val rumor = EventBuilder.privateMsgRumor(receiver, payload).build(keys.publicKey())
        giftWrap(NostrSigner.keys(keys), receiver, rumor, emptyList()).asJson()
    }
    fun unwrap(keys: Keys, json: String, expectedSender: String): RelayPayload = runBlocking {
        val event = Event.fromJson(json)
        val gift = UnwrappedGift.fromGiftWrap(NostrSigner.keys(keys), event)
        val rumor = gift.rumor()
        require(rumor.kind().asU16() == 14.toUShort())
        require(gift.sender().toHex() == expectedSender)
        require(rumor.author().toHex() == gift.sender().toHex())
        checkNotNull(PayloadCodec.decode(rumor.content()))
    }
    fun header(keys: Keys, method: String, url: String, bytes: ByteArray? = null): String {
        val tags = mutableListOf(listOf("u", url), listOf("method", method))
        if (bytes != null && bytes.isNotEmpty()) tags += listOf("payload", hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        tags += listOf("nonce", hex(ByteArray(16).also { SecureRandom().nextBytes(it) }))
        val e = EventBuilder(Kind(27235.toUShort()), "").tags(tags.map { Tag.parse(it) }).signWithKeys(keys)
        return "Nostr " + b64.encodeToString(e.asJson().toByteArray())
    }
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}

class Mailbox(val base: String, val keys: Keys): AutoCloseable {
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    init {
        val u = java.net.URI(base)
        require(u.scheme == "https" || (u.scheme == "http" && u.host in setOf("127.0.0.1", "localhost")))
    }
    fun request(method: String, path: String, data: JSONObject? = null): JSONObject {
        val url = base.trimEnd('/') + path
        val bytes = data?.toString()?.toByteArray()
        val req = okhttp3.Request.Builder().url(url).header("Authorization", Interop.header(keys, method, url, bytes))
        req.method(method, bytes?.toRequestBody("application/json".toMediaType()))
        return client.newCall(req.build()).execute().use {
            check(it.isSuccessful) { "mailbox HTTP ${it.code}" }
            val body = checkNotNull(it.body)
            require(body.contentLength() <= 2 * 1024 * 1024)
            val source = body.source()
            require(!source.request(2 * 1024 * 1024L + 1)) { "oversized response" }
            JSONObject(source.readUtf8())
        }
    }
    fun send(to: String, id: String, envelope: String): JSONObject = request("POST", "/v1/send", JSONObject().put("v",1).put("deliveries",JSONArray().put(JSONObject().put("to",to).put("id",id).put("envelope",envelope))))
    fun checkSocket() {
        val url=base.replaceFirst("http", "ws").trimEnd('/')+"/v1/ws"
        val ready=java.util.concurrent.CountDownLatch(1)
        val synced=java.util.concurrent.CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val socket=client.newWebSocket(okhttp3.Request.Builder().url(url).header("Authorization",Interop.header(keys,"GET",url)).build(),object: WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val obj=JSONObject(text)
                    if(obj.optString("type")=="ready") {
                        ready.countDown()
                        ws.send("{\"type\":\"sync\",\"after\":0,\"limit\":10,\"ref\":\"spike\"}")
                    }
                    if(obj.optString("type")=="batch") synced.countDown()
                } catch(e: Exception) { failure.set(e); ready.countDown(); synced.countDown() }
            }
            override fun onFailure(ws: WebSocket,t: Throwable,response: okhttp3.Response?) { failure.set(t);ready.countDown();synced.countDown() }
        })
        try {
            check(ready.await(20,TimeUnit.SECONDS) && synced.await(20,TimeUnit.SECONDS)) { "WebSocket ready/sync timeout" }
            failure.get()?.let { throw IllegalStateException("WebSocket failed",it) }
        } finally { socket.cancel() }
    }
    override fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
