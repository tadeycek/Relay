package com.relay.app.desktop

import com.relay.app.call.group.GroupAnnouncement
import com.relay.app.call.group.GroupCallCodec
import com.relay.app.call.group.GroupCallFenceSnapshot
import com.relay.app.call.group.GroupTrackRef
import com.relay.app.groups.GroupProtocol
import com.relay.app.transport.SendResult
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** Shared fixtures for the WP16 desktop group-call tests: roster, fakes for the Worker API, native media, timers and the store. */
class GroupCallHarness(val self: String = A) {
    companion object {
        val A = "a".repeat(64); val B = "b".repeat(64); val C = "c".repeat(64); val D = "d".repeat(64); val OUT = "e".repeat(64)
        const val GROUP = "123e4567-e89b-42d3-a456-426614174000"
        const val DEVICE = "desk-1"
        fun pid(n: Int) = "%016x".format(n)
    }

    val log = CopyOnWriteArrayList<String>()
    var now = 1_800_000_000_000L
    var memberKeys = listOf(A, B, C)
    var revision = 3L
    fun stored(keys: List<String> = memberKeys, rev: Long = revision, owner: String = A) = StoredGroup(
        conversationId = groupConversationId(owner, GROUP), owner = owner, sharedId = GROUP, name = "Design", revision = rev,
        members = keys.map { GroupProtocol.Member(it, "n-" + it.take(1)) }, avatar = null, avatarRevision = -1, updatedAt = 0)
    var group: StoredGroup? = stored()
    val conv get() = groupConversationId(A, GROUP)
    private var ctr = 0
    val deterministicRandom: (Int) -> ByteArray = { n -> ByteArray(n) { (++ctr * 7 + n).toByte() } }

    class MemStore : GroupCallStateStore {
        val data = HashMap<String, GroupCallFenceSnapshot>()
        val invites = HashMap<String, StoredGroupCall>()
        @Volatile var failNext = false
        var commits = 0
        override fun snapshot(conversationId: String) = data[conversationId]
        override fun commit(conversationId: String, snapshot: GroupCallFenceSnapshot) {
            if (failNext) throw IllegalStateException("disk full")
            data[conversationId] = snapshot; commits++
        }
        override fun commit(conversationId: String, snapshot: GroupCallFenceSnapshot, invitation: StoredGroupCall?) {
            if (failNext) throw IllegalStateException("disk full")
            data[conversationId] = snapshot
            if (invitation == null) invites.remove(conversationId) else invites[conversationId] = invitation
            commits++
        }
        override fun invitations(): Map<String, StoredGroupCall> = HashMap(invites)
    }
    val store = MemStore()

    inner class FakeSocket(val room: String, val secret: String, val pid: String?, val listener: RoomListener) : RoomSocket {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var closed: Pair<Int, String>? = null
        override fun send(text: String): Boolean { sent += text; return closed == null }
        override fun close(code: Int, reason: String) { closed = code to reason; log += "ws-close:$code" }
    }
    class FakeSfu : SfuApi {
        override fun call(op: String, body: JSONObject): JSONObject = JSONObject().put("v", 1)
    }
    inner class FakeApi : GroupCallRoomApi {
        val creates = CopyOnWriteArrayList<CreateRoomRequest>()
        val ends = CopyOnWriteArrayList<String>()
        val removals = CopyOnWriteArrayList<Triple<String, List<String>, String?>>()
        val sockets = CopyOnWriteArrayList<FakeSocket>()
        @Volatile var createOutcome: CreateRoomOutcome = CreateRoomOutcome.Created
        var createHook: (CreateRoomRequest) -> CreateRoomOutcome? = { null }
        @Volatile var infoFor: (String) -> RoomInfoOutcome = { _ -> RoomInfoOutcome.Unknown }
        val infos = CopyOnWriteArrayList<String>()
        @Volatile var removeOk = true
        override fun create(request: CreateRoomRequest): CreateRoomOutcome { creates += request; log += "create:${request.room}"; return createHook(request) ?: createOutcome }
        override fun info(room: String): RoomInfoOutcome { infos += room; return infoFor(room) }
        override fun end(room: String): Boolean { ends += room; log += "end:$room"; return true }
        override fun removeMembers(room: String, remove: List<String>, secretHash: String?): Boolean { removals += Triple(room, remove, secretHash); log += "remove:$room"; return removeOk }
        override fun rotate(room: String, secretHash: String) = true
        override fun connect(room: String, secret: String, pid: String?, listener: RoomListener): RoomSocket =
            FakeSocket(room, secret, pid, listener).also { sockets += it; log += "connect:$room:${pid ?: "-"}" }
        override fun sfu(room: String): SfuApi = FakeSfu()
        val last get() = sockets.last()
    }
    val api = FakeApi()

