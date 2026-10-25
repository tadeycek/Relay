# WP14 desktop 1:1 calls — foundation slice (PARTIAL)

**Status: PARTIAL; WP14-AC1–AC4 open.** No app entry-point, UI or mailbox wiring was changed. This is an injectable host/native peer foundation, not a connected production call path. Branch `feat/linux-port`; no commit, push or deployment.

## Scope and API

- New `DesktopCallHost` wraps core `CallRuntime` on a single-thread executor. Epoch-ms clock, `SecureRandom` nonces/ids, verified-contact sender gate before `onWire`, sticky `Ended`, explicit close/hangup. Core `CallRuntime` drains ordered effects. Caller must provide the authenticated NIP-17 **seal sender**, not an untrusted body field. Callback implementations must post via `host.post(event)`; create peers/turn/timers with a late-bound host reference or equivalent serial enqueue.
- `DesktopCallSignalQueue` preserves per-recipient ordering and reports failed sends. Its injected `deliver` must send `LIVE` over the encrypted mailbox signal frame to every recipient socket; for `LIVE_AND_STORED_URGENT` it must additionally make an urgent stored encrypted invite. It is **not wired** to `DesktopMailboxTransport` (which currently cannot send ephemeral signals). Signal overflow rejects; the caller must surface that failure.
- `DesktopCallTurnPort` accepts only injected authenticated fetches, validates returned expiry against epoch clock with five seconds remaining, ignores replies after cancellation/replacement/close, emits failure for fetch exceptions. Credentials are not logged. The HTTP GET `/v1/call/ice` and actual expiry parser are still integration seams; the authenticated fetch must enforce its own transport bounds and return a cancel handle. Ended cancels the port.
- `DesktopCallPeer` uses real webrtc-java 0.20.0 peer/factory/native audio module created only by `OpenPeerConnection`; default device enumeration/selection uses actual `AudioDeviceModuleBase.getRecordingDevices/getPlayoutDevices` and `setRecordingDevice/setPlayoutDevice`, validated by descriptor. It queues up to 256 remote candidates per generation until the asynchronous remote SDP set-success callback, drops stale generations, cleans track/source/sender/receivers/transceivers/factory/audio module on close/failure. `CustomAudioSource` and `HeadlessAudioDeviceModule` are injection seams for generated PCM tests; no physical microphone was opened in the test.

## Test-first and execution

Test-first missing-host compile failure: `/home/tadej/.hermes/cache/scratch/wp14-red.log` (`DesktopCallHost`, `DesktopCallSignalQueue` absent). First native run encountered an actual `UnsatisfiedLinkError` because the test constructed `CustomAudioSource` before native loading; fixed the fixture to construct the source inside the peer's post-module factory hook. TURN exception test failed before catch was implemented: `/home/tadej/.hermes/cache/scratch/wp14-turn-throw-red.log`. Expiry guard test established RED by **mutation**, not first-run failure: replaced `expiry > nowMs() + 5_000` with `expiry > 0`, observed the assertion fail at `DesktopCallTurnTest.kt:24` in `/home/tadej/.hermes/cache/scratch/wp14-turn-red.log`, restored it and reran. Reentrant serial-executor shortcut likewise established RED by mutation: removed the worker-thread fast path, the UI callback hung and JUnit timed out at `DesktopCallHostTest.kt:50` (`wp14-reentrant-red.log`), restored it; a concurrent WP08 mid-edit first caused an unrelated `DesktopMedia.kt` compilation failure, then the rerun reached the timeout assertion. The final suite below ran after restoring the shortcut.

Command (the init script redirects **all** project build directories to absolute scratch; unique project cache, build cache disabled):

```sh
JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 PATH=/home/tadej/tools/jdk-21.0.12.1+1/bin:$PATH \
./gradlew :desktop:test --tests 'com.relay.app.desktop.DesktopCall*' --rerun-tasks --no-daemon \
  --no-build-cache --project-cache-dir /home/tadej/.hermes/cache/scratch/wp14-project-cache \
  -Pkotlin.incremental=false -Dorg.gradle.java.home=/home/tadej/tools/jdk-21.0.12.1+1 \
  -I /home/tadej/.hermes/cache/scratch/wp14-init.gradle --console=plain \
  > /home/tadej/.hermes/cache/scratch/wp14-final.log 2>&1
```

Result (superseded by the independent-review fix round below): 5 tests at the first foundation commit point.

## Independent-review blocker fixes (B1–B5 + teardown)

Final run: same command with the absolute-redirect init script `wp14-fix-init.gradle` (all project buildDirs → `/home/tadej/.hermes/cache/scratch/wp14-fix-build`, `--project-cache-dir …/wp14-fix-project-cache`, no build cache, `--rerun-tasks`, `--no-daemon`, JDK 21.0.12.1). **BUILD SUCCESSFUL; 13 tests, 0 failures, 0 errors, 0 skips** from JUnit XML copied to `…/scratch/wp14-full2-xml` (Host 6, Native 5, Turn 2); log `wp14-full2.log`.

