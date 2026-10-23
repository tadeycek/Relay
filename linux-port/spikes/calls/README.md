# WP02 — standalone native calls spike

**Verdict: PARTIAL.** This is a runnable Linux feasibility probe, not the Relay call engine and not evidence of Android↔Linux audible calls or Cloudflare media. See [canonical evidence](../../evidence/WP02.md) and the earlier [delegate snapshot](EVIDENCE-DRAFT.md) for the acceptance-criterion matrix and actual measurements.

## Run locally (no credentials)

From the Relay checkout:

```bash
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1
export TMPDIR=/home/tadej/.hermes/cache/scratch
timeout 90 ./gradlew -p linux-port/spikes/calls --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" --dependency-verification strict \
  --console=plain --rerun-tasks clean probe
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/test_live_guards.py -v
```

Requires Linux x86_64, JDK 21, and the host native runtime's audio libraries. The checked-in Gradle dependency verification metadata pins both 0.20.0 jars and their POMs. `transitive=false` is intentional on **both** dependency declarations: the upstream POM has a self-dependency using a Maven platform property; choose the native classifier explicitly rather than resolving that platform property as a dependency. The parent checkout's Gradle 8.14 wrapper is reused with `-p`; root settings/dependencies are not loaded. There is no Android plugin in this project.

The probe:

- Loads the actual Linux JNI library and enumerates real capture/playout devices without starting recording.
- Creates two independent native PeerConnectionFactories with headless audio modules, no configured public ICE servers, and exchanges real offers, answers and gathered ICE candidates in memory.
- Opens a data channel from each peer and verifies delivery of both distinct messages.
- Pushes three seconds of generated 48 kHz, mono, signed 16-bit 440/660 Hz audio in both directions; asserts nonempty encoded transforms and nonzero decoded PCM on both remote audio tracks. This is not hardware loopback or listening evidence.
- Measures actual UTF-8/zlib/base64 SDP and actual encoded audio payload sizes. Proposed `TYPE:CALL` bodies have fixed measurement metadata and real SDP/ICE. ICE batches contain at most 20 candidates. These are **not** signed Nostr gift wraps, Tink ciphertexts, server wire frames or a production-authenticated codec.
- Asserts zlib round-trip, rejects corrupt streams and >64 KiB decompression, and caps gathered candidates at 64 per peer.
- Closes peers and explicitly disposes acquired track/receiver/sender/transceiver/channel references before disposing factories. The initial prototype otherwise left native JVM-attached threads alive; final runs exit normally without `System.exit`.

By default only sizes and device names are logged; SDP, candidates, credentials, identity keys and media data are not persisted. Optional `RELAY_SPIKE_BODY_DIR` records proposed call bodies (including ephemeral SDP/ICE) into an existing **private, gitignored** directory for the WP01 wire-size integration; do not attach those body files to evidence. The fixed all-zero/one measurement identifiers are not keys. Measurements vary with certificates, random ICE credentials and host interfaces.

## Guarded live TURN path (NOT RUN)

`relayProbe` uses the **same** two-native-peer synthetic-audio/data-channel experiment, with `RTCIceTransportPolicy.RELAY`. It requires all of:

- `RELAY_SPIKE_LIVE=1`
- `RELAY_TURN_URL` in the exact form `turns:host:443?transport=tcp`
- short-lived `RELAY_TURN_USERNAME` and `RELAY_TURN_PASSWORD`

Supply credentials through a local secure environment workflow, never source, chat, command-line arguments or evidence. Do not enable Gradle debug logging, and do not print configuration/RTCIceServer objects (the upstream `toString()` includes credentials).

```bash
# Only after explicit owner approval and secure environment provisioning:
timeout 90 ./gradlew -p linux-port/spikes/calls --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" --dependency-verification strict \
  --console=plain --rerun-tasks relayProbe
```

The guard is checked **before native loading or network requests**. Local tests exercise disabled, missing-credential and invalid-URL cases. A future successful run would prove only desktop↔desktop relay; it does not prove Android interop, UDP-blocked fallback, audible audio, or Cloudflare account setup. It checks every gathered candidate is relay, but does not inspect selected-pair stats or independently prove the transport. No firewall changes are scripted or authorized.

## Guarded SFU control-plane smoke (NOT RUN)

`live_sfu.py` requires `RELAY_SPIKE_LIVE=1`, `RELAY_SFU_APP_ID` and `RELAY_SFU_APP_SECRET`. It creates an ephemeral unconnected session with `POST /sessions/new`, then reads that exact session back. Requests have fixed provider origin, no redirects, 20-second timeouts and 64 KiB response caps. Neither session id nor provider response nor credentials is logged. An unconnected session expires provider-side; this is not an account configuration operation. Only run with owner approval for creating a temporary session.

```bash
# Only after explicit owner approval and secure environment provisioning:
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/live_sfu.py
```

This is deliberately labelled **control-plane only**. It does not implement/test `tracks/new`, renegotiation, media publish/subscribe, dashboard egress, or FrameCryptor. The unittest suite skips the live case without opt-in; skipped is not passed. No fake provider responses are used. API reference: https://developers.cloudflare.com/realtime/sfu/api/ ; session lifecycle: https://developers.cloudflare.com/realtime/sfu/concepts/sessions-tracks/ .

## Android native probe (separate package)

After the parent released the shared emulator, the standalone `android/` project was built and run as **`org.relay.callsprobe`** on `emulator-5554` (API 36, x86_64). Neither `com.relay.app` nor `relay.spike.android` was touched. The Android app loads `io.github.webrtc-sdk:android:144.7559.15`, creates/disposes an actual native PeerConnectionFactory, enumerates platform audio devices, and detects the FrameCryptor class. It declares neither microphone nor network permission and never captures audio. This is **native initialization/device enumeration only**, not Android P2P negotiation or an audible call.

```bash
JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 ANDROID_HOME=/home/tadej/Android/Sdk \
  ./gradlew -p linux-port/spikes/calls/android --no-daemon \
  -Dorg.gradle.java.home=/home/tadej/tools/jdk-21.0.12.1+1 \
  --dependency-verification strict --console=plain --rerun-tasks assembleDebug
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 install -r \
  linux-port/spikes/calls/android/build/outputs/apk/debug/relay-calls-android-probe-debug.apk
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 shell am start -W \
  -n org.relay.callsprobe/.MainActivity
# Read only this process/tag; do not dump unrelated application logs.
PID=$(/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 shell pidof org.relay.callsprobe)
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 logcat -d --pid="$PID" -s RelayCallsProbe:I '*:S'
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 shell am force-stop org.relay.callsprobe
```

Android has its **own** Gradle verification metadata, including AGP/transitive build artifacts and the pinned AAR. It does not affect desktop dependencies. Next work requires a permission-gated Android calling endpoint, actual cross-platform SDP exchange and listening in both directions. Provider credentials/account access and explicit network-test authorization are separate prerequisites. Production call authorization, glare, expiry/replay handling, protected HPKE/gift-wrap sizes, and reviewed frame-encryption format remain unimplemented.

## Dependency / licence boundary

`evidence/dependency-audit.json` records exact resolved jar bytes/SHA-256 and matches against Maven Central SHA-256 files. This is a checksum pin, not a source/provenance audit. Java sources identify Apache-2.0; the Linux jar bundles WebRTC license/patents, libva COPYING, and NVIDIA decoder/encoder license files. Their presence is inventoried, **not** approval of every transitive native licence. Redistribution review remains open; do not assume the whole native jar is solely Apache-2.0.
