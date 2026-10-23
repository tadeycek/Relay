# 07. Group voice calls on Cloudflare Realtime SFU (option A)

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

Discord-style voice for any Relay group:
- anyone in the group starts the call;
- members join and leave freely while it runs;
- mute, deafen and speaking indicators are supported.

Implemented in WP15 (server), WP16 (clients) and WP17 (E2EE, gated).

## 1. Shape

```
Members' clients ──E2E group fan-out──▶ "group_start {room, secret, e2eeKey?}"   (stored, like any group event)
       │
       ├─ wss /v1/call/rooms/<room>/ws ──▶ CallRoom Durable Object  (roster of participants, track directory)
       │                                         │ holds SFU_APP_SECRET use, never exposes it
       └─ HTTPS /v1/call/rooms/<room>/sfu/* ─────┴──▶ rtc.live.cloudflare.com/v1/apps/<app>/sessions/…
       │
       └─ WebRTC (DTLS-SRTP, Opus) ◀────────────▶ Cloudflare SFU edge (nearest PoP)
```

- **One SFU session per participant device.** It publishes one local audio track named `mic` and subscribes to
  every other participant's `mic`.
- **The SFU has no rooms.** The `CallRoom` DO is the room: it knows who is in the call and which session/track each
  of them publishes, and it pushes changes to everyone (02 §D).

## 2. Starting and announcing a call

1. Member M presses **Join voice** in the group with no active call. M's client then:
   - generates `room` (16 random bytes, hex), `secret` (32 random bytes) and, once WP17 ships, `e2eeKey`
     (32 random bytes);
   - calls `POST /v1/call/rooms {room, secretHash}`.
2. M sends `TYPE:CALL op=group_start` through the **existing group v2 direct fan-out** to every member. It is a
   stored delivery, so offline members and late joiners get it, and members' devices show "Voice active — N in
   call".
3. Members join with `room` and `secret`. The server never learns the group roster: it only sees which server
   members connect to the room (LP-D12).
4. When the last participant leaves, the DO closes everything and wipes its storage. The last leaver's client also
   sends `group_end`, which members use to clear the "voice active" banner. A stale banner self-clears after 10
   minutes with no room activity: clients query `GET /v1/call/rooms/<room>` (200/404) when they open the group.

## 3. Joining (client ↔ DO ↔ SFU)

1. `GET /v1/call/ice`, for TURN credentials in case UDP is blocked.
2. Open `wss …/rooms/<room>/ws?secret=…&device=…`.
   - The DO checks NIP-98 membership (the same auth helper as the mailbox) and `sha256(secret)`.
   - On success it replies `{"type":"welcome","participants":[{pid, pubkey, device, sessionId, track, muted}], "you":pid}`.
3. Create a `RTCPeerConnection` and add the microphone track as `sendonly`.
4. `POST …/sfu/sessions-new` with the local offer. The DO forwards it to `sessions/new`, records
   `sessionId → pid`, and returns the SFU answer.
5. `POST …/sfu/tracks-new` with `location: local, trackName: mic, mid`. The DO records the publication and
   broadcasts `{"type":"joined", pid, sessionId, track:"mic"}`.
6. To subscribe to the others, `POST …/sfu/tracks-new` with `location: remote` and up to 64 tracks per call
   (`sessionId`, `trackName: mic`).
   - If the response has `requiresImmediateRenegotiation`, the client applies the SFU's offer, answers, and sends
     `PUT …/sfu/renegotiate`.
7. On each later `joined` or `left` event, every client subscribes to the new track or closes the departed one with
   `tracks/close`.
   - Mutations are serialised per session, as Cloudflare requires.

The DO enforces that a participant can only mutate its own `sessionId`. It also enforces at most 2 sessions per pid
during reconnects, and at most 17 participants (the largest Relay group). That cap is a sanity limit, not a
technical one.

## 4. In-call behaviour

