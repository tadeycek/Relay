# WP02 follow-up — actual Android↔Linux native generated audio

Date: 2026-10-23. Branch: `feat/linux-port`. **Local cross-platform media proof PASS; WP02 as a whole remains partial.**
No commit/push/merge, canonical WP/task/evidence edits, root app build, production call changes or provider access
were performed by this follow-up. All repository changes are under `linux-port/spikes/calls/`.

## What actually ran

- **One Linux peer**, `relay.spike.CrossPlatformProbe`, using native `dev.onvoid.webrtc:webrtc-java:0.20.0`.
- **One Android peer**, `org.relay.callsprobe.CrossPlatformRun`, using native
  `io.github.webrtc-sdk:android:144.7559.15`, installed as `org.relay.callsprobe`.
- Independently launched **emulator-5556**, AVD `localbeat_wp11_16k`, API 36 / x86_64;
  actual `getconf PAGE_SIZE` = **16,384**. This does not establish minimum-API or physical-phone compatibility.
- Parent-owned emulator-5554 (`localbeat_wp11_4k`) was only queried for its AVD name before selecting the other AVD.
  No application, data, mapping or process on emulator-5554 or any real device was changed.
- Native offer/answer and gathered ICE travel through a bounded length-prefixed, host-loopback TCP bridge and
  one temporary `adb reverse` mapping. Native ICE/DTLS-SRTP media travels separately, not as PCM through that bridge.
  No configured public STUN/TURN/SFU service, provider credentials, Worker, firewall change or cloud account.
- Linux sends generated 440 Hz signed 16-bit, 48 kHz mono PCM for 800 × 10 ms ticks. Android sends generated
  660 Hz PCM via the pinned SDK's `setAudioBufferCallback`, with a 10 ms clock in the callback.
  `adm.setAudioRecordEnabled(false)` is called **before factory creation**; no microphone permission is declared.
  The app requires normal `INTERNET` and `ACCESS_NETWORK_STATE` permissions, not a runtime microphone grant.
- Android's speaker path is muted and Linux is headless. Native remote-track sinks count decoded PCM samples,
  nonzero samples and squared sample energy. Each platform also obtains its real inbound-audio RTP stats.

## Decisive final results

Strict rerun builds: desktop `installDist probe`: **BUILD SUCCESSFUL, 5 tasks executed**;
Android `assembleDebug`: **BUILD SUCCESSFUL, 34 tasks executed**.
The original two-Linux-peer regression still decoded nonzero PCM both ways, delivered 2 data messages and exited
normally with `cleanup=COMPLETE`. This regression is separate from the Android↔Linux proof below.

Final restored-positive cross-platform run (after both silent-source mutations):

| Receiving platform | Inbound RTP packets | Inbound RTP payload bytes | Decoded PCM samples | Nonzero samples | RMS sample amplitude |
|---|---:|---:|---:|---:|---:|
| Android (from Linux) | 353 | 28,444 | 344,160 | 336,389 | 8,394.123 |
| Linux (from Android) | 404 | 33,044 | 385,440 | 384,395 | 8,103.122 |

Both native peer states were `CONNECTED`; Android generated 707 callback frames at the measurement point,
Linux pushed 800 frames. Linux gathered 16 candidates; Android gathered 2 in this run. Gathered local offer/answer
sizes were 3,096 / 1,366 UTF-8 bytes. Raw SDP/ICE is neither included here nor persisted as evidence.
Counts vary with negotiation timing, NAT interfaces, scheduling and codec startup.

Acceptance requires **both distinct platform results**, connected state, positive inbound audio RTP packets/bytes,
positive generated-frame and nonzero-sample counts, at least 24,000 decoded samples, and RMS **greater than 500**.
Android computes/logs its own receive PASS/FAIL; the host checks both actual result rows and native cleanup.

### Tests and real negative mutations

- Acceptance unit tests: **4 passed, 0 failed, 0 skipped**. An initial deliberately unimplemented acceptance
  function failed `test_both_platforms_receive`; implementing the gate made the suite green.
- Native cross-platform suite: **3 passed, 0 failed, 0 skipped**: positive exchange, silent Android source,
  silent Linux source. These are real native-media runs, not mocked transport or synthesized stats.
- Silent Android still delivered **402 RTP packets / 14,159 bytes** to Linux, but Linux RMS fell to **0.108**;
  the exact Linux decoded-energy assertion rejected the call.
- Silent Linux still delivered **353 RTP packets / 12,246 bytes** to Android, but Android RMS fell to **0.082**;
  Android logged `android_receive=FAIL` and the exact Android decoded-energy assertion rejected the call.
- Silence still produced **4,355 / 2,300 nonzero decoded samples** (codec startup/low-level noise), so a bare
  “some nonzero PCM” assertion would have falsely accepted these runs. The energy threshold is intentionally
  stronger. Mutation tests assert the exact receiver failure, and separately require both RTP connections to work;
  an emulator/setup error cannot count as an expected rejection.
- Mutations are runtime source flags only; no code weakening remains. A separate default-source run afterwards
  passed again; see `cross-platform-restored-positive.json` and `.txt`.
- Existing live-provider guards: **5 tests, 4 passed, 1 skipped**, no failures. The skipped case still requires
  real opted-in SFU credentials; it was not counted as a provider pass.

## Reproduce independently

From `/home/tadej/repos/Relay`, stay on `feat/linux-port`; do not merge. Build only these standalone projects:

```sh
export JAVA_HOME="$HOME/tools/jdk-21.0.12.1+1"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew -p linux-port/spikes/calls --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" --dependency-verification strict \
  --console=plain --rerun-tasks installDist probe
./gradlew -p linux-port/spikes/calls/android --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" --dependency-verification strict \
  --console=plain --rerun-tasks assembleDebug
```