| # | Fix | RED evidence |
|---|---|---|
| B1 | Every async SDP/offer/answer callback is fenced by `alive(session, generation)` (session current, not closed, same generation) before any native op or event; gen-1 remote-offer success arriving after gen 2 began issues no `createAnswer`/`setLocal`/event. Test `staleRemoteOfferCallbackCannotCreateGenerationOneAnswer` holds gen 1's callback, starts gen 2, runs it, waits 1.5 s, asserts no gen-1 `LocalDescription` and no `PeerFailed`. | First-run failure `wp14-b12-teardown.log`; fence-removal mutation (`alive` ignoring generation) fails it (`wp14-m1b.log`). |
| B2 | Callbacks run their check-and-effect under a per-session lock; `close()` marks the session closed under the same lock before disposing, so no receiver/sink/native op/event is added after close. Test `heldOnTrackCannotAttachAfterClose` holds `onTrack` across `close()`. | First-run failure `wp14-b12-teardown.log`; removing the closed check makes the held `onTrack` attach a sink to a disposed track and the **test JVM aborts (exit 134)** (`wp14-m2b.log`). |
| B3 | `DesktopCallSignalQueue` completion is one-shot per item (CAS), so deliver-callback-then-throw and double invoke cannot pop the next item or report failure. | `wp14-b3-red.log` (both tests failed first run), green `wp14-b3-green.log`. |
| B4 | `DesktopCallTimers` per-arm token; a cancelled callback already running cannot post after cancel+rearm of the same key. | `wp14-b4-red.log` first-run failure, green `wp14-b4-green.log`. |
| B5 | Native test asserts inbound audio RTP packets>0 and bytes>0 on **both** endpoints (via `getStats` inbound-rtp), >1000 decoded samples, and mean |sample| > 100 in both directions, plus silent-source negatives in each direction (A silent → B energy must be rejected; B silent → A rejected, RTP still flows). Normal JVM/process exit after the native tests is **not asserted** (claim withdrawn in the round-3 review; the Gradle worker finishing is not a dedicated exit test). | Mutation (not first-run RED): making source A transmit a tone in the "silent A" test fails `silentSourceAIsRejectedDespiteComfortNoise` (`wp14-mut-M3-silentA-plays.log`). The B-direction negative was not separately mutated. |
| teardown | `close()` runs every step (hang-up, peer close, turn, timers, signal, peer) in its own guard, always shuts the executor down, rethrows the first failure with the rest suppressed. Test `closeReleasesRemainingPortsWhenUiThrows` (UI throws on `Ended`). | `wp14-teardown-red.log` first-run failure. |

Fixture finding: the fixture **can** read RTP stats (`inboundAudioRtp()` in `DesktopCallPeer`), so the RTP assertions are real. Opus comfort noise is covered by the energy threshold plus negatives, not by "nonzero".

