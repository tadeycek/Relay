# 02. Research findings

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

Checked 2026-10-23. **Verified** means read from the cited source or this repo that day. **Estimate** means
arithmetic from verified inputs. Re-measure every estimate in the WP that depends on it. **Unverified** means it
still needs checking, and the WP named alongside it owns the check.

## A. What the repo already gives us (verified, this checkout)

- **Server contract**: `server/cloudflare/PROTOCOL.md`; limits come from `src/protocol.ts` `DEFAULT_LIMITS`.
  - Envelope ≤128 KiB. Pending bytes ≤32 MiB per recipient and ≤256 MiB in total. ≤16 deliveries per send.
  - ≤4 sockets per identity. Signal frames ≤16 KiB, ≤120 per minute per socket, never stored.
  - Media ≤25 MiB + 64 B per file, ≤16 recipients, 1 MiB storage chunks, 8-day lifetime.
- **Queue semantics**:
  - `/v1/ack` is cumulative and deletes the rows: `DELETE FROM deliveries WHERE recipient = ? AND seq <= ?` in
    `mailbox.ts`.
  - Media `DELETE` sets `confirmed = 1` per recipient **pubkey**. The file is erased when no unconfirmed pubkey
    remains (`media.ts`).
  - **Both operations assume one device per pubkey.** A second device sharing the key loses messages and files to
    whichever device acks or confirms first.
- **Auth**: NIP-98 kind 27235, where the `u` tag signs the exact URL including the query string, so query
  parameters are authenticated. Any holder of the Nostr key can authenticate.
- **Push**: one FCM token per pubkey (`push_tokens.pubkey PRIMARY KEY`), a `{"type":"wake"}` data message, and
  `WAKE_INTERVAL_MS = 20_000` per recipient. On wake, Android only schedules `PollWorker.wakeNow`
  (`RelayMessagingService.kt`).
- **Payload**: `RelayPayload{v,id,ts,body}`, with `body` ≤16 KiB.
  - Bodies are SMS-era strings: `TYPE:MEDIA|URL:|SHA:|KEY:|MIME:|SIZE:|CAP:`, `TYPE:ENC|…` (inner Tink HPKE),
    `TYPE:REPLY`, `TYPE:READ_RECEIPT`, `TYPE:PROFILE_PHOTO`, and others.
  - `MediaBody` only allows `image/jpeg|png|gif|webp` and `video/mp4`, up to 25 MiB. **There is no generic file
    type.**
- **Crypto**:
  - `MediaCrypto` is pure JVM: AES-256-GCM, `iv(12)‖ct‖tag`.
  - `RelayCrypto` uses Tink `AndroidKeysetManager` with an `android-keystore://` master key. It holds three
    keysets: a current HPKE key, a previous HPKE key kept for a grace period, and an Ed25519 signing key.
  - Rotation is phone-initiated and signed by the long-term Ed25519 key.
- **Android-only surface**: 93 of 186 main Kotlin files import `android.*`. The pure-JVM candidates for sharing are
  listed in 04 §2.
- **Retention**: `RetentionPolicy.LIFETIME_MS` = 7 days, and incoming messages older than that are refused.
- **No calling code exists.** The README says "Internet audio calling is not implemented in this checkout."
- **Free-plan budget** (server README, measured 2026-10-18): about 5.3 rows written per delivery and about 2 per
  confirmed message, or roughly 27,000 rows a day for 10 users × 100 messages. **Rows written (100,000 a day) is the
  binding limit.**

## B. Desktop stack (verified on Maven Central / Google Maven, 2026-10-23)

