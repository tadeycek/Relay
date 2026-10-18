# Handoff to Codex — Relay delivery server (2026-10-18, evening)

> Latest status: Firebase/v7 source is pushed to PR #44, still unmerged. The original signing
> key is recovered, a versionCode-13 signed update APK is built, 19 selected device tests pass and an offline
> 1.11 → 1.12 emulator update preserves the checked fixtures. Read the final "Signed update candidate" section;
> earlier entries are historical. Publication of the signing/version/test follow-ups is now explicitly approved;
> confirm the published revision in Git and PR #44. Tadej merges himself.

Written by Claude because Tadej is low on Claude usage. Read this first, then
`rebuild/MAILBOX-PILOT-2026-10-18.md` (full results) and `server/cloudflare/PROTOCOL.md` (wire contract).
Tadej's AGENTS.md rule still applies: get his go-ahead before changing code.

## Where things are

- **Worktree:** `/home/tadej/Documents/GIT/Relay-cloudflare-mailbox`
- **Branch:** `feat/cloudflare-mailbox`
- **PR:** #44, open with 5 commits. **Do not merge.** Tadej merges once everything runs end to end.
- **Issues:** the PR closes #42 (C0), #45 (C2) and #46 (no relays). #43 (C1 pilot) stays open.
- **Main checkout:** `/home/tadej/Documents/GIT/Relay` is on `perf/messaging-baseline` with Codex's
  **uncommitted** Phase 0 perf work. It was not touched. It overlaps files changed in the PR:
  IncomingMessageHandler, MessagingRuntime, NostrTransport, RelayDbHelper.

## What Tadej decided (all 2026-10-18)

1. **No public Nostr relays anywhere.** Builds with a server URL use only `MailboxTransport`; NostrTransport is
   constructed only by dev builds without a URL. The NIP-17 gift-wrap format and Nostr keys stay as the
   crypto and identity format.
2. **Tor mode is off.** It's direct; the code is kept as `routeWithTor()` and the settings are hidden.
3. **The server sees the sender** (authenticated writes).
4. **History lives only on the phones.** The server deletes a message on the recipient's receipt, and drops
   anything uncollected after 8 days.
5. **Photos and videos go to Relay's own server** (a Durable Object per file), not Blossom, MongoDB or R2.
   R2 needs a card on file, which breaks the zero-spend rule.
6. **Owner recovery and the Control Panel (`owner/`) are removed.** 10 wrong calculator codes in a row wipe
   the app (clear app data).
7. **New people join by in-person QR pairing** with a member. The deploy-time `ALLOWED_PUBKEYS` are only
   the "founders".
8. **Firebase push** for a closed app: content-free "wake" messages.
9. **A new Cloudflare account** (created 2026-10-18) is in use. Wrangler is logged into it. The old account
   is untouched.

## Done and committed (pushed to PR #44)

- **Server** (`server/cloudflare`, Worker + SQLite Durable Objects, schema **v5** in the committed code):
  - mailbox with NIP-98 auth, idempotent stores, paged catch-up, delete-on-receipt;
  - hibernating sockets, and live typing/presence signals that are never stored;
  - `MediaBlob` file store;
  - membership by pairing: `/v1/invite`, `/v1/pair`;
  - 8-day expiry.
  - 96 tests in the committed state.
- **App:**
  - `transport/mailbox/`: transport, receipt state machine `MailboxSession`, raw inbox (DB v22), media,
    membership;
  - wipe after 10 wrong codes; owner recovery removed; QR scanner crash fixed;
  - foreground wake-up of a backed-off socket; refused signals no longer drop the socket.
  - 317 unit tests.
- **Live proof** (`rebuild/dev/test-mailbox-emulators.py`, two emulators through the deployed pilot):
  - pairing; texts both ways; photo; offline catch-up;
  - a group with posts both ways and a group photo; typing, direct and in the group;
  - closed-app delivery via the periodic PollWorker;
  - both ways of joining: vouch, and invite;
  - the wipe, on the real calculator UI.

## Uncommitted in the worktree: Firebase push (built and unit-tested, NOT live-tested, NOT deployed)

