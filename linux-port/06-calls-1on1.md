# 06. 1:1 voice calls

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

These are audio-only, peer-to-peer WebRTC calls between two Relay identities, with every linked device of the
callee ringing. The work is split across WP11 (server), WP12 (`:core` engine), WP13 (Android) and WP14 (desktop).

## 1. Media path and privacy

- WebRTC with Opus mono at 24–32 kbit/s, DTX on, and FEC on.
- ICE servers are `stun:stun.cloudflare.com:3478` plus Cloudflare TURN credentials from `/v1/call/ice` (05 §5).
- ICE policy is `all`. A setting called **"Always relay calls"** switches it to `relay`, which hides your IP from
  the other person.
- **Encryption.** DTLS-SRTP runs between the two endpoints. TURN relays only SRTP ciphertext, so the call is
  end-to-end encrypted.
- **Identity binding.** Each side puts its DTLS certificate fingerprint into the SDP, and the SDP travels inside
  the authenticated NIP-17 envelope (the seal signature proves the sender). That binding is what stops a TURN or
  mailbox operator from inserting itself as a man in the middle. **Both sides must verify** that the
  `a=fingerprint` in the received SDP matches the one the DTLS handshake actually used. libwebrtc already enforces
  this.
- **What the server learns.** The mailbox sees signal frame sizes and timing: who called whom, when, and for how
  long by signal activity. Cloudflare TURN sees IP pairs when relaying. The UI states this once on the first call.

## 2. State machine (`:core CallEngine1to1`)

```
Caller:  Idle → Inviting ─(ringing)→ Ringing ─(accept from device D)→ Connecting ─(ICE connected)→ Active → Ended
                    │ 45 s no accept → timeout          │ decline/busy → Ended          ICE failed 15 s → Ended(failed)
Callee:  Idle → Incoming(ringing all devices) ─(local accept)→ Connecting → Active → Ended
                    │ answered_elsewhere → Ended(silently)  │ cancel/timeout → Missed
```

**Rules:**

- **One call at a time per device.**
  - An incoming invite while you're Active gets `busy` and is recorded as a missed call.
  - Call waiting is not in scope.
- **Glare** (both sides invite at the same time): the invite with the lower `call` id wins. The other side cancels
  its own invite and accepts the winning one.