| Need | Library | Version found | Notes |
|---|---|---|---|
| Nostr (same as phone) | `org.rust-nostr:nostr-sdk-kmp-jvm` | 0.44.8 | Same version as the Android `nostr-sdk-kmp-android:0.44.8`. The jar bundles `linux-x86-64/libnostr_sdk_ffi.so` (glibc and musl) plus aarch64 builds, and is 58 MB. |
| Crypto | `com.google.crypto.tink:tink` | 1.24.0 | JVM Tink. HPKE and Ed25519 are the same primitives as `tink-android`. Desktop keyset storage is our job (no Keystore). |
| Encrypted DB | `io.github.willena:sqlite-jdbc` (sqlite-jdbc-crypt) | 3.53.4.0 | Apache-2.0. Ships `natives-linux` and supports the SQLCipher cipher scheme via URI/pragma parameters. |
| Secret storage | `com.github.javakeyring:java-keyring` | 1.0.4 (last release 2023-08) | One API covering the Linux Secret Service (GNOME/KDE) **and the macOS Keychain**. The README warns that protection depends on OS and packaging: on macOS the Keychain ACL is tied to the app's code signature. Alternative for Linux only: `de.swiesend:secret-service` 3.0.0-alpha. Decided in LP-D05. |
| UI | Compose Multiplatform Gradle plugin `org.jetbrains.compose` | 1.12.2 stable (1.13.0-beta01 newest) | `nativeDistributions` wraps `jpackage` and supports `Deb`/`Rpm` on Linux. **AppImage is not a built-in target**, so WP10 runs `appimagetool` on the jpackage app image. |
| WebRTC (desktop) | `dev.onvoid.webrtc:webrtc-java` | 0.20.0 | Classifier jars for `linux-x86_64`, `linux-aarch64` and `linux-aarch32`. Since 0.17.0, PulseAudio, udev and D-Bus load at runtime only when used (jrtc.dev get-started). Requires X11/GLib/GBM/libdrm runtime libraries. Supports **encoded transforms** (insertable streams), which makes E2EE possible (jrtc.dev/guide/advanced/encoded-transforms). |

**macOS coverage of the same libraries (verified 2026-10-23, Maven Central file listings):**
- `nostr-sdk-kmp-jvm` 0.44.8 bundles `darwin-aarch64` and `darwin-x86-64` `libnostr_sdk_ffi.dylib`.
- `webrtc-java` 0.20.0 publishes `macos-aarch64` and `macos-x86_64` classifier jars.
- `sqlite-jdbc` 3.53.4.0 publishes `natives-mac`.
- Compose `nativeDistributions` builds `Dmg` targets. jpackage can't cross-build, so the `.dmg` is built on a Mac.
  A GitHub Actions macOS runner is enough.

**Unsigned macOS apps (unverified, owned by WP18):**
- Apple Silicon requires at least an ad-hoc signature. Expected: jpackage ad-hoc signs when no identity is
  configured. Check with `codesign -dv`.
- Gatekeeper blocks the first open of a quarantined download. The user allows it once via System Settings → Privacy
  & Security → Open Anyway, or with `xattr -d com.apple.quarantine`.
- Microphone access needs `NSMicrophoneUsageDescription` in Info.plist.
- Expected side effect: with ad-hoc signatures, the microphone (TCC) and Keychain grants may be asked again after
  every update, because the code identity changes.

Sources: `repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml` for each row;
https://jrtc.dev/guide/get-started; https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html.

## C. Android calling stack (verified)

- **WebRTC library**: Google no longer publishes `org.webrtc:google-webrtc`. The maintained prebuilt is
  `io.github.webrtc-sdk:android`, latest **150.7871.04** (2026-10-23). It is LiveKit's build and includes the
  `FrameCryptor` E2EE API. `io.getstream:stream-webrtc-android` (1.3.10, last updated 2025-09) is the alternative.
  Sources: https://github.com/webrtc-sdk/android and Maven metadata.
- **Telecom**: Jetpack `androidx.core:core-telecom` (stable 1.0.x, 1.1.0-rc01 newest) for self-managed VoIP calls.
  It needs the `MANAGE_OWN_CALLS` permission. Source: https://developer.android.com/develop/connectivity/telecom/voip-app/telecom
- **Foreground service** (Android 14+ target): type `phoneCall` with `FOREGROUND_SERVICE_PHONE_CALL`. The app
  targets SDK 36, so this applies. Microphone access while in the background needs the FGS to be running. Source:
  https://developer.android.com/about/versions/14/changes/fgs-types-required
- **Full-screen incoming-call UI**:
  - Since Android 14, `USE_FULL_SCREEN_INTENT` is granted by default only to apps whose core function is calling or
    alarms. Google Play revokes it for other apps.
  - Relay ships outside Play (`rebuild/DISTRIBUTION.md`), so the default grant is expected to remain. Even so, the
    app must check `NotificationManager.canUseFullScreenIntent()` and fall back to a heads-up `CallStyle`
    notification.
  - **Unverified on a real Android 14+ phone, owned by WP13.** Source:
    https://developer.android.com/about/versions/14/behavior-changes-14
- **Background start**: a high-priority FCM message temporarily exempts the app from FGS background-start
  restrictions. This is the path for ringing a sleeping phone. **Unverified for this app's FCM configuration, owned
  by WP13.**

## D. Cloudflare Realtime (verified, developers.cloudflare.com, pages dated 2026-09/10)

