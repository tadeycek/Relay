# WP02 evidence draft — local native feasibility only

Date: 2026-10-23. **PARTIAL / NOT READY FOR REVIEW under the binding readiness gates.**

Scope: only `linux-port/spikes/calls/**`; no commits, root build/configuration edits, task-board edits or changes to canonical evidence. The parent may promote this draft after review. No credentials were supplied or persisted. No provider/account/firewall/network configuration was changed. Shared emulator use began only after the parent explicitly released it; only package `org.relay.callsprobe` was installed/launched/stopped (left installed).

## Acceptance criteria

| Criterion | Result | Exact boundary / blocker |
|---|---|---|
| AC1 Android↔Linux P2P, TURN, UDP-blocked TLS443 | **NOT SATISFIED** | Two Linux native peers exchanged data and generated audio both ways. Android native factory initialized separately. These were **not** an Android↔Linux call. No microphone/listening/phone test; live TURN credentials absent; no firewall change authorized/performed. |
| AC2 both libraries publish/subscribe, measured egress | **NOT RUN** | SFU app credentials/account absent. Guarded control-plane session smoke exists, not full SFU media implementation. `tracks/new`, renegotiation, multi-client media, dashboard billing and cost comparison remain unrun. |
| AC3 offer/answer/ICE frame sizes and signal limit decision | **PARTIAL** | Real SDP, ICE and encoded audio measured below; proposed unencrypted call bodies measured. Actual gift-wrap and inner Tink ciphertext/server signal frames **not measured**. Keep the existing limit unchanged for now; these local sizes alone do not justify increasing it and cannot close the 14 KiB wrapped-frame decision gate. |
| AC4 free allowance/payment-card requirement | **NOT RUN / UNKNOWN** | No account login or activation. No yes/no claim can be made. |
| AC5 Android FrameCryptor↔Java encoded transform via SFU | **NOT RUN** | Java pass-through encoded transform executed; Android FrameCryptor class exists. No shared frame format, AES-GCM interop, nonce/replay/epoch/rekey tests, SFU path or observed encrypted frame layout. No WP17 update (outside scope). No E2EE claim. |
| AC6 Ubuntu24/PipeWire device enumeration | **PASS, enumeration only** | Ubuntu 24.04.3 LTS x86_64, PipeWire 1.0.5. Actual JNI capture and playout lists each contain `default: Built-in Audio Analog Stereo` and `Built-in Audio Analog Stereo`. Does not verify microphone capture, playback quality or acoustic echo cancellation. |

## Real execution

JDK: Temurin 21.0.12.1+1. Gradle: parent wrapper 8.14. Linux desktop dependency: `dev.onvoid.webrtc:webrtc-java:0.20.0` plus explicit `linux-x86_64` classifier. Full host context is in `evidence/environment.json`; PipeWire readback in `evidence/pipewire-status.txt`.

From `/home/tadej/repos/Relay`:

```bash
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1
export TMPDIR=/home/tadej/.hermes/cache/scratch
# Initial checksum bootstrap (already completed; do not regenerate pins routinely):
./gradlew -p linux-port/spikes/calls --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" --write-verification-metadata sha256 probe
# Decisive clean strict verification, then a repeat:
timeout 90 ./gradlew -p linux-port/spikes/calls --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" --dependency-verification strict \
  --console=plain --rerun-tasks clean probe
timeout 90 ./gradlew -p linux-port/spikes/calls --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" --dependency-verification strict \
  --console=plain --rerun-tasks probe
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/test_live_guards.py -v
```

Both strict native runs exited **0**, `BUILD SUCCESSFUL`, with `cleanup=COMPLETE`. Each created two independent factories/peers and delivered two distinct data messages; 300 × 10 ms generated PCM chunks per sender produced **150 encoded frames each direction**. Received nonzero decoded PCM on both peers. No real microphone was opened, no speaker listened to, and no Android call participated.

| Measurement | Strict run 1 | Strict run 2 |
|---|---:|---:|
| Offer UTF-8 / zlib / base64(zlib) bytes | 1489 / 717 / 956 | 1489 / 711 / 948 |
| Answer UTF-8 / zlib / base64(zlib) bytes | 1454 / 711 / 948 | 1456 / 714 / 952 |
| Proposed offer `TYPE:CALL` body bytes | 1720 | 1708 |
| Proposed answer `TYPE:CALL` body bytes | 1712 | 1716 |
| Gathered ICE candidates, both peers combined | 40 | 40 |
| Candidate SDP min / max bytes | 115 / 147 | 115 / 147 |
| ICE batch counts | 20 / 4 / 16 | 20 / 4 / 16 |
| Proposed ICE body sizes, bytes | 4960 / 1432 / 4172 | 4964 / 1432 / 4176 |
| Encoded audio A count / min / max / total bytes | 150 / 73 / 143 / 12244 | 150 / 73 / 143 / 12244 |
| Encoded audio B count / min / max / total bytes | 150 / 71 / 141 / 12225 | 150 / 71 / 141 / 12225 |
| Received PCM bytes A / B | 300480 / 301440 | 300480 / 301440 |
| Nonzero PCM bytes A / B | 286238 / 286004 | 286238 / 286004 |