- **Accept race** (two of the callee's devices accept):
  - The caller takes the first `accept` it receives and sends `offer` to that device only.
  - It sends `answered_elsewhere` with the winning device to the callee's identity.
  - A losing device that already sent `accept` gives up when that arrives, or after 5 s without an offer.
- **Reconnect.** During Active, if ICE becomes `disconnected`, the engine runs an ICE restart: a new offer with
  `iceRestart`, sent over signals, for up to 20 s before Ended(failed). This covers Wi-Fi ↔ mobile handover.
- **Missed and declined calls** are written to the conversation as call events, using the existing
  `ConversationEventBody` style (local only, not sent).

The engine is pure Kotlin: inputs are signals and timers, outputs are commands to a `MediaAdapter` and a
`SignalSink`. It is unit-tested with a fake clock for every transition above, including glare and the accept race.

## 3. Signalling transport

- **Live path.** Everything goes over the mailbox socket `signal` frame `{"type":"signal","to":<pubkey>,"envelope":…}`.
  - That reaches **all** sockets of the recipient, so every device sees it.
  - `device` fields in call bodies tell devices which messages are theirs.
  - These frames are never stored and cost no rows.
- **Wake path.**
  - `invite` is also sent as a normal stored delivery with `urgent: true`.
  - A callee whose phone is asleep is woken by FCM, connects its socket, and fetches the inbox.
  - It finds an `invite` younger than 45 s and starts ringing.
  - The caller resends `invite` as a signal every 5 s while Inviting, so a device that just connected catches up
    immediately.
- **Size budget.**
  - SDP is deflated and base64-encoded, and `ice` batches are capped (05 §6).
  - Target: every frame ≤14 KiB after wrapping.
  - WP02 measures real frames.
  - The server variable `MAX_SIGNAL_BYTES` = 32 KiB is the fallback.
- **Rate.** A call setup is about 10–20 frames, and a long call adds about 1 a minute (keepalive and mute), well
  under 120 a minute.

## 4. Android (WP13)

- **Dependencies:** `io.github.webrtc-sdk:android` (current at planning time: 150.7871.04) and
  `androidx.core:core-telecom` (stable 1.0.x). Both need entries in `gradle/verification-metadata.xml`, regenerated
  with the full task set.
- **Permissions:**
  - `RECORD_AUDIO` (runtime, requested at the first call);
  - `MANAGE_OWN_CALLS`;
  - `FOREGROUND_SERVICE_PHONE_CALL` + `FOREGROUND_SERVICE_MICROPHONE`;
  - `USE_FULL_SCREEN_INTENT`;
  - `BLUETOOTH_CONNECT` (Android 12+, for headset routing).
- **Telecom.** Calls register as self-managed calls through `CallsManager` (core-telecom). This gives us:
  - system audio routing (earpiece, speaker, Bluetooth, wired);
  - interaction with cellular calls (hold or reject);
  - wearable surfaces.
  Self-managed calls don't go into the system call log unless the app opts in, and **we don't opt in**.
- **Incoming call while asleep:**
  1. A high-priority FCM `wake` with `urgent=1` reaches `RelayMessagingService`.
  2. The service opens the mailbox socket directly instead of going through `PollWorker`, which is too slow for a
     ring, and fetches the inbox.
  3. If there is a fresh `invite`, it posts a `CallStyle.forIncomingCall` notification with a full-screen intent
     when `canUseFullScreenIntent()` allows it, otherwise as a heads-up.
  4. It starts the `phoneCall` foreground service.
  5. On WP13 test phones, measure the time from send to ring on Wi-Fi and on mobile data, screen off. Target: ≤5 s
     median.
- **Calculator disguise and the app lock:**
  - The incoming-call UI shows the contact's display name and avatar, following the existing notification preview
    setting. With previews hidden, it shows "Incoming call" with no name.
  - **Answering doesn't unlock messaging.** The in-call screen is a separate activity showing only the call
    controls.
  - Opening the chat from a call requires the normal calculator code.
  - Expiry of the 10-minute session doesn't end an active call.
  - (Proposed; open question Q1 in 09.)
- **Audio focus and Bluetooth** are handled by the Telecom integration. A proximity wake lock is held while on
  earpiece.
- **Calculator-only / wiped state:** no calls are possible. Incoming invites are ignored, the same as messages
  today (`CalculatorOnly.enabled`).

## 5. Desktop (WP14)

- **WebRTC:** `webrtc-java` with the platform classifier jar. Audio uses `AudioDeviceModule`, with input/output
  device pickers in Settings → Voice. Audio devices are enumerated through PulseAudio, which on PipeWire systems
  means pipewire-pulse.
- **Incoming call:**
  - A tray notification plus an always-on-top ringing window with Accept and Decline.
  - The ring sound loops for up to 45 s.
  - Ringing also works when the main window is hidden, as long as the app runs (tray).
- **During the call:**
  - A bottom voice bar shows the contact, a timer, mute, deafen, the output device and hang up.
  - A push-to-talk key is optional and works only while the window is focused, because global hotkeys on Wayland
    aren't portable.
- **macOS:** the same code plus `NSMicrophoneUsageDescription`. The microphone prompt is verified in WP18.

## 6. Acceptance (summary; full list in the WPs)

- Calls connect phone↔phone, phone↔desktop and desktop↔desktop, on the same LAN and across networks.
- One side behind a UDP-blocking firewall gets connected over `turns:…:443` and TURN egress is observed.
- With phone and desktop both ringing, accepting on one stops the other within 2 s.
- A 10-minute call with a Wi-Fi → mobile handover on the phone recovers within 20 s.
- Packet capture shows only DTLS/SRTP and no plaintext RTP. The fingerprint in the SDP matches the DTLS certificate.