- **Mute** keeps the sender alive with silence instead of removing the track. The SFU garbage-collects tracks that
  receive no packets for 30 s, and Opus DTX still sends sparse packets. The muted state is broadcast through the DO
  (`{"type":"state", pid, muted, deafened}`).
- **Deafen** mutes playback of every remote track, locally only.
- **Speaking indicator** is computed locally from received-track `audioLevel` stats every 200 ms, with a threshold
  and a 300 ms hangover. It costs no server traffic.
- **Reconnect.** If the PeerConnection fails:
  - within the SFU's 30 s reuse window, do an ICE restart;
  - otherwise create a new session, republish, and resubscribe;
  - the DO swaps the pid's `sessionId` and broadcasts it.
  If the DO socket drops, reconnect with the same pid token within 30 s.
- **Phone specifics.** The same core-telecom and `phoneCall` foreground-service path as 1:1 calls (06 §4):
  - the call is shown as "Group voice — <group name>";
  - the speaker is the default route;
  - leaving the app keeps the call running.
- **Desktop specifics.**
  - The voice bar lists participants with speaking rings, mute and deafen icons.
  - A participant can be clicked for per-user volume, which is local gain on the remote track.

## 5. Security and privacy

- **Authentication.** Room access requires both server membership (NIP-98) and the room secret, which is delivered
  E2E only to group members.
- **Roster check on clients.** Each client compares participants' pubkeys with its own copy of the group roster.
  Anyone not on it is shown as **"Not in this group"** in red, and the user can leave. The call starter can end the
  room (`{"type":"end"}` to the DO, accepted from the creator's pubkey only) and start a new one with a fresh
  secret.
- **Until WP17 ships:** Cloudflare's SFU terminates DTLS-SRTP and could technically access group audio. The voice
  bar shows a small, honest label: "Encrypted in transit (not end-to-end)". 1:1 calls don't carry this label,
  because they are E2EE.
- **What the server sees:** room ids, which members joined when, mute timing, and SFU session ids. It doesn't see
  the group name or roster.
- **Secrets.** `SFU_APP_ID`, `SFU_APP_SECRET`, `TURN_KEY_ID` and `TURN_API_TOKEN` are Worker secrets set with
  `wrangler secret put`. They are never in the repo, never in client builds, and never in evidence files.

## 6. E2EE for group calls (WP17, gated by LP-D16)

- **Key.** A per-call 32-byte `e2eeKey` travels in `group_start` (E2E). Keys are derived per sender:
  `HKDF(e2eeKey, info = "relay-call-frame" ‖ pubkey ‖ device)`.
- **Android:** `FrameCryptor` from `io.github.webrtc-sdk:android`, with AES-GCM and a key provider.
- **Desktop:** `webrtc-java` encoded transforms (`RTCRtpSender`/`RTCRtpReceiver` transform), with AES-GCM
  implemented in Kotlin.
- **Gate.** The frame format (IV layout, trailer, key index) must be **identical** on both platforms. The LiveKit
  `FrameCryptor` format is fixed by its native code, so the desktop has to reproduce it byte for byte. The WP02/WP17
  spike encrypts on Android, decrypts on desktop and vice versa, through the real SFU. Failure leaves group calls
  "encrypted in transit" and is recorded as such.
- **Key rotation on leave** (a member who left can't decrypt later audio) is a stretch goal inside WP17. Without it,
  a member who leaves and still holds the key could decrypt captured SFU traffic for that call only.

## 7. Budget (estimates from 02 §E)

- **Egress:** about 21.6 MB per stream-hour, giving about 430 MB per hour for 5 people and about 5.9 GB per hour for
  17 people. The free 1,000 GB a month is shared with TURN.
- **Durable Object requests:**
  - about 6 per join;
  - socket frames for joins, leaves and mute changes;
  - hibernation keeps idle rooms cheap.
- **Rows:** at most 1 per participant join/leave plus room creation and deletion, so negligible.
- **Watch:** add `EXPOSE_METRICS` coverage for `CallRoom`, and add a Cloudflare dashboard check on Realtime egress
  to the pilot probe notes (WP16 evidence).
