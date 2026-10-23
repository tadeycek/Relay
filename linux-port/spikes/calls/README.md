# WP02 — standalone native calls spike

**Verdict: PARTIAL WP02; local Android↔Linux native generated audio now verified in both directions.** This is not the Relay call engine, a physical listening test or Cloudflare media evidence. The new [follow-up evidence](FOLLOWUP-EVIDENCE.md) records the executable cross-platform proof and real silence mutations; canonical WP evidence and the earlier [delegate snapshot](EVIDENCE-DRAFT.md) remain historical baselines for this follow-up.

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

## Android↔Linux generated-audio proof (separate package)

`CrossPlatformProbe` creates **one** Linux native peer; `CrossPlatformRun` inside **`org.relay.callsprobe`**
creates the other peer using Android `io.github.webrtc-sdk:android:144.7559.15`. The existing `probe` task
remains the separate Linux↔Linux regression, not the cross-platform proof.

Run only on an independently owned **emulator-5556**, AVD `localbeat_wp11_16k`. Do not use emulator-5554,
`com.relay.app`, the WP01 app, or real devices. The Python runner deliberately refuses other serials/AVDs.
See [FOLLOWUP-EVIDENCE.md](FOLLOWUP-EVIDENCE.md) for exact emulator/build commands and measured results.

```bash
# After the standalone builds and own emulator boot documented in FOLLOWUP-EVIDENCE.md:
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/test_cross_platform.py -v
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/check_cross_platform_mutations.py
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/cross_platform.py
```

The manifest now declares normal `INTERNET` and `ACCESS_NETWORK_STATE` permissions, **not RECORD_AUDIO**.
There is no microphone grant/capture: Android disables `AudioRecord` before creating the factory and uses
the pinned SDK's external audio-buffer callback, paced at 10 ms, to generate 660 Hz PCM. Linux pushes
440 Hz PCM through `CustomAudioSource`. Android's output is muted; its decoded-track sink and Linux's
headless decoded-track sink count samples and energy. Actual inbound audio RTP packet/byte stats plus
at least 24,000 PCM samples, nonzero samples and RMS > 500 are required independently on both platforms.
Silent-source mutations keep RTP flowing and must fail the receiver energy assertion.

The temporary TCP bridge binds host loopback only. A single owned `adb reverse` mapping carries bounded
native gathered SDP/ICE and numeric results; native WebRTC transports media separately over local ICE.
No public STUN/TURN servers are configured. Raw native diagnostics stay in private ignored `.private/`;
only numeric/size summaries go into evidence. The runner stops its APK, removes and reads back its exact
reverse mapping, and waits for native cleanup. The emulator itself is caller-owned and must be stopped
by the caller after the experiment. Default activity launch without the explicit `cross_platform` intent
extra still runs the earlier factory/device-enumeration capability probe.

No root app build, production calling code, provider configuration, physical listening, TURN/SFU media,
cloud authorization or frame-encryption interop is exercised here.

## Dependency / licence boundary

`evidence/dependency-audit.json` records exact resolved jar bytes/SHA-256 and matches against Maven Central SHA-256 files. This is a checksum pin, not a source/provenance audit. Java sources identify Apache-2.0; the Linux jar bundles WebRTC license/patents, libva COPYING, and NVIDIA decoder/encoder license files. Their presence is inventoried, **not** approval of every transitive native licence. Redistribution review remains open; do not assume the whole native jar is solely Apache-2.0.