- **Server:**
  - `src/push.ts`: `PushSender` signs a service-account JWT (RS256), then calls the FCM HTTP v1 send with
    data-only `{type:"wake"}`.
  - `src/mailbox.ts`: schema **v6** `push_tokens`; `POST /v1/push-token`; on a store for a recipient with no
    live socket, `ctx.waitUntil(wake())`, throttled to 20 s per recipient. Dead tokens are deleted.
    `pushFetch` can be swapped in tests.
  - `src/index.ts` route; `test/push.test.ts` (7 tests); `vitest.config.ts` (throwaway RSA key);
    `test/storage.test.ts` (expects v6).
  - **103 server tests pass, and typecheck passes.**
- **App:**
  - root `build.gradle` (google-services plugin 4.4.2, `apply false`);
  - `app/build.gradle` (plugin applied only if `app/google-services.json` exists; firebase-bom 33.7.0 +
    firebase-messaging);
  - `transport/mailbox/MailboxPush.kt` (registers the token);
  - `messaging/RelayMessagingService.kt` (on wake → `PollWorker.wakeNow`);
  - `PollWorker.wakeNow` (one-time work, expedited only on API 31+);
  - `MessagingRuntime` calls `MailboxPush.refresh()` after start; manifest service entry.
  - **App builds online and 317 unit tests pass.**
- **Not yet done:**
  - commit these files;
  - deploy server v6 (`wrangler deploy` needs the allowlist var; see below);
  - a live emulator run to confirm the app is unaffected without Firebase files;
  - the real push test once Tadej provides the Firebase files.

### Waiting on Tadej: Firebase files

He is creating a Firebase project (free Spark plan, any Google account). He'll put two files in the
worktree's `.local/` (gitignored):

- `google-services.json`: copy it to `app/google-services.json`, which is gitignored by `.gitignore` line 42.
- The service-account key JSON: store it as a Worker secret with
  `cd server/cloudflare && npx wrangler secret put FCM_SERVICE_ACCOUNT < ../../.local/<key>.json`.
  That uploads it to Cloudflare, so ask first. Never commit it or paste it into notes.

Then: build, install, check that the token registers (`MailboxPush` logs "push token registration: HTTP 200"),
stop Bob's app, send from Alice, and confirm Bob is woken via FCM (notification) without forcing the
JobScheduler job.

## Live infrastructure (keep hostnames and account IDs out of notes and the repo)

- **Worker** `relay-mailbox-test` on the new account; its URL is in `~/.config/relay-mailbox/pilot-url` (mode 600).
- **Synthetic identities** in `~/.config/relay-mailbox/pilot-identities.json` (mode 600).
- **Allowlist / founders** = synthetic users + Alice's emulator key (last deployed with `--founders alice`).
  Bob's current key joined by pairing and is in the `members` table, which survives deploys.
- **Deployed server code** = commit 3c1db15 (schema v5). A plain `npx wrangler deploy` needs
  `--var "ALLOWED_PUBKEYS:$(node tools/pilot.ts allowlist),<alice key>" --var EXPOSE_METRICS:1`.
  The emulator keys are in `.local/mailbox-emulator-pubkeys.txt`. Or use the harness with
  `--deploy-allowlist --founders alice`.
- **Uptime probe:** a systemd **user** timer `relay-mailbox-probe.timer` runs every 10 min from a copy in
  `~/.local/share/relay-mailbox/probe`. The log is `~/.local/state/relay-mailbox/probe.jsonl`; summarise it
  with `node tools/pilot.ts uptime --days 7` from that folder. Linger is off, so it only runs while Tadej is
  logged in. After changing `server/cloudflare/tools/pilot.ts` or `client/mailbox-client.ts`, copy them
  there.

## How to build and test

```sh
cd server/cloudflare && npx vitest run && npm run typecheck          # server
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk                        # app (JDK 17; system java is 27)
./gradlew -q :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -Dorg.gradle.java.home=$JAVA_HOME
```

**Emulators:** AVDs `RelayPerfAliceApi36` and `RelayPerfBobApi36` in `~/.config/.android/avd`. Neither has a
camera. **Memory is tight (23 GB machine; there was one OOM incident).** Always:

1. build first;
2. `./gradlew --stop` and `pkill -f KotlinCompileDaemon`;
3. start headless with
   `-no-window -no-audio -gpu swiftshader_indirect -memory 1024 -cores 2 -no-snapshot-save`
   on ports 5554 and 5556;
4. install the app + androidTest APKs;
5. run `python3 rebuild/dev/test-mailbox-emulators.py [--founders alice] [--deploy-allowlist] [--repair] [--shower Alice|Bob]`;
6. `adb -s emulator-55xx emu kill` afterwards.

## Remaining work (Tadej's list)