These are actual encoder payload lengths, **not network/SRTP/TURN billing bytes**. Proposed call bodies use fixed synthetic metadata and actual SDP/candidates, not production-authenticated calls. ICE bodies are not compressed on the proposed wire; optional `zlib_json` diagnostic values do not replace the reported body size. Certificates/ICE credentials/randomness and host interfaces affect repeated measurements. Parsed numbers: `evidence/measurements.json`; raw output: `evidence/native-strict-run{1,2}.txt`.

Safety checks: zlib offer/answer round trips succeeded, malformed compressed input rejected, expansion over 65536 bytes rejected. Three actual `relayProbe` invocations without opt-in, without credentials, and with an invalid URL each failed at the expected guard before native loading (expected Gradle exit **1**, not a provider failure). Details: `evidence/turn-guards.json` and associated logs. Python suite: **4 passed, 1 live SFU test skipped**; no fake network responses. No live provider result exists.

## Android actual build and runtime

After emulator release, built standalone `android/` with AGP 8.3.2, compile/target SDK 36, minimum SDK 26 and `io.github.webrtc-sdk:android:144.7559.15`. AGP warns SDK36 is newer than its tested SDK; actual compilation/package/runtime succeeded, but no minimum-SDK compatibility claim is made.

```bash
JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 ANDROID_HOME=/home/tadej/Android/Sdk \
  ./gradlew -p linux-port/spikes/calls/android --no-daemon \
  -Dorg.gradle.java.home=/home/tadej/tools/jdk-21.0.12.1+1 \
  --write-verification-metadata sha256 --console=plain assembleDebug
JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 ANDROID_HOME=/home/tadej/Android/Sdk \
  timeout 120 ./gradlew -p linux-port/spikes/calls/android --no-daemon \
  -Dorg.gradle.java.home=/home/tadej/tools/jdk-21.0.12.1+1 \
  --dependency-verification strict --console=plain --rerun-tasks assembleDebug
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 install -r \
  linux-port/spikes/calls/android/build/outputs/apk/debug/relay-calls-android-probe-debug.apk
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 shell am start -W \
  -n org.relay.callsprobe/.MainActivity
PID=$(/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 shell pidof org.relay.callsprobe)
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 logcat -d --pid="$PID" -s RelayCallsProbe:I '*:S'
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 shell pm path org.relay.callsprobe
/home/tadej/Android/Sdk/platform-tools/adb -s emulator-5554 shell am force-stop org.relay.callsprobe
```

Strict build exit **0**; installation `Success`; activity `Status: ok`, cold launch **497 ms**. API36 x86_64 emulator reported `native_load=PASS factory_create=PASS`, **4 input / 2 output devices**, `framecryptor_class=AVAILABLE_NOT_INTEROP_TESTED`, `factory_dispose=PASS`. Manifest has no RECORD_AUDIO/INTERNET permission. APK: **47,890,200 bytes**, SHA-256 `88436f463cf49defdb8fb2ffd727364424cd48649056d8238d94eaa6b30f7b43`. Readback/output: `evidence/android-runtime.json`. This verifies native Android initialization only, not P2P or media transport. No device install/build touched either protected package.

## Dependency integrity and licences

Gradle-generated SHA-256 verification is checked in separately for desktop and Android; final builds used strict mode without regenerating it. Maven Central's published SHA-256 files independently matched downloaded artifact hashes (same publisher source, not signature/provenance review).

- Desktop API jar SHA-256: `7c2b8349fd1a628b7ced59106ab4dd3314dd0a0e0944ea0e740f84ac9c4ff349`.
- Linux native jar SHA-256: `b2f58cf5e8d27d0488ab3c5ab6e03261549850c92ed1e73527a138510df43137`.
- Android AAR SHA-256: `fc7c8d027eb33860ccbb7847988f7f942d44a92d1f575348bba6c0250cde4071`.

Exact bytes/POM digests: `evidence/dependency-audit.json`, `evidence/android-dependency-audit.json`. Android verification contains 180 build/dependency components. Java sources carry Apache-2.0; Linux native jar bundles WebRTC licence/patents, libva COPYING and NVIDIA decode/encode licences. Inventory is not a completed redistribution review. Native licences remain a G6 follow-up.

## Issues found / fixed

- Upstream desktop POM's classifier self-dependency caused the first resolution attempt to stall; explicit classifiers with `transitive=false` on both declarations resolved it.
- Initial data/audio success did not terminate the JVM: acquired sender/receiver/track/transceiver references kept native-attached threads alive. Explicit disposal fixed normal process exit; final repeated strict runs do not use forced successful exit.
- Android APK basename follows `rootProject.name`, not directory `android`; final install discovered the single actual APK and verified the installed package and process-specific runtime log.

## Not run / remaining blockers

Android↔Linux SDP/ICE/media interoperability; real phone and audible both-way calls; capture/playback permissions; hardware echo cancellation; forced Cloudflare TURN; selected relay transport verification; UDP-blocked TLS443; Cloudflare activation/card/free allowance; SFU publish/subscribe/renegotiate; per-minute billing/egress; mute/DTX track expiry and republish; 5/17-party mixing/load; actual Tink/gift-wrap/server signal frame sizes; FrameCryptor wire layout/interop/security review; macOS and minimum-OS testing. No calls engine, production security codec, SFU proxy/authorization or server limit was modified. These blockers keep WP02 below `for_review`.
