package com.relay.app.golden

import com.relay.app.groups.GroupAvatarBody
import com.relay.app.groups.GroupProtocol
import com.relay.app.media.MediaBody
import com.relay.app.media.MediaContainer
import com.relay.app.media.MediaCrypto
import com.relay.app.media.MediaRef
import com.relay.app.media.ProfileNameBody
import com.relay.app.media.ProfilePhotoBody
import com.relay.app.messaging.ConversationEventBody
import com.relay.app.messaging.DeletionBody
import com.relay.app.messaging.IncomingClassifier
import com.relay.app.messaging.PurgeBody
import com.relay.app.messaging.ReadReceiptBody
import com.relay.app.messaging.ReplyBody
import com.relay.app.messaging.TypingBody
import com.relay.app.pairing.PairingMessages
import com.relay.app.pairing.PendingPairing
import com.relay.app.transport.PayloadCodec
import com.relay.app.transport.RelayPayload
import com.relay.app.transport.mailbox.MailboxProtocol
import com.relay.app.transport.nostr.InboxRelays
import com.relay.app.util.QrContactCode
import com.relay.app.util.SmsMessageParser
import com.relay.app.data.model.PinExpiry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * WP03-AC2. Records the output of every body codec for a fixed deterministic corpus into
 * `src/test/resources/golden/body-codecs.golden`. The file is recorded once on the unmodified
 * (pre-extraction) tree with `RELAY_GOLDEN_RECORD=1` and then only ever compared, so a moved or
 * refactored codec that changes a single byte fails here. Randomised outputs (HPKE, AES-GCM key/IV,
 * nonces, Schnorr aux) are excluded; MediaCrypto is covered through fixed-key decrypt vectors.
 */
class BodyCodecGoldenTest {

    private val lines = ArrayList<String>()