1. **Firebase push**: commit, deploy and live-test (above).
2. **Real phones**: needs Tadej.
3. **Production deployment**: a separate Worker name (not `-test`), Tadej's and the pilot users' founder keys,
   `EXPOSE_METRICS=0`, and keep the probe pointed at it. Needs Tadej's list of people (or just his own key;
   others join by pairing).
4. Optionally: a server-side way to remove a member (none exists yet), and the stale-key cleanup after a wipe.

## Gotchas

- **CRLF files:** `RelayApplication.kt`, `RelayDbHelper.kt`, `DatabaseContract.kt`, `SettingsViewModel.kt`,
  `RelayPreferences.kt` and others use CRLF. Preserve the endings (check `git diff --stat` for whole-file
  diffs).
- **zsh doesn't word-split `$VAR`:** use arrays or Python for adb commands.
- **Harness:** chats are opened through People → contact, because Messages rows can match group previews.
  After a wipe, the peer's stale contact must be forgotten (`forget_stale`).
- **Non-member contacts:** the emulators have old "Perf …" contacts that aren't on the server. Messages to
  them fail (expected), and presence to them is paused after one refusal.
- **Vault:** `~/Documents/SecondBrain`. Dev logs from this session are named `2026-10-18 — Relay …`
  (Cloudflare Mailbox C0 C1, Android on Mailbox C2, No Public Relays and Owner Removal).

## Codex follow-up — 2026-10-18, after Tadej's go-ahead

Local review, fixes and verification are complete. The Firebase work is still **uncommitted and undeployed**;
PR #44 remains unmerged. Neither Firebase JSON file is in `.local/`, and `app/google-services.json` is absent.
The separate `Relay` performance checkout was not modified. No secret was uploaded and no live server was
redeployed: the existing pilot still runs the committed v5 code.

Additional fixes made during review:

- Android token registration uses a network-constrained `MailboxPushWorker` with exponential retry.
  `MessagingRuntime` refreshes registration on ONLINE, including after QR admission, instead of just once
  before admission. Firebase token rotation schedules the same registration work.
- The unpublished v6 `push_tokens` table now includes `last_wake_at`, so the 20-second wake-up throttle
  survives eviction/hibernation. Refreshing an unchanged token preserves the cooldown.
- Failed socket sends no longer suppress the Firebase fallback.
- Only the typed FCM `UNREGISTERED` response removes a token; a generic 404 preserves it.
- `/v1/push-token` validates the protocol version. README and PROTOCOL now document optional Firebase setup,
  the token route, wake-ups and live-test prerequisites.

Verification performed by Codex:

- Baseline: 103 server tests and typecheck passed. Regression suite before fixes: 5 failed / 7 passed.
- After fixes: **108 server tests, 317 Android unit tests (48 suites)**, server typecheck and bundle dry run,
  debug APK and instrumentation APK builds all pass.
- Live no-Firebase check against the existing pilot:
  `ANDROID_HOME=/home/tadej/Android/Sdk python3 rebuild/dev/test-mailbox-emulators.py --founders alice`.
  Existing verified pairing was reused; no wipe, forced re-pair or allowlist deployment was requested.
  Both text directions (2.7 / 3.2 seconds including UI automation), encrypted photo, offline catch-up
  (4.5 seconds after network restoration), group creation/posts/photo, typing and closed-app periodic
  polling/notification all passed. **This is not proof of FCM delivery.**
- Built first with JDK 17, `--no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`;
  stopped Gradle, then ran the headless emulators with 1024 MiB requested RAM, two cores, and snapshot
  loading/saving disabled. Available memory stayed above 6 GiB at observed checkpoints; no OOM occurred.
  Both emulators were shut down after the successful scenario.
- Earlier emulator artifacts were copied to `.local/push-smoke-backup-20261018/`. Current logs are
  `.local/push-server-tests.log`, `.local/push-typecheck-final.log`, `.local/push-server-build.log`,
  `.local/push-android-build.log`, `.local/push-emulator-smoke.log` and `.local/push-runtime-{alice,bob}.log`.

Next gate: Tadej supplies the two Firebase JSON files. Obtain approval before uploading the service-account
secret; preserve founders when deploying. For the actual FCM test, end the recipient process without using
Android Force stop, then verify delivery without forcing JobScheduler. Force-stopped apps cannot receive FCM
until reopened. Real phones, production deployment and merge still need their separate acceptance steps.