- **Pricing**: SFU and TURN cost $0.05/GB of **egress from Cloudflare**. The **first 1,000 GB a month is free and
  shared** between SFU and TURN. Ingress is free. TURN↔SFU traffic is not charged twice.
  (/realtime/sfu/platform/pricing/)
- **Connection API**:
  - Base URL `https://rtc.live.cloudflare.com/v1`, with header `Authorization: Bearer <App Secret>`. The secret must
    stay on the backend.
  - `POST /apps/{appId}/sessions/new`
  - `POST …/sessions/{id}/tracks/new` (`location: local` publishes; `remote` plus the publisher's `sessionId` and
    `trackName` subscribes)
  - `PUT …/renegotiate`, `PUT …/tracks/close`, `GET …/sessions/{id}`
  - **No rooms**: the application owns discovery and authorization. (/realtime/sfu/api/,
    /realtime/sfu/concepts/sessions-tracks/)
- **Limits** (/realtime/sfu/platform/limits/):
  - 50 API requests a second **per session**, and ≤64 tracks per request. No fixed cap on tracks per session.
  - A track with no incoming packets for **30 s is garbage-collected**.
  - A disconnected session has a 30 s reuse window.
  - Operations wait ≤5 s for transport.
  - Audio codecs: Opus, G.711.
- **Connectivity**: STUN `stun.cloudflare.com:3478`. TURN is optional for networks that need it.
- **TURN credentials**: `POST /v1/turn/keys/{key_id}/credentials/generate-ice-servers` with a Bearer TURN API token
  and `{"ttl": seconds}`.
  - It returns `iceServers` (UDP 3478/443, TCP 3478/80, TLS 5349/443) and credentials can be revoked.
  - The key must stay server-side. Filter out the port-53 URL when not trickling.
  - (/realtime/turn/generate-credentials/)
- **E2EE**: Cloudflare's own Orange Meets added MLS-based E2EE over the same SFU (blog "Orange Me2eets"). So the SFU
  forwards frames that are encrypted at the frame level without needing to read them.
- **Unverified**:
  - whether enabling Realtime on a Free account requires a payment method;
  - whether the App Secret can be scoped.
  - Both are owned by WP02.

## E. Estimates (re-measure in the named WP)

**Voice bandwidth.**
- Opus voice runs at 24–32 kbit/s. With RTP, SRTP and UDP overhead at 50 packets a second it is about
  **48 kbit/s on the wire, or 21.6 MB per stream-hour**.
- On the SFU, each listener receives N-1 streams. Room egress per hour is N·(N-1)·21.6 MB:
  - 2 people ≈ 43 MB;
  - 5 people ≈ 432 MB;
  - 10 people ≈ 1.9 GB;
  - 17 people (the largest Relay group) ≈ 5.9 GB.
- The free 1,000 GB therefore covers about **2,300 five-person room-hours** or **170 full-group room-hours** a
  month, before DTX silence suppression (which lowers it further).
- 1:1 calls cost nothing unless they use TURN, and then about 43 MB an hour.
- Owner: WP02 and WP16.

**Signal frame size for SDP.**
- A libwebrtc audio-only offer is about 2–4 KB, more if it carries bundled candidates.
- The wrapping layers add size: inner Tink HPKE plus base64 (×1.33), then NIP-44 padding plus base64 (×1.33) for
  the seal, then again for the wrap, plus JSON.
- So 3 KB of SDP becomes **about 11–12 KB per frame**. That fits under 16 KiB but with little margin, and not when
  candidates are inlined.
- Plan: trickle ICE, deflate the SDP before sealing, and raise `MAX_SIGNAL_BYTES` to 32 KiB only if the measured
  frame size requires it (server env var, no code change).
- Owner: WP02 and WP11.

**Rows written with linked devices.**
- One queue per identity, so a delivery still costs about 5.3 rows.
- Each extra device adds about 1 row per ack batch (its cursor row).
- Sent-copies to one's own identity cost one delivery each.
- Example: 10 users × 100 messages, 3 of them with desktops: 27k + 300 sent-copies × 7.3 + about 1k cursor writes
  ≈ **30k rows a day**, or 30% of the free limit.
- Each 25 MiB file part adds about 26 rows plus 1 per recipient.
- Owner: WP04, measured with `EXPOSE_METRICS=1`.

**Group call control-plane cost.**
- About 6 Worker plus DO requests per join (ice, join, sessions/new, tracks/new local, tracks/new remote,
  renegotiate), plus about 1 per other join or leave.
- DO SQLite writes: about 2 per join or leave if room state is persisted (LP-D13 keeps it to that).
- Negligible next to messaging.