Defect found and fixed while doing B2: the first B2 design held the peer monitor in callbacks; a synchronous native `addIceCandidate` made under that monitor waited on the signaling thread, which was blocked in `onIceCandidate` waiting for the monitor — a **native deadlock** (jstack-confirmed, hung the suite). Rule now: callbacks never take the peer monitor and public operations never hold the per-session lock across native calls. Callback failures emit `PeerFailed` only (the engine's `ClosePeerConnection` effect then disposes from the serial host thread, never inside a native callback). Native tests carry 60 s timeouts so a future hang fails instead of stalling.

## Third independent re-review (B1, B2 + S1/S3/S5)

Test-first; same isolated absolute-scratch Gradle command as above (`wp14-r3-init.gradle`, build dir `…/scratch/wp14-r3-build`, `--rerun-tasks --no-daemon --no-build-cache`, JDK 21.0.12.1). Seams added before RED (no behavior change): `DesktopCallPeer(makeFactory=…)`, `DesktopCallHost.post(event, valid)`, `DesktopCallTimers(post:(CallEvent,()->Boolean)->Unit)`.

| ID | Fix | RED → GREEN |
|---|---|---|
| B1 | `onSerial` converts `RejectedExecutionException` to `IllegalStateException`; `close()` cancels every `Future` returned by `shutdownNow()` so a queued `.get()` throws `CancellationException`; `post()` swallows `RejectedExecutionException`. Test `callerQueuedBehindSlowCloseDoesNotHangWhenCloseShutsExecutor`: worker parked in `peer.close(callId)`, then in a UI hook; caller `hangUp()` queued; `close()` runs `shutdownNow`; victim must finish ≤2 s. | RED `wp14-r3-red.log` ("caller queued behind close() hangs forever"); GREEN `wp14-r3-green.log`. `postAfterCloseNeverThrows` passed before the fix too (guard-only; the narrow check→execute race is not deterministically reproducible). |
| B2 | `open` catches `Throwable`, runs the same cleanup, rethrows only `VirtualMachineError`, otherwise `fail(callId)` → `PeerFailed`. Tests (`DesktopCallPeerOpenTest`): `UnsatisfiedLinkError` from audio module, `NoClassDefFoundError` from factory, `UnsatisfiedLinkError` from `makeSource` (module **and** factory dispose counts = 1), OOM still propagates. | RED: 3 of 4 failed with the raw Error escaping `open`; OOM test passed pre-fix (already propagated); GREEN 4/4. |
| S1 | Timer fire posts a validity guard tied to a per-key generation (bumped on start/cancel/close); the host drops the queued event when the guard is false. Tests `firedTimerEventIsStaleOnceCancelledOrRearmed`, `postedEventWhoseGuardIsFalseIsDropped`. | RED both; GREEN. |
| S3 | `duplicateFailedCompletionReportsExactlyOneFailure`: duplicate `ok=false` plus a late `ok=true` yields one `failed()` and does not consume the next item. | Passed on first run (strengthening of already-correct CAS); no RED. |
| S5 | `onIceCandidate` already stamps `s.gen` read under the session lock; the native API gives no per-candidate gather generation, so a candidate gathered for gen N but delivered after gen N+1 was set is stamped N+1 (residual, not fixable at this layer). Native tests now assert every transferred candidate carries gen 1. | Assertion only; no code change. |

Result: **22 DesktopCall tests, 0 failures, 0 errors, 0 skips** from JUnit XML (Host 11, Native 5, Open 4, Turn 2); baseline was 13. **WP14-AC1–AC4 remain unrun; evidence stays PARTIAL.**

Scope of the native evidence: two local headless peers with generated PCM. Not a virtual-PulseAudio/microphone test, not audible, not phone↔desktop; **WP14-AC1–AC4 remain unrun and this slice stays PARTIAL.**

## Dependency provenance and release gate

`dev.onvoid.webrtc:webrtc-java:0.20.0` API and explicit `linux-x86_64` native classifier both use `transitive=false`. The cached POM contains a classifier self-dependency; do not resolve it transitively. Compared the cached jars and POM against the WP02 spike verification metadata and audit:

| Artifact | SHA-256 |
|---|---|
| `webrtc-java-0.20.0.jar` | `7c2b8349fd1a628b7ced59106ab4dd3314dd0a0e0944ea0e740f84ac9c4ff349` |
| `webrtc-java-0.20.0-linux-x86_64.jar` | `b2f58cf5e8d27d0488ab3c5ab6e03261549850c92ed1e73527a138510df43137` |
| `webrtc-java-0.20.0.pom` | `011ac8ede781ef06de80ee7b79b77a6b662488f8b7022db95b283e4087170654` |
| `webrtc-java-parent-0.20.0.pom` | `7d270e4aedbea3e91697ad9c3d6f2160617d4336c05b71f0c259f8fe3c6b5743` (WP02 verification metadata) |

Parent POM `v0.20.0` SCM: https://github.com/devopvoid/webrtc-java/tree/v0.20.0 ; declares Apache License 2.0, http://www.apache.org/licenses/LICENSE-2.0.txt . The native jar bundles `META-INF/licenses/webrtc/LICENSE.md` and `PATENTS`, `libva/COPYING`, `nvdec/LICENSE`, `nvenc/LICENSE` (WP02 audit `linux-port/spikes/calls/evidence/dependency-audit.json`). **Redistribution/license and native-runtime security approval remain open**, not inferred from successful local tests.

## Integration / not run

- `DesktopMailboxTransport`: encrypted signal frame routing and stored urgent invite fanout, authenticated seal sender, TURN GET, transport failure path and incoming routing before the ordinary chat inbox. WP08 agent owns that file; it was not touched here.
- `DesktopEngine/Main/DesktopUi/DesktopChatUi`: composition root, tray/ringing/voice bar, actual audio device pickers, window-lifetime/exit control and local call-event record. None touched here.
- WP14-AC1 phone↔desktop TURN-only across networks, AC2 same-identity multi-device ring cancellation within two seconds, AC3 CI-like **virtual PulseAudio** two-desktop test, AC4 phone Wi-Fi→mobile recovery all **not run**. No live provider, physical microphone/speaker/headset, real device picker, package launch or macOS test. No UI/transport claims.
- Native test uses local media transport (WebRTC DTLS-SRTP), not the further frame-E2EE acceptance gate; native distribution packaging was not run. Opus bitrate/DTX/FEC policy has not been applied to the native SDP yet and remains open before production wiring.
