# WP16 Android client — resumed partial slice

Status: **PARTIAL; not a working Android group call and not for review.** Branch `feat/linux-port`, base `ca950a6`. No commit/push/deploy, no desktop/server/core/build-script/board edits. Another agent owns desktop. This file supersedes no earlier evidence.

## Implemented / verified in this slice

- Preserved and built the inherited pure Android-host group layer: announcement codec/coordinator, roster-bound create parser, room socket state machine, SFU session abstraction, session orchestrator and UI model. These are unit-tested ports/fakes, **not wired to the app's real group-chat path**.
- Tightened live-state readback: HTTP 200 must explicitly name the expected room as well as match the roster revision/hash before the mic can open. Inherited revocation failure test fixture was repaired to include the room, matching the actual server response contract.
- An ingress replay-fence persistence failure with an active room now returns `CloseRoom`, rather than silently reporting no teardown effect.
- Added bounded, versioned, secret-free replay/ended-room snapshot JSON codec and a synchronous Android `SharedPreferences.commit()` store. Corrupt/oversized snapshots throw (fail closed); the store is **not yet installed in a real group host**. No join secret is serialized. The inherited in-memory `MemoryStore` is test-only.
- Kept `Group calls are encrypted in transit, not end to end.` as the core/UI model label. No group E2EE claim.

## Test-first / mutation evidence

- Snapshot codec behaviorless stub: 2 test failures, then 2 passes after implementation (`red-snapshot.log`, `green-slice.log` under `/home/tadej/.hermes/cache/scratch/wp16a/`).
- Missing-room readback: 1 failure on the inherited defaulting parser before fix (`red-strict.log`). Deliberate **mutation check** after the fix reintroduced the defaulting parser: the same test failed 1/1 (`mutation-room.log`); parser restored and full suite rerun. This was a mutation check, not a second test-first run.
- Active-room failed-commit effect: 1 behavioral failure before fix (`red-persist.log`), then full-suite pass.
- Earlier inherited pure tests were reported by the previous agent as 67 tests / 6 suites after repairing its fake's roster-path match. This session independently reran the final tree.

## Final isolated verification

Both invocations used `JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1`, `ANDROID_HOME=/home/tadej/Android/Sdk`, `--no-daemon -Dorg.gradle.java.home=/home/tadej/tools/jdk-21.0.12.1+1 -Dorg.gradle.jvmargs=-Xmx2g -I /home/tadej/.hermes/cache/scratch/wp16-resume/init.gradle --project-cache-dir /home/tadej/.hermes/cache/scratch/wp16-resume/project-cache --no-build-cache -Pkotlin.incremental=false --rerun-tasks` with absolute scratch build directories and no repository-relative build redirect.

- `./gradlew [flags above] :app:testDebugUnitTest --rerun-tasks`: **PASS**. Parsed `/home/tadej/.hermes/cache/scratch/wp16-resume/build/app/test-results/testDebugUnitTest/*.xml`: **54 suites, 419 tests, 0 failures, 0 errors, 0 skipped**; group package **7 suites, 70 tests**. Log: `/home/tadej/.hermes/cache/scratch/wp16-resume/test-final.log`.
- `./gradlew [flags above] :app:assembleDebug --rerun-tasks`: **PASS**. APK `/home/tadej/.hermes/cache/scratch/wp16-resume/build/app/outputs/apk/debug/app-debug.apk`, 138827966 bytes. Log: `/home/tadej/.hermes/cache/scratch/wp16-resume/assemble-final.log`.
- No dependency or Gradle file changed. No active `TODO()` / disabled mutation guard in `app/src/{main,test}/java/com/relay/app/calls/group` at final scan. `git diff --check` clean. Self-review only; no independent review.

## Acceptance and integration remaining

- **Pure group call layer: PARTIAL** — the port-driven orchestration compiles/tests, but its SFU API is not a concrete Cloudflare proxy binding or native Android peer. The server `sessions-new` contract optionally accepts an offer and the inherited `SfuApi.sessionsNew()` takes none; real negotiation needs a wire/API compatibility pass before use.
- **Authenticated ingress + announcement fan-out: BLOCKED in current contract** — `GroupProtocol.EVENT_KINDS` accepts `post/delete/purge/profile/read`, not a control-only call kind. `SharedGroups.store` persists ordinary posts as chat history, while G5 says call control is not permanent history. Do not sneak a plaintext `TYPE:CALL` into the direct 1:1 signal path or represent a group call as a chat post. A dedicated group control event/fan-out contract belongs in shared core plus app/desktop; not changed under this Android-only scope. The Android snapshot store and coordinator are not yet wired into `IncomingMessageHandler`/`SharedGroups`.
- **Roster-bound create/join/rotate/removal: PARTIAL** — pure create and removal policies tested against fakes; no authenticated Android HTTP/socket port, no real rotation secret redistribution to remaining devices. Creator's fallback `/end` is attempted after removal failure but unconfirmed remote end does not prove remote revocation.
- **GroupCallEngine persistence: PARTIAL** — tested codec/store implementation exists, but no app host instantiates it; real atomicity with incoming group-delivery acknowledgement remains unverified.
- **SFU track/session state and UI in real group chat: NOT DONE** — no real `SfuPeer`, foreground service/Telecom binding, speaking/volume native capture, or banner/screen in `GroupChatScreen`. Existing 1:1 paths unchanged.
- **WP16-AC1: NOT RUN** — five participants including ≥2 real phones and ≥1 desktop, 15 min, leaves/rejoin, mutual audio.
- **WP16-AC2: NOT RUN** — live muted track survival past 60 seconds (unit fake packet counter is not SFU evidence).
- **WP16-AC3: PARTIAL** — unknown roster participant is flagged and not subscribed in unit tests; not displayed on a real phone.
- **WP16-AC4: NOT RUN** — actual SFU egress measurement vs estimate.
- **G5 signed roster: BLOCKED server/shared contract** — the frozen member list is creator-claimed, not signed against the owner's authoritative revision (as documented in `server/cloudflare/PROTOCOL.md` §G5). No Android-only code can attest it. `ALLOW_UNROSTERED_CALLS=0` deployment gate not verified.
- **NOT RUN release gates:** physical Android phones, Android emulator/instrumentation, live Cloudflare room/SFU/TURN, Android↔desktop interoperability, 17-participant load, native microphone/audible audio, background/Telecom/FGS, live stale-banner readback, end-to-end authenticated fan-out, measured provider cleanup and egress, WP17 frame encryption, explicit non-E2EE release acceptance. This build is not a usable group-call APK.