    private fun esc(s: String): String = buildString {
        for (c in s) when {
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c.code in 0x20..0x7e -> append(c)
            else -> append("\\u%04x".format(c.code))
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun r(x: Any?): String = when (x) {
        null -> "null"
        is ByteArray -> "bytes:" + hex(x)
        is ProfilePhotoBody.Update -> "PPUpdate(${x.revision},${r(x.jpeg)})"
        is GroupAvatarBody.Update -> "GAUpdate(${x.group},${x.revision},${r(x.jpeg)})"
        is List<*> -> x.joinToString(",", "[", "]") { r(it) }
        is String -> "\"" + x + "\""
        else -> x.toString()
    }

    private fun rec(name: String, block: () -> Any?) {
        val v = try { r(block()) } catch (t: Throwable) { "throws:" + t::class.simpleName }
        lines += "$name\t${esc(v)}"
    }

    private val uuidA = "123e4567-e89b-42d3-a456-426614174000"
    private val uuidB = "9b2c1a7e-0f3d-4b6a-8c5d-1e2f3a4b5c6d"
    private val hex64a = "a".repeat(64)
    private val hex64b = "0123456789abcdef".repeat(4)
    private val key32 = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 1).toByte() })
    private val sha = "ab".repeat(32)
    private val nonce = "00112233445566778899aabbccddeeff"
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3, 4, 5, 250.toByte(), 251.toByte())
    private val pubKeyB64 = Base64.getEncoder().encodeToString(ByteArray(44) { (it * 3).toByte() })
    private val sigB64 = Base64.getEncoder().encodeToString(ByteArray(32) { (255 - it).toByte() })

    private fun corpus() {
        // --- RelayPayload / PayloadCodec
        val payloads = listOf(
            RelayPayload("id-1", 1700000000000L, "hello"),
            RelayPayload(uuidA, 0L, ""),
            RelayPayload("x".repeat(64), Long.MAX_VALUE, "unicode \u00e9\u4e2d\ud83d\udc4d \"quoted\" \\ back\nline"),
            RelayPayload("id", 5, "b", v = 1),
        )
        payloads.forEachIndexed { i, p -> rec("payload.encode.$i") { PayloadCodec.encode(p) } }
        listOf(
            """{"v":1,"id":"a","ts":5,"body":"b"}""", """{"v":2,"id":"a","ts":5,"body":"b"}""", """{"v":0,"id":"a","ts":5,"body":"b"}""",
            """{"v":1,"id":"","ts":5,"body":"b"}""", """{"v":1,"id":"${"z".repeat(65)}","ts":5,"body":"b"}""",
            """{"v":1,"id":"a","ts":"x","body":"b"}""", "not json", "", """{"v":1,"id":"a","ts":5}""",
            """{"id":"a","ts":5,"body":"b","v":1,"extra":true}""",
        ).forEachIndexed { i, j -> rec("payload.decode.$i") { PayloadCodec.decode(j) } }

        // --- MediaBody
        val refs = listOf(
            MediaRef("https://m.example/v1/media/$sha", sha, key32, "image/jpeg", 1234, ""),
            MediaRef("https://m.example/x", sha, key32, "video/mp4", 25L * 1024 * 1024, "a caption | with pipe\u200b"),
            MediaRef("https://m.example/x", sha, key32, "image/png", 1, ReplyBody.format(ReplyBody.Reply("Ann", "q", uuidA, "reply caption"))),
            MediaRef("http://insecure.example/x", sha, key32, "image/gif", 1, ""),
            MediaRef("https://m.example/x", sha, key32, "image/webp", 99, "c".repeat(300)),
        )
        refs.forEachIndexed { i, ref ->
            rec("media.format.$i") { MediaBody.format(ref) }
            rec("media.roundtrip.$i") { MediaBody.parse(MediaBody.format(ref)) }
            rec("media.normalizeCaption.$i") { MediaBody.normalizeCaption(ref.caption) }
        }
        listOf(
            "TYPE:MEDIA|URL:https://h/x|SHA:$sha|KEY:$key32|MIME:IMAGE/JPEG|SIZE:10",
            "type:media|url:https://h/x|sha:${sha.uppercase()}|key:$key32|mime:image/png|size:10|cap:hi",
            "TYPE:MEDIA|URL:https://u:p@h/x|SHA:$sha|KEY:$key32|MIME:image/png|SIZE:10",
            "TYPE:MEDIA|URL:https://h/x|SHA:$sha|KEY:AAAA|MIME:image/png|SIZE:10",
            "TYPE:MEDIA|URL:https://h/x|SHA:$sha|KEY:$key32|MIME:image/bmp|SIZE:10",
            "TYPE:MEDIA|URL:https://h/x|SHA:$sha|KEY:$key32|MIME:image/png|SIZE:0",
            "TYPE:MEDIA|URL:https://h/x|SHA:$sha|KEY:$key32|MIME:image/png|SIZE:${25L * 1024 * 1024 + 1}",
            "TYPE:MEDIA|URL:https://h/x|SHA:zz|KEY:$key32|MIME:image/png|SIZE:5",
            "TYPE:MEDIA|URL:https://h/x|SHA:$sha|KEY:$key32|MIME:image/png|SIZE:5|CAP64:!!!",
            "TYPE:MEDIA|noColon", "  TYPE:MEDIA|URL:https://h/x|SHA:$sha|KEY:$key32|MIME:image/png|SIZE:5  ", "plain text",
        ).forEachIndexed { i, b ->
            rec("media.parse.$i") { MediaBody.parse(b) }
            rec("media.isMedia.$i") { MediaBody.isMedia(b) }
        }
        listOf("https://a/b", "http://a/b", "https://", "https://a@b/c", "https://a b/c", "https://a/b|c", "https://" + "h".repeat(300))
            .forEachIndexed { i, u -> rec("media.url.$i") { MediaBody.isAcceptableUrl(u) } }

        // --- ProfileNameBody / ProfilePhotoBody
        listOf(
            ProfileNameBody.Update(0, "A"), ProfileNameBody.Update(42, "Zo\u00eb \u4e2d"), ProfileNameBody.Update(-1, "bad"),
            ProfileNameBody.Update(1, " pad"), ProfileNameBody.Update(1, "n".repeat(31)), ProfileNameBody.Update(1, "x\u200by"),
        ).forEachIndexed { i, u ->
            rec("profilename.format.$i") { ProfileNameBody.format(u) }
            rec("profilename.roundtrip.$i") { ProfileNameBody.parse(ProfileNameBody.format(u)) }
        }
        listOf("TYPE:PROFILE_PHOTO|KIND:NAME|REV:3|NAME:QW5u", "TYPE:PROFILE_PHOTO|KIND:NAME|REV:-3|NAME:QW5u", "TYPE:PROFILE_PHOTO|KIND:NAME|REV:3|NAME:/w==", "TYPE:PROFILE_PHOTO|KIND:NAME|REV:x|NAME:QQ==", "nope")
            .forEachIndexed { i, b -> rec("profilename.parse.$i") { ProfileNameBody.parse(b) } }
        listOf(ProfilePhotoBody.Update(0, jpeg), ProfilePhotoBody.Update(7, null), ProfilePhotoBody.Update(1, ByteArray(0)),
            ProfilePhotoBody.Update(-1, null), ProfilePhotoBody.Update(2, ByteArray(ProfilePhotoBody.MAX_BYTES + 1)))
            .forEachIndexed { i, u ->
                rec("profilephoto.format.$i") { ProfilePhotoBody.format(u) }
                rec("profilephoto.roundtrip.$i") { ProfilePhotoBody.parse(ProfilePhotoBody.format(u)) }
            }
        listOf("TYPE:PROFILE_PHOTO|REV:1|JPEG:AAAA", "TYPE:PROFILE_PHOTO|REV:1|JPEG:", ProfilePhotoBody.REQUEST, "TYPE:PROFILE_PHOTO|REV:1|JPEG:!!")
            .forEachIndexed { i, b ->
                rec("profilephoto.parse.$i") { ProfilePhotoBody.parse(b) }
                rec("profilephoto.isControl.$i") { ProfilePhotoBody.isControl(b) }
            }

        // --- ReplyBody / DeletionBody / PurgeBody / ReadReceiptBody / ConversationEventBody
        listOf(
            ReplyBody.Reply("Ann", "quoted", uuidA, "text"), ReplyBody.Reply("B", "", "r", "\u00e9\ud83d\udc4d \"q\" \\"),
            ReplyBody.Reply("", "q", "r", "t"), ReplyBody.Reply("N".repeat(31), "q", "r", "t"), ReplyBody.Reply("N", "q".repeat(121), "r", "t"),
        ).forEachIndexed { i, rp ->
            rec("reply.format.$i") { ReplyBody.format(rp) }
            rec("reply.roundtrip.$i") { ReplyBody.parse(ReplyBody.format(rp)) }
            rec("reply.preview.$i") { ReplyBody.preview(ReplyBody.format(rp)) }
        }
        rec("reply.parse.v2") { ReplyBody.parse("TYPE:REPLY|{\"v\":2,\"name\":\"a\",\"quote\":\"b\",\"ref\":\"c\",\"text\":\"d\"}") }
        rec("reply.parse.garbage") { ReplyBody.parse("TYPE:REPLY|{") }
        rec("reply.preview.plain") { ReplyBody.preview("plain") }
        rec("reply.withText.null") { ReplyBody.withText(null, "x") }
        rec("reply.withText.draft") { ReplyBody.withText(ReplyBody.format(ReplyBody.Reply("Ann", "q", "r", "")), "new text") }
        rec("deletion.format.ok") { DeletionBody.format(uuidA) }
        rec("deletion.format.bad") { DeletionBody.format("not-a-uuid") }
        rec("deletion.parse.ok") { DeletionBody.parse(DeletionBody.format(uuidB)) }
        rec("deletion.parse.bad") { DeletionBody.parse(DeletionBody.PREFIX + "{\"v\":1,\"id\":\"zzz\"}") }
        rec("deletion.parse.v2") { DeletionBody.parse(DeletionBody.PREFIX + "{\"v\":2,\"id\":\"$uuidA\"}") }
        rec("purge.format.ok") { PurgeBody.format(1700000000000L) }
        rec("purge.format.zero") { PurgeBody.format(0) }
        rec("purge.parse.ok") { PurgeBody.parse(PurgeBody.format(99)) }
        rec("purge.parse.neg") { PurgeBody.parse(PurgeBody.PREFIX + "{\"v\":1,\"before\":-5}") }
        rec("readreceipt.format.1") { ReadReceiptBody.format(1700000000000L, listOf(uuidA)) }
        rec("readreceipt.format.2") { ReadReceiptBody.format(5, listOf(uuidA, uuidB)) }
        rec("readreceipt.format.dup") { ReadReceiptBody.format(5, listOf(uuidA, uuidA)) }
        rec("readreceipt.format.empty") { ReadReceiptBody.format(5, emptyList()) }
        rec("readreceipt.format.ts0") { ReadReceiptBody.format(0, listOf(uuidA)) }
        rec("readreceipt.parseIds.ok") { ReadReceiptBody.parseIds("$uuidA,$uuidB") }
        rec("readreceipt.parseIds.bad") { ReadReceiptBody.parseIds("$uuidA,nope") }
        rec("readreceipt.parseIds.toomany") { ReadReceiptBody.parseIds(List(65) { UUID_N(it) }.joinToString(",")) }
        val events = listOf(
            ConversationEventBody.Event("screenshot", uuidA, targets = listOf(uuidB)),
            ConversationEventBody.Event("media_saved", uuidA, target = uuidB),
            ConversationEventBody.Event("reaction", uuidA, target = uuidB, emoji = "\u2764\ufe0f", revision = 3),
            ConversationEventBody.Event("reaction", uuidA, target = uuidB, emoji = "", revision = 1),
            ConversationEventBody.Event("reaction", uuidA, target = uuidB, emoji = "x", revision = 1),
            ConversationEventBody.Event("other", uuidA),
        )
        events.forEachIndexed { i, e ->
            rec("event.format.$i") { ConversationEventBody.format(e) }
            rec("event.roundtrip.$i") { ConversationEventBody.parse(ConversationEventBody.format(e)) }
        }
        rec("event.emoji") { ConversationEventBody.EMOJI }


        // --- TypingBody (parse takes an explicit clock, so it is deterministic)
        val typingNow = 1700000000000L
        val typings = listOf(
            TypingBody.Signal(true, typingNow), TypingBody.Signal(false, typingNow - 1000),
            TypingBody.Signal(true, typingNow, uuidA, hex64a, hex64b), TypingBody.Signal(true, typingNow, "x", "y", "z"),
        )
        typings.forEachIndexed { i, sg ->
            rec("typing.format.$i") { TypingBody.format(sg) }
            rec("typing.roundtrip.$i") { TypingBody.parse(TypingBody.format(sg), typingNow) }
        }
        rec("typing.parse.stale") { TypingBody.parse(TypingBody.format(TypingBody.Signal(true, typingNow - TypingBody.TTL - 1)), typingNow) }
        rec("typing.parse.future") { TypingBody.parse(TypingBody.format(TypingBody.Signal(true, typingNow + 2001)), typingNow) }
        rec("typing.parse.junk") { TypingBody.parse(TypingBody.PREFIX + "{", typingNow) }

        // --- GroupAvatarBody
        rec("groupavatar.format.jpeg") { GroupAvatarBody.format(GroupAvatarBody.Update(uuidA, 4, jpeg)) }
        rec("groupavatar.format.null") { GroupAvatarBody.format(GroupAvatarBody.Update(uuidA, 0, null)) }
        rec("groupavatar.format.badgroup") { GroupAvatarBody.format(GroupAvatarBody.Update("nope", 0, null)) }
        rec("groupavatar.roundtrip.jpeg") { GroupAvatarBody.parse(GroupAvatarBody.format(GroupAvatarBody.Update(uuidA, 4, jpeg))) }
        rec("groupavatar.roundtrip.null") { GroupAvatarBody.parse(GroupAvatarBody.format(GroupAvatarBody.Update(uuidB, 1, null))) }
        rec("groupavatar.parse.notjpeg") { GroupAvatarBody.parse(GroupAvatarBody.PREFIX + "{\"group\":\"$uuidA\",\"revision\":1,\"jpeg\":\"AAAAAA==\"}") }

        // --- GroupProtocol
        val state = GroupProtocol.State(uuidA, hex64a, "Team", 3, listOf(
            GroupProtocol.Member(hex64a, "Owner", pubKeyB64, listOf("wss://r1.example", "wss://r2.example")),
            GroupProtocol.Member(hex64b, "Friend \u00e9"),
        ))
        val sync = GroupProtocol.Sync(state)
        rec("group.sync.format") { GroupProtocol.format(sync) }
        rec("group.sync.roundtrip") { GroupProtocol.parse(GroupProtocol.format(sync)) }
        rec("group.sync.fitted") { GroupProtocol.format(GroupProtocol.Sync(GroupProtocol.fitted(state))) }
        rec("group.ref") { GroupProtocol.ref(state) }
        rec("group.memberId") { GroupProtocol.memberId(hex64b) }
        rec("group.relayHints") { GroupProtocol.relayHints(listOf("wss://a.example", "ws://bad", "wss://a.example", "wss://b.example", "wss://c.example")) }
        GroupProtocol.EVENT_KINDS.forEach { kind ->
            val text = when (kind) {
                "post" -> "hi there"
                "delete" -> uuidB
                "purge" -> "1700000000000"
                "read" -> uuidB
                else -> ProfileNameBody.format(ProfileNameBody.Update(1, "Nm"))
            }
            val ev = GroupProtocol.Event(kind, GroupProtocol.Ref(uuidA, hex64a, 3), uuidB, hex64b, text, 1700000000000L)
            rec("group.event.$kind.format") { GroupProtocol.format(ev) }
            rec("group.event.$kind.roundtrip") { GroupProtocol.parse(GroupProtocol.format(ev)) }
            rec("group.event.$kind.check.ok") { GroupProtocol.checkEvent(hex64b, state.copy(members = state.members), ev) }
            rec("group.event.$kind.check.spoof") { GroupProtocol.checkEvent(hex64a, state, ev) }
            rec("group.event.$kind.check.null") { GroupProtocol.checkEvent(hex64b, null, ev) }
        }
        rec("group.acceptSync.ok") { GroupProtocol.acceptSync(hex64a, state, state.copy(revision = 4)) }
        rec("group.acceptSync.old") { GroupProtocol.acceptSync(hex64a, state, state.copy(revision = 2)) }
        rec("group.acceptSync.wrongsender") { GroupProtocol.acceptSync(hex64b, null, state) }
        rec("group.parse.v1sync") { GroupProtocol.parse(GroupProtocol.format(sync).replace("\"v\":2", "\"v\":1")) }
        rec("group.parse.junk") { GroupProtocol.parse(GroupProtocol.PREFIX + "[]") }

        // --- SmsMessageParser
        rec("sms.location.0") { SmsMessageParser.formatLocation(46.056946, 14.505751) }
        rec("sms.location.1") { SmsMessageParser.formatLocation(-33.8688, 151.2093, PinExpiry.SIX_HOURS, "  Home sweet home  ") }
        rec("sms.location.2") { SmsMessageParser.formatLocation(0.0, -0.0, PinExpiry.NEVER, "L".repeat(40)) }
        rec("sms.location.parse") { SmsMessageParser.parseLocation(SmsMessageParser.formatLocation(-33.8688, 151.2093, PinExpiry.ONE_HOUR, "x")) }
        rec("sms.location.parseExpiry") { SmsMessageParser.parseExpiry("TYPE:LOCATION|LAT:1.000000|LNG:2.000000|EXPIRY:48hr") }
        rec("sms.location.parseLabel") { SmsMessageParser.parseLabel("TYPE:LOCATION|LAT:1.0|LNG:2.0|LABEL:\u00e9 Caf\u00e9 |x") }
        rec("sms.readReceipt") { SmsMessageParser.formatReadReceipt(1700000000000L) }
        rec("sms.readReceipt.parse") { SmsMessageParser.parseReadReceipt("TYPE:READ_RECEIPT|MSG_ID:123") }
        rec("sms.pubkey.0") { SmsMessageParser.formatPublicKey(pubKeyB64) }
        rec("sms.pubkey.1") { SmsMessageParser.formatPublicKey(pubKeyB64, "N\u00e4me|x\n", sigB64, sigB64) }
        val pk = SmsMessageParser.formatPublicKey(pubKeyB64, "Name", sigB64, sigB64)
        rec("sms.pubkey.parse.key") { SmsMessageParser.parsePublicKey(pk) }
        rec("sms.pubkey.parse.sig") { SmsMessageParser.parsePublicKeySigningKey(pk) }
        rec("sms.pubkey.parse.rot") { SmsMessageParser.parsePublicKeyRotationSignature(pk) }
        rec("sms.pubkey.parse.name") { SmsMessageParser.parsePublicKeyName(pk) }
        rec("sms.enc.0") { SmsMessageParser.formatEncrypted("QUJD") }
        rec("sms.enc.1") { SmsMessageParser.formatEncrypted("QUJD", sigB64) }
        rec("sms.enc.parse.ct") { SmsMessageParser.parseEncryptedPayload(" " + SmsMessageParser.formatEncrypted("QUJD+/==", sigB64) + " ") }
        rec("sms.enc.parse.sig") { SmsMessageParser.parseEncryptedSignature(SmsMessageParser.formatEncrypted("QUJD", sigB64)) }
        rec("sms.enc.parse.none") { SmsMessageParser.parseEncryptedPayload("TYPE:ENC|CT:!!!") }

        // --- IncomingClassifier over a mixed corpus
        listOf(
            "hello", pk, SmsMessageParser.LOCATION_REQUEST_MSG, SmsMessageParser.LOCATION_DECLINED_MSG,
            SmsMessageParser.formatLocation(1.5, 2.5, PinExpiry.TWENTY_FOUR_HOURS, "Lab"), SmsMessageParser.formatReadReceipt(77),
            ReadReceiptBody.format(88, listOf(uuidA)), "TYPE:READ_RECEIPT|MSG_ID:5|IDS:bad",
            MediaBody.format(refs[0]), "TYPE:MEDIA|URL:http://x", "TYPE:ENC|CT:QUJD",
        ).forEachIndexed { i, b -> rec("classify.$i") { IncomingClassifier.classify(b) } }

        // --- PairingMessages / PendingPairing
        rec("pair.req.0") { PairingMessages.formatRequest(nonce, "Ann", pubKeyB64, sigB64) }
        rec("pair.req.1") { PairingMessages.formatRequest(nonce, "  N|a\u200bme  ", pubKeyB64, null) }
        rec("pair.req.2") { PairingMessages.formatRequest(nonce, "", pubKeyB64, "") }
        rec("pair.confirm") { PairingMessages.formatConfirm(nonce) }
        rec("pair.parse.req") { PairingMessages.parse(PairingMessages.formatRequest(nonce, "Ann", pubKeyB64, sigB64)) }
        rec("pair.parse.confirm") { PairingMessages.parse(PairingMessages.formatConfirm(nonce)) }
        rec("pair.parse.badnonce") { PairingMessages.parse("TYPE:PAIR_CONFIRM|NONCE:xyz") }
        rec("pair.parse.badkey") { PairingMessages.parse("TYPE:PAIR_REQUEST|NONCE:$nonce|KEY:!!!") }
        rec("pair.valid") { listOf(PairingMessages.isValidNonce(nonce), PairingMessages.isValidNonce(null), PairingMessages.isValidNonce("AB")) }
        val pending = PendingPairing(hex64a, nonce, "Ann", pubKeyB64, null, listOf("wss://r.example"), true, 12, 1700000000000L)
        rec("pair.pending.toJson") { pending.toJson() }
        rec("pair.pending.roundtrip") { PendingPairing.fromJson(pending.toJson()) }
        rec("pair.pending.roundtrip.sig") { PendingPairing.fromJson(pending.copy(signingPublicKeyBase64 = sigB64).toJson()) }
        rec("pair.pending.bad") { PendingPairing.fromJson("{}") }

        // --- QrContactCode
        rec("qr.encode.0") { QrContactCode.encode("Ann", hex64a.uppercase(), pubKeyB64) }
        rec("qr.encode.1") { QrContactCode.encode("A|n\n n\u200b", hex64b, pubKeyB64, sigB64, listOf("wss://r1.example", "ws://bad", "wss://r1.example", "ws://abc.onion")) }
        rec("qr.encode.2") { QrContactCode.encode("Ann", hex64a, pubKeyB64, null, emptyList(), nonce) }
        rec("qr.encode.3") { QrContactCode.encode("Ann", hex64a, pubKeyB64, sigB64, listOf("wss://r"), nonce, 1700000000000L) }
        rec("qr.encode.bad") { QrContactCode.encode("Ann", hex64a, pubKeyB64, null, emptyList(), null, 5L) }
        listOf(
            QrContactCode.encode("Ann", hex64a, pubKeyB64), QrContactCode.encode("Ann", hex64a, pubKeyB64, sigB64, listOf("wss://r1.example"), nonce),
            QrContactCode.encode("Ann", hex64a, pubKeyB64, sigB64, listOf("wss://r1.example"), nonce, 1700000000000L),
            "RELAYQR:2|PHONE:+38640123456|NAME:Old|KEY:$pubKeyB64", "RELAYQR:2|NAME:Old|KEY:$pubKeyB64", "RELAYQR:5|NAME:x|NPUB:$hex64a|KEY:$pubKeyB64",
            "RELAYQR:3|NAME:x|NPUB:zz|KEY:$pubKeyB64", "RELAYQR:0|X:1", "garbage",
        ).forEachIndexed { i, raw -> rec("qr.decode.$i") { QrContactCode.decode(raw) } }
        rec("qr.usable") {
            val s = QrContactCode.decode(QrContactCode.encode("A", hex64a, pubKeyB64, null, emptyList(), nonce, 1000L))!!
            listOf(QrContactCode.isUsableInvitation(s, 999L), QrContactCode.isUsableInvitation(s, 1000L))
        }
        rec("qr.hints") { QrContactCode.cleanRelayHints(listOf(" wss://a ", "wss://b|c", "ws://x.onion", "wss://a", "http://z")) }
        rec("inboxrelays.tags") { InboxRelays.toTags(listOf("wss://a.example", "ws://nope", "ws://x.onion", "wss://a.example")) }
        rec("inboxrelays.parse") { InboxRelays.parseRelayTags(listOf(listOf("relay", "wss://a.example"), listOf("p", "x"), listOf("relay", "ws://nope"))) }
        rec("inboxrelays.resolve") { InboxRelays.resolveTargets(null, listOf("wss://h.example"), listOf("wss://o.example")) }

        // --- MailboxProtocol
        listOf(
            """{"type":"ready","expiresAt":1700000000}""",
            """{"type":"batch","messages":[{"seq":1,"envelope":"e1"},{"seq":0,"envelope":"skip"},{"seq":3,"envelope":""},{"seq":4,"envelope":"e4"}],"more":true,"cursor":4}""",
            """{"type":"message","seq":9,"envelope":"e9"}""", """{"type":"message","seq":-1,"envelope":"e9"}""",
            """{"type":"acked","through":12}""",
            """{"type":"error","error":{"code":"rate","retryable":true},"ref":"sync"}""", """{"type":"error","error":{"code":"x"}}""",
            """{"type":"signal","envelope":"sig"}""", """{"type":"signal"}""", "pong", """{"type":"zzz"}""", """{"type":"batch"}""",
        ).forEachIndexed { i, f -> rec("mailbox.frame.$i") { MailboxProtocol.parseFrame(f) } }
        rec("mailbox.sync") { MailboxProtocol.syncFrame(17) }
        rec("mailbox.ack") { MailboxProtocol.ackFrame(18) }
        rec("mailbox.signal") { MailboxProtocol.signalFrame(hex64a, "env", "ref-1") }
        rec("mailbox.send") { MailboxProtocol.sendBody(hex64a, "id-1", "{\"k\":\"\u00e9\"}") }
        listOf(
            200 to """{"results":[{"status":"stored"}]}""", 200 to """{"results":[{"status":"duplicate"}]}""",
            200 to """{"results":[{"status":"rejected","error":{"code":"unknown_recipient"}}]}""",
            200 to """{"results":[{"status":"rejected","error":{"code":"id_conflict"}}]}""",
            200 to """{"results":[{"status":"rejected","error":{"code":"envelope_too_large"}}]}""",
            200 to """{"results":[{"status":"rejected","error":{"code":"busy","retryable":true}}]}""",
            200 to """{"results":[{"status":"rejected","error":{"code":"nope"}}]}""", 200 to """{"results":[]}""", 200 to "xx",
            200 to """{"results":[{"status":"weird"}]}""", 400 to "", 403 to "", 413 to "", 401 to """{"error":{"code":"stale"}}""", 401 to "", 500 to "",
        ).forEachIndexed { i, (st, b) -> rec("mailbox.outcome.$i") { MailboxProtocol.sendOutcome(st, b) } }
        rec("mailbox.ids") { listOf(MailboxProtocol.isRecipient(hex64a), MailboxProtocol.isRecipient("A".repeat(64)), MailboxProtocol.isDeliveryId("a-b_c"), MailboxProtocol.isDeliveryId("")) }

        // --- MediaContainer / MediaCrypto (fixed vectors only)
        listOf(0, 1, 2, 255, 65532, 65533, 65534, 200000).forEach { n ->
            val payload = ByteArray(n) { (it * 31 + 7).toByte() }
            val wrapped = MediaContainer.wrap(payload)
            rec("container.size.$n") { MediaContainer.wrappedSize(n) }
            rec("container.sha.$n") { MediaCrypto.sha256Hex(wrapped) }
            rec("container.unwrap.$n") { MediaContainer.unwrap(wrapped)?.let { MediaCrypto.sha256Hex(it) } }
        }
        rec("container.wrap.small") { MediaContainer.wrap(byteArrayOf(1, 2, 3)) }
        rec("container.unwrap.bad") { MediaContainer.unwrap(byteArrayOf(1, 2, 3, 4, 5)) }
        val fixedKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val fixedBlob = ByteArray(12) { (100 + it).toByte() } + javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, ByteArray(12) { (100 + it).toByte() }))
            doFinal("golden media plaintext".toByteArray())
        }
        rec("crypto.blob") { fixedBlob }
        rec("crypto.decrypt.ok") { MediaCrypto.decrypt(fixedBlob, fixedKey)?.toString(Charsets.UTF_8) }
        rec("crypto.decrypt.wrongkey") { MediaCrypto.decrypt(fixedBlob, key32) }
        rec("crypto.decrypt.short") { MediaCrypto.decrypt(ByteArray(5), fixedKey) }
        rec("crypto.decrypt.badkey") { MediaCrypto.decrypt(fixedBlob, "AAAA") }
        rec("crypto.sha.empty") { MediaCrypto.sha256Hex(ByteArray(0)) }
        rec("crypto.roundtrip") { val e = MediaCrypto.encrypt("abc".toByteArray()); MediaCrypto.decrypt(e.blob, e.keyBase64)?.toString(Charsets.UTF_8) }
        rec("crypto.blob.size") { MediaCrypto.encrypt(ByteArray(100)).blob.size }
        rec("b64.vectors") { listOf(ByteArray(0), byteArrayOf(0), byteArrayOf(0, 1), byteArrayOf(0, 1, 2), ByteArray(48) { (it * 5).toByte() }, ByteArray(255) { it.toByte() }).map { Base64.getEncoder().encodeToString(it) } }
    }

    @Suppress("FunctionName")
    private fun UUID_N(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    @Test
    fun bodyCodecsAreByteIdenticalToTheRecordedCorpus() {
        corpus()
        val actual = lines.joinToString("\n", postfix = "\n")
        val golden = File("src/test/resources/golden/body-codecs.golden")
        if (System.getenv("RELAY_GOLDEN_RECORD") == "1") {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            println("GOLDEN RECORDED: ${lines.size} entries -> ${golden.absolutePath}")
            return
        }
        assertEquals("golden entry count", golden.readLines().size, lines.size)
        val expected = golden.readText().split("\n").filter { it.isNotEmpty() }
        lines.forEachIndexed { i, l -> assertEquals("golden entry #$i (${l.substringBefore('\t')})", expected[i], l) }
        assertEquals(golden.readText(), actual)
    }
}