### Android Firebase config received — 2026-10-18

Tadej supplied `/home/tadej/Downloads/google-services.json`. It matches the intended Firebase project and
Android package `com.relay.app`; copied without removing the original to `.local/google-services.json` and
`app/google-services.json`. Both destinations are gitignored and mode 600. The Firebase-enabled build passes
`:app:processDebugGoogleServices :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`
with the same guarded JDK 17 settings above; 317 unit tests pass and all required Firebase Android resources
are generated. Log: `.local/push-firebase-config-build.log`. No emulator was started for this step.

The **service-account key JSON is still missing**. Tadej is being guided to Project settings → Service accounts
→ Generate New Private Key → Generate Key. Await the downloaded file, validate matching project without
printing its contents, then obtain the required approval before uploading the Worker secret and redeploying.
No credential was uploaded, no live server changed, and code remains uncommitted with PR #44 unmerged.

## Firebase live acceptance completed — 2026-10-18

Tadej supplied and explicitly approved uploading the service-account key, deploying the test server and
running real FCM acceptance. The key is stored at `.local/fcm-service-account.json` (gitignored, mode 600)
and uploaded as the Worker's `FCM_SERVICE_ACCOUNT` secret. Never print, commit or put its contents in notes.
The Downloads originals were preserved. Both Android configuration copies remain gitignored with mode 600.

Before rollout, the live pilot account and settings were verified and privately saved in
`.local/push-deployment-before.json`. All 11 existing founder keys and all other plaintext variables were
preserved; QR-admitted membership was not reset. Production was not deployed.

### Schema drift found and repaired

The initial deployment exposed a real old-v6 layout: `push_tokens` had `pubkey`, `token`, `updated_at`, but
no `last_wake_at`, while metadata already said schema 6. Token registration returned 503 even though ordinary
live smoke remained 12/12. The earlier handoff's v5 deployment claim and assumption that v6 was unpublished
were stale. Fresh-database tests missed this because the column had been added by editing v6 in place.

The final code restores the original v6 definition and adds **v7**, conditionally adding `last_wake_at`
without recreating the table or losing existing tokens/messages/members. Two regressions cover original v6
with retained data and v6 that already has the column. The first reproduces the 503 before the fix. The
temporary metadata-only diagnostics used to inspect the live column names were removed afterwards.

### Final evidence

- **110 server tests**, typecheck, **317 Android unit tests**, Firebase-enabled debug and instrumentation
  APK builds pass. Post-v7 live server smoke: **12/12**. Live token registration returns HTTP 200 with
  push enabled; both emulator tokens registered successfully.
- The v7 live conversation run passed text both ways, encrypted direct/group photos, group posts, typing,
  database checks and offline catch-up (4.4 seconds), but its final Alice chat-navigation step hit an existing
  UI timing race. Log: `.local/push-v7-emulator-smoke.log`; this combined invocation did not exit successfully.
- The focused rerun succeeded:
  `ANDROID_HOME=/home/tadej/Android/Sdk python3 rebuild/dev/test-mailbox-emulators.py --founders alice --push-only`.
  **Bob's app process was absent, periodic polling was paused, a new real FCM callback arrived, and a new
  notification appeared in 2.8 seconds including send automation. No JobScheduler job was forced.**
  Log: `.local/push-v7-push-only.log`, with runtime evidence in `.local/push-v7-runtime-{alice,bob}.log`.
- The harness now supports `--push` for the full FCM scenario and `--push-only` for the focused check;
  without these flags it still tests the periodic polling fallback. It opens the sender's chat before
  stopping the recipient, and restarts the recipient in cleanup to restore polling. The instrumentation's
  `pause_poll` action affects only the synthetic recipient's periodic job. `RelayMessagingService` logs a
  content-free callback marker, never message data or tokens.
- Both memory-limited emulators and the temporary Worker log-tail process were stopped. No OOM; more than
  6 GiB was available at observed checkpoints, and about 13 GiB after shutdown. The existing 10-minute
  uptime timer remains active. Large artifacts were not placed in `/tmp`.

Private deployment logs: `.local/push-live-secret-upload.log`, `.local/push-v7-deploy.log`; test evidence:
`.local/push-v7-server-tests.log`, `.local/push-v7-typecheck.log`, `.local/push-live-android-build.log`,
`.local/push-v7-server-smoke.log`. Before/failed-run artifacts were preserved in private `.local/` folders.