Confirm port 5556 is not in use and `localbeat_wp11_16k` is not the parent-owned running AVD. Launch this command
as an owned **terminal background process** (not via browser tools, and never on port 5554):

```sh
"$ANDROID_HOME/emulator/emulator" -avd localbeat_wp11_16k -port 5556 \
  -no-window -no-audio -no-boot-anim -no-snapshot -memory 1024 -cores 2 \
  -gpu host -skin 480x800
```

After `adb -s emulator-5556 shell getprop sys.boot_completed` returns `1`, run promptly:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/test_cross_platform.py -v
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/test_live_guards.py -v
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/check_cross_platform_mutations.py
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/cross_platform.py --skip-install \
  --output linux-port/spikes/calls/evidence/cross-platform-restored-positive.json
"$ANDROID_HOME/platform-tools/adb" -s emulator-5556 reverse --list
"$ANDROID_HOME/platform-tools/adb" -s emulator-5556 emu kill
```

`cross_platform.py` defaults to installing the exact just-built APK and starting the experiment itself. The native
suite installs once, then reuses that APK for both mutations. `--skip-install` is only for immediate repeat runs.
To reproduce one negative manually, use `--silent android` or `--silent linux`: runner exit **1** with the exact
receiver energy assertion is expected; the native suite verifies that detail rather than accepting any exit 1.
The runner refuses any serial except emulator-5556 and requires the expected AVD name. It uses a random free
loopback port, `reverse --no-rebind`, verifies creation, removes only its exact mapping, and reads back removal.
SDP frames are bounded at 65,536 bytes; no full native stats/SDP/candidate objects are written to evidence.

## Artifacts / pins

- APK: `linux-port/spikes/calls/android/build/outputs/apk/debug/relay-calls-android-probe-debug.apk`
  (**47,906,688 bytes**), SHA-256 `701b6cc675582c49a27fe2c3499d4f127520c78172f263d3b8946316bbe3cb79`.
- Desktop JAR: `linux-port/spikes/calls/build/libs/relay-calls-spike.jar` (**24,222 bytes**), SHA-256
  `013ab87427618c7012ad8b84a34277ab7a881d44c4ffefa695a75751eca6e6b7`.
- Installed desktop runtime classpath: `linux-port/spikes/calls/build/install/relay-calls-spike/lib/*`.
- [Artifact/pin audit](evidence/cross-platform-artifacts.json): desktop verification metadata remains
  **2 components / 4 SHA-256 entries**, Android **180 components / 304 SHA-256 entries**.
  Both verification XML files are byte-for-byte identical to branch HEAD. **0 pins added, 0 removed**;
  no new dependencies, regenerated verification metadata, root build configuration or root dependency changes.
- Actual per-platform metrics: [positive](evidence/cross-platform-positive.json),
  [silent Android](evidence/cross-platform-silent-android.json), [silent Linux](evidence/cross-platform-silent-linux.json),
  [restored positive](evidence/cross-platform-restored-positive.json).
- Sanitized execution transcripts: [native suite](evidence/cross-platform-native-checks.txt),
  [restored positive](evidence/cross-platform-restored-positive.txt), [unit suite](evidence/cross-platform-unit.txt),
  [existing guards](evidence/cross-platform-existing-guards.txt).
- Raw native diagnostics are private ignored `.private/cross-platform-*.log`, not publication artifacts.
  No generated media, identity keys or provider credentials are staged. The signalling data exists only in memory.

## Failures found and cleanup

1. Initial Android peer creation aborted in JNI because its network monitor needed `ACCESS_NETWORK_STATE`.
   Targeted process logs showed that exact `SecurityException`; adding the normal permission fixed the real call.
   `INTERNET` alone is insufficient for the SDK's default network monitor.
2. Activity recreation could start a second probe worker. A static atomic running guard prevents overlapping
   native peers; the runner rejects an Android worker-failure marker. It also waits for Android's native cleanup
   marker after the host acknowledgement instead of racing cleanup with immediate log readback.
3. Several long-lived second-emulator attempts were killed by their **4 GiB per-terminal memory cgroup**,
   not host-wide exhaustion. Kernel journal entries explicitly identify `CONSTRAINT_MEMCG` and the owned qemu PID.
   Reduced guest RAM alone did not eliminate it. The final run used host GPU, a small skin and a fresh emulator;
   all three native cases plus restored-positive passed before stopping it. This is a short proof, **not a
   long-duration emulator stability claim**. A prior interrupted rerun is preserved in
   [interrupted transcript](evidence/cross-platform-native-checks-interrupted.txt), not relabelled as passing.
4. Final runner readback: **no reverse mappings, no `org.relay.callsprobe` PID** on emulator-5556.
   The owned emulator was then stopped with `adb -s emulator-5556 emu kill`; final device enumeration contained
   only the parent's still-running emulator-5554. Each successful Linux native process exited normally, without
   `System.exit`, and both native cleanup markers were observed before force-stopping the test APK.

## Still not proven

No physical microphone, speaker/headset listening, audio quality, real-phone background/permission flow,
TURN relay or blocked-UDP TLS443 fallback, live SFU publish/subscribe/egress, Cloudflare card/account requirement,
group mixing/load/mute expiry, encoded-frame E2EE/FrameCryptor interop, production authentication/replay/glare
contract, final Android encrypted signal-size integration, cloud/media security guarantee or macOS compatibility.
This closes only the **local generated-audio Android↔Linux portion** of WP02-AC1, not all of AC1 or WP02.