    inner class FakeMedia : GroupMediaPort {
        val calls = CopyOnWriteArrayList<String>()
        var onEvent: (GroupMediaEvent) -> Unit = {}
        var levelMap: Map<String, Float> = emptyMap()
        @Volatile var closed = false
        override fun open(sfu: SfuApi, onEvent: (GroupMediaEvent) -> Unit) { this.onEvent = onEvent; calls += "open"; log += "media-open" }
        override fun publish() { calls += "publish"; log += "media-publish" }
        override fun setMicMuted(muted: Boolean) { calls += "mic:$muted" }
        override fun subscribe(ref: GroupTrackRef) { calls += "sub:${ref.pid}:${ref.sessionId}" }
        override fun unsubscribe(ref: GroupTrackRef) { calls += "unsub:${ref.pid}:${ref.sessionId}" }
        override fun setDeafened(deafened: Boolean) { calls += "deaf:$deafened" }
        override fun setVolume(pid: String, volume: Float) { calls += "vol:$pid:$volume" }
        override fun levels() = levelMap
        override fun close() { closed = true; calls += "close"; log += "media-close" }
    }
    val medias = CopyOnWriteArrayList<FakeMedia>()
    val media get() = medias.last()

    class ManualTimers : GroupCallTimers {
        class T(val at: Long, val task: () -> Unit) { @Volatile var cancelled = false }
        val pending = CopyOnWriteArrayList<T>()
        var clock = 0L
        override fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable { val t = T(clock + delayMs, task); pending += t; return AutoCloseable { t.cancelled = true } }
        fun advance(ms: Long) {
            clock += ms
            pending.filter { !it.cancelled && it.at <= clock }.sortedBy { it.at }.forEach { pending.remove(it); it.task() }
        }
    }
    val timers = ManualTimers()

    val announced = CopyOnWriteArrayList<Pair<String, String>>()
    @Volatile var announceResult: (String) -> SendResult = { SendResult.Sent(1) }

    var online = true
    var linked = true
    val services = mutableListOf<DesktopGroupCalls>()
    fun service(graceMs: Long = 30_000): DesktopGroupCalls = DesktopGroupCalls(
        selfPubkey = self, deviceId = DEVICE,
        groupByConversation = { if (group?.conversationId == it) group else null },
        groupBySharedId = { owner, id -> group?.takeIf { it.owner == owner && it.sharedId == id } },
        states = store, api = if (linked) api else null,
        announce = { peer, body -> announced += peer to body; log += "announce:${peer.take(1)}"; announceResult(peer) },
        online = { online }, makeMedia = { FakeMedia().also { medias += it } }, nameOf = { "n-" + it.take(1) },
        clock = { now }, random = deterministicRandom, timers = timers, reconnectGraceMs = graceMs,
    ).also { services += it }

    fun close() { services.forEach { runCatching { it.close() } } }

    fun roster() = group!!.protocolState()
    fun startBy(caller: String, device: String = "dev-${caller.take(1)}", at: Long = now, roster: GroupProtocol.State = roster()): GroupAnnouncement =
        GroupCallCodec.newStart(roster, caller, device, at, deterministicRandom,
            keyEpoch = 0L, callKey = ByteArray(32) { (it * 7 + 3).toByte() })
    fun frame(a: GroupAnnouncement) = GroupCallWire.frame(a)

    fun welcome(you: Int, others: List<Triple<Int, String, String?>>): String {
        val arr = others.joinToString(",") { (n, key, session) ->
            """{"pid":"${pid(n)}","pubkey":"$key","device":"dev-${key.take(1)}","sessionId":${session?.let { "\"$it\"" } ?: "null"},"track":${if (session != null) "\"mic\"" else "null"},"muted":false}"""
        }
        return """{"type":"welcome","v":1,"you":"${pid(you)}","participants":[$arr]}"""
    }
    fun joined(n: Int, key: String, session: String) =
        """{"type":"joined","pid":"${pid(n)}","pubkey":"$key","device":"dev-${key.take(1)}","sessionId":"$session","track":"mic","muted":false}"""
    fun left(n: Int, key: String) = """{"type":"left","pid":"${pid(n)}","pubkey":"$key","device":"dev-${key.take(1)}"}"""

    fun liveMatching(a: GroupAnnouncement): RoomInfoOutcome = RoomInfoOutcome.Live(true, a.revision, a.rosterHash, 1)

    /** Announces from [caller], joins the call and delivers [welcome]; returns the announcement. */
    fun joinedCall(svc: DesktopGroupCalls, caller: String = B, others: List<Triple<Int, String, String?>> = listOf(Triple(1, caller, "sess-" + caller.take(1))), you: Int = 9): GroupAnnouncement {
        val start = startBy(caller)
        api.infoFor = { liveMatching(start) }
        check(svc.ingress(caller, frame(start)))
        check(svc.join(conv) == null)
        api.last.listener.onOpen()
        api.last.listener.onText(welcome(you, others))
        svc.sync()
        return start
    }
}

/** Test-side decoder of a framed announcement body (the production path goes through [GroupCallCodec.decode]). */
fun GroupCallWire.decodeForTest(body: String): GroupAnnouncement {
    val o = JSONObject(unframe(body)!!)
    return GroupAnnouncement(o.getString("op"), o.getString("event"), o.getString("group"), o.getString("owner"), o.getLong("revision"),
        o.getString("rosterHash"), o.getString("room"), if (o.isNull("joinSecret")) null else o.getString("joinSecret"),
        o.getString("caller"), o.getString("device"), o.getLong("created"), o.getLong("expires"))
}