**Still pending:** source review and explicitly authorized commit/push; real-phone testing (needs Tadej);
then the separate production Worker and pilot founders. **Do not merge PR #44.** The separate performance
checkout remains untouched, with overlapping uncommitted work. Actual FCM timing on real phones, including
Doze/OEM behavior, has not been established by emulator acceptance.

### Publication approved — 2026-10-18

Tadej explicitly requested: "Commit and push and open pr, which i will merge myself". Publish the Firebase
implementation, v7 migration, regression tests, emulator harness and documentation on the existing
`feat/cloudflare-mailbox` branch and update its already-open PR #44 rather than creating a duplicate.
The earlier pending commit/push approval entries are historical. Merge remains exclusively Tadej's action;
real-phone and production acceptance are still pending. Commit and remote publication are recorded by Git
and the PR, avoiding a self-referential commit hash in this document.

## Signed update candidate — 2026-10-18

Tadej approved investigating the remaining video-expiry assertion, configuring Relay-only original-key
release signing, increasing versionCode and building an update APK. The existing global debug key and
the main performance checkout remain unchanged. No production deployment or merge occurred.

- Original private-key entry is under ignored `.local/original-signing-20261018-sdwh0rw9/`; local
  `.local/signing.properties` is mode 600 and ignored. Never commit or share signing inputs/passwords.
  Release signing does not alter debug signing. Firebase Android config and an HTTPS mailbox URL are
  also required for `assembleRelease`/`bundleRelease`; the missing-signing guard was tested.
- Candidate: `/home/tadej/Downloads/Relay-1.12-preview.apk`, `com.relay.app`, versionCode **13**,
  versionName **1.12-preview**, non-debuggable, original certificate SHA-256
  `06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9`.
  APK SHA-256: `33b981152163ffb391bf86109eedb24bb0e9a77b225ee10d31af0f9b73e4ff4d`.
  This uses the **test pilot**, not a new production server. Install as an update; never uninstall or
  clear storage to bypass a mismatch. See `rebuild/DISTRIBUTION-1.12-preview.md`.
- Current builds/lint and all **317 Android unit tests** pass. Previous server tests remain 110 plus
  typecheck and 12/12 live checks; server code/deployment did not change in this step.
- Latest selected device suite: **19/19**. Legacy UiSelector queries depended on a sometimes-null active
  root; the video-expiry test now uses all-window `By` queries and retains gate, closed media, stopped
  playback and cleared plaintext-cache assertions. Focused video repeats pass 3/3 per device after an
  unrelated System UI startup ANR on Bob blocked its first attempt. No app/session behavior changed.
- Latest controlled FCM proof: **3.3 seconds**, recipient process absent, periodic polling paused,
  fresh callback and exact new message in the active notification, no forced periodic job. Normal-mode
  forced-job testing with Firebase enabled is **not isolated proof of polling fallback**.
- Actual published 1.11 (versionCode 12) was installed on a new, offline `RelayUpgradeApi36` AVD and
  updated using the signed release with `adb install -r`. UID and six fixture-file hashes match before
  first launch; original PIN enforcement, profile name, encrypted identity, database secret and a
  synthetic private file persist after launch. No existing message-history fixture or real phone was
  tested in this upgrade check. This dedicated AVD is retained for reuse, not deleted.
- Private evidence: `.local/signed-update-20261018-hSGL7b/RESULTS.md` and sibling logs/scripts.
  Build before starting emulators and stop Gradle first. API-36 clamps requested 1024 MB to 2560 MB
  per VM; headless/two cores/SwiftShader plus a <2 GiB host-memory stop guard were used. Run the
  dedicated update AVD only after stopping Alice and Bob. All three VMs are stopped; the ordinary
  10-minute probe timer remains active. No OOM observed.

`release/nostr-version` is already pushed at pre-Cloudflare `fab14d0e6aecbded01e91027a13fd30c6a63e251`.
The candidate was built from base `dc10526` plus the signing/version, four instrumentation/harness fixes
and distribution/handoff documentation in this follow-up. Tadej subsequently explicitly requested:
"commit and push this latest changes". Publish these follow-ups to the existing PR #44, not a duplicate;
the final published revision is recorded by Git and the PR. Keep auto-merge disabled and leave the merge
to Tadej. No APK upload, production deployment or additional real-phone acceptance is implied by publishing.
Next: original-key update on real phones, QR admission through an admitted member,
peer/push/Doze/mobile-network acceptance, then separately approved production.
