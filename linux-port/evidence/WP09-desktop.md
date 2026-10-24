# WP09 — desktop file transfer evidence

Branch `feat/linux-port`, base `21ac04e`, uncommitted. Owned paths: `desktop/**` and this file. No commit, push, deploy, version bump, or `.local`/`.private` read by this task. Work resumed from a prior desktop agent killed by a provider rate limit; its code was kept, audited and extended, not rewritten.

## Files
New: `DesktopFiles.kt` (controller), `DesktopFileUi.kt` (bubble, picker, drop), tests `DesktopFilesTest.kt` (29 tests), `DesktopFilesWorkerTest.kt` (1 gated Worker test).
Modified: `DesktopEngine.kt` (sendFile/retryFile, TYPE:FILE receive route, background retry loop), `DesktopMailboxTransport.kt` (`sendWithMedia` override, no redirects, durable exact media body), `DesktopMessageStore.kt` (`file_transfers`, `file_sync_refs`, `queueFileSync`, `refreshFileSyncPublication`, media send-body journal), `SentSync.kt` (per-wire media digests on every envelope/retry, deterministic `ts`), `Main.kt` (attach button, drop target, bubble dispatch, test opt-in flag).

## Requirement audit (status: done = implemented, tested = test exercises it; "mut" = mutation-checked)
| Requirement | Status |
|---|---|
| Persisted bubble + authenticated manifest + original id + per-part state before any network call | done, tested (`bubble manifest original id and flags are durable before upload` asserts DB state inside the upload hook) |
| Parts encrypted once, exact ciphertext reused on retry; no whole-file ByteArray | done, tested (`retry reuses exact encrypted parts`; code has only 64 KiB buffers; peak RSS below) |
| Upload recipients = contact + own pubkey | done, tested; assertion **strengthened this session** from `size==2` to the exact list `[peer, own]`; mut |
| Signed send with media refs via `sendWithMedia`; fail closed | done, tested (`media transport retry replays durable exact body`, `invalid media refs fail permanently without any network`) |
| SENT publication carries same media list on every part/retry; own confirm deferred until accepted | done, tested; the retry test only compared bodies to each other, **strengthened** to assert the `media` array in every body; mut (`originator waits…`, `file sync refs survive…`) |
| Incoming + SENT-copy bodies are file records keyed by ORIGINAL payload id | done, tested (`SENT file commits original id…`, Worker test: late own device record under sentId) |
| Streamed part download to private staging, verified parts persisted, restart redownloads only missing | done, tested at controller level (`interrupted download resumes…`) and at engine level in the Worker test (**added**) |
| Whole-file sha256, size, id verified before save/open | done, tested (`wrong whole plaintext digest`, `local plaintext corruption…`); redundant guard, see mutation notes |
| Confirmation persisted, retried only after verified durable storage | done, tested (`confirmation failure is durable…`, `tampered ciphertext never confirms`) |
| Cancel/delete fencing, no resurrection | done, tested; **added** `cancel during upload…` and `deleted transfer is not resurrected by replayed delivery` |
| Disk quota / disk-full | done, tested (`quota source symlink and group fail closed`, **added** `incoming file over private quota…`, `filesystem failure during upload…` uses an injected FileSystemException, not real ENOSPC) |
| 0700 dirs / 0600 files, symlink refusal, no overwrite, atomic no-replace publish | done, tested (`save collision and symlink ancestors…`, **added** `symlinked persisted part is refused before upload`). Atomicity (hard-link publish) is by construction; not crash-injected |
| UI picker + drag/drop, bubble with name/size/progress/retry/cancel/save-as | done; compiled and screenshot-verified (below); picker/drop not exercised by an automated UI test |
| Executable-like warning before OS open, never auto-open | done; function tested (`executable warnings…`), dialog wiring not UI-tested; nothing in the code opens a file without a click |
| Default-off send flag in controller AND UI, receive independent | done, tested (`default off refuses…`, `default off also fences persisted outgoing retry`, receive-with-send-OFF in the sixty MiB test); UI gating via `attachBlockReason`; **added** helper + test |
| Unsupported TYPE safe fallback | **was missing in testable form**; added `DesktopFiles.displayText` (+ test); previously inline in Main |
| Group files | explicitly disabled: `prepare` rejects `group:*`, receive of a group manifest shows "Unsupported file attachment"; **added** explicit UI/drop message "File sending to groups is disabled" via `attachBlockReason` |

## Scope expansion
`attachBlockReason`/`displayText` helpers in `DesktopFiles` (Main wiring); progress-bar colour fix in `DesktopFileUi` (after the screenshot showed it invisible on the blue outgoing bubble); Worker test extended with restart/resume phase and timing/RSS print. ~~`SentSyncPublisher` now signs the envelope with `entry.createdAt` instead of `clock()` so retries are byte-identical~~
— **reverted, see "Review fixes" below: byte-identical retries were never required and the createdAt signature made any retry after the 24 h server window a permanent 400.**

## TDD record (honest)
- Genuine RED (watched fail before production change): `attach is blocked with an explicit reason…` (NullPointerException from a null-returning stub) and `unsupported message types never render raw wire text` (`expected Unsupported message type but was TYPE:HOLOGRAM|secret`). Log `wp09/red1.log`: 29 tests, 2 failures. A first attempt did not compile (missing symbols); that is not counted as RED. The stubs were then replaced by the real helpers.
- The other 4 new tests (cancel during upload, tombstone, symlinked part, incoming quota) passed on first run because the prior agent's code already implemented them. They are NOT red-green; they rely on the mutation check below.
- Mutation checks, one mutation per Gradle run (`--rerun-tasks :desktop:test --tests *DesktopFilesTest --tests *SentSyncTest`), file restored and diffed after each:

| Mutation | Tests that failed |
|---|---|
| remove `check(sendEnabled)` in `prepare` | `default off refuses before source or network access` (1) |
| `localSource && !sendEnabled` guard in `run` disabled | `default off also fences persisted outgoing retry` (1) |
| upload recipients `[peer]` only | 14 tests (the shared Media fake asserts the exact list) incl. the dedicated ones |
| remove `syncPublished` wait before confirm | `originator waits for durable SENT grant publication before confirming` (1) |
| remove cancel check in `update()` | `cancel during downloaded part…`, `cancel during upload…` (2) |
| remove incoming quota `require` | `incoming file over private quota…`, `quota source symlink and group…` (2) |
| remove no-overwrite `require` in `saveAs` | `save collision and symlink ancestors…` (1) |
| remove id binding in `accept` | `id binding refuses reassociated manifest` (1) |
| drop media list from SENT envelope | **0 failures at first** — test was weak; strengthened, rerun (`mut2.log`): `file sync refs survive rejection retry…` fails (1) |
| remove `verifyPlain` in `saveAs` | **survived (0)**: redundant, `verifyPlain(temp)` after the copy still refuses; defence in depth, not a gap |
| remove `noSymlinks` in `verifyPart` | **survived (0)**: `NOFOLLOW_LINKS` open still refuses; second guard |
| remove tombstone early-return at `run` start | **survived (0)**: the `update()` fence still keeps the state DELETED and nothing downloads; the early return is only cleanup |

## Verification (all `--rerun-tasks`, counts parsed from `build/test-results/test/*.xml`)
Gradle prefix: `JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 ./gradlew --no-daemon --console=plain -Dorg.gradle.java.home=$JAVA_HOME -Dorg.gradle.jvmargs=-Xmx3g`.
- Before this session (inherited tree, `RELAY_FILES_E2E=1 :desktop:test --rerun-tasks`): **86 tests, 0 failures, 0 errors, 3 skipped** (the 3 other Worker E2Es need `RELAY_E2E=1`). 62 pre-existing + 24 file tests.
- Final: `RELAY_E2E=1 RELAY_FILES_E2E=1 … :desktop:test --rerun-tasks -i` → BUILD SUCCESSFUL in 7m20s; **92 tests, 0 failures, 0 errors, 0 skipped** (DesktopFilesTest 29, DesktopFilesWorkerTest 1, SentSyncTest 26, DesktopLinkFlowTest 17, DesktopKeyMaterialTest 6, DesktopMessageStoreTest 6, Engine 2, MailboxTransport 2, and the three RELAY_E2E Worker tests 1 each). The earlier 62 stay green. A preceding run with only `RELAY_FILES_E2E=1` also gave 92 / 0 / 0 / 3 skipped in 63 s. One attempt of the full-flag run was cut off by my own tool timeout and discarded.
- `:desktop:compileKotlin :desktop:createDistributable --rerun-tasks`: BUILD SUCCESSFUL, 0 compile errors. Packaged binary `desktop/build/compose/binaries/main/app/relay/bin/relay`.

### Real local Worker, desktop ↔ JVM phone peer (`DesktopFilesWorkerTest`, wrangler dev --local --protocol https, port 8796)
Admin key not in `ALLOWED_PUBKEYS`; desktop signs with an enrolled device auth key; recipient HPKE key imported through the production contact store; a second own device (`late own device`) is enrolled **after the upload and before SENT** and must fetch the blob through the SENT sync media list.
- 0 bytes, 1 part, both directions, saved SHA-256 `e3b0c442…b855` equal to source: 625 ms round trip.
- 19 bytes, 1 part, `5f9a7536…790f` equal: 432 ms.
- 62,914,560 bytes (60 MiB generated), 3 parts, `acc2deca…9ea6` equal in desktop→phone, phone→desktop and late-own-device saves: 5,613 ms for the whole round (both directions + late device).
- Restart: 28,311,552 bytes (2 parts) from the phone peer; injected IOException on the 2nd part download; state FAILED with parts `[true,false]`; engine stopped, store closed, then a **new store, controller, transport and engine** recreated on the same dirs; exactly **1** download after restart, saved SHA-256 equals source.
- Peak memory: the test JVM (hosts desktop engine, JVM phone peer and late device, default heap) `VmHWM` = **421,328 kB**, `VmRSS` at the end 378,160 kB (/proc/self/status). The Worker process RSS was not sampled. This is a single-JVM upper bound, not a per-component number; the 60 MiB file never exists as a ByteArray in the desktop code.
- Cleanup: no listener on 8796 and no wrangler/workerd process after the run (`ss -ltn`, `ps`).

### Failure matrix (what happens, where tested)
| Failure | Result | Test |
|---|---|---|
| tampered ciphertext part | not confirmed, not published, saveAs refused | `tampered ciphertext never confirms or publishes` |
| wrong whole-file digest in manifest | FAILED, no confirm, no file | `wrong whole plaintext digest…` |
| manifest re-associated to another id | refused at accept | `id binding refuses reassociated manifest` |
| confirm fails | CONFIRM_PENDING durable, retried after restart, no redownload | `confirmation failure is durable…` |
| download interrupted | verified parts kept, only missing redownloaded | controller + Worker restart test |
| send response lost | same encrypted parts, one upload, same signed body | `retry reuses exact…`, `media transport retry replays…` |
| SENT publication rejected | outbox kept with same media list, own confirm withheld | `file sync refs survive…`, `originator waits…` |
| cancel/delete mid-transfer or replay | tombstone, nothing sent/confirmed/resurrected | three cancel tests |
| quota / injected FS error | FAILED, retryable, nothing uploaded | quota tests, `filesystem failure…` |
| symlink source / dir / part / target ancestor | refused | safety tests |
| save collision | existing bytes preserved | `save collision…` |
| >8 media refs | permanent failure, no network | `invalid media refs…`, `sync authenticated media references bounded…` |

## Packaged UI screenshots (real; X11 `:0`, `xwd -id <window> | convert`)
Launched the packaged `relay` against a disposable copy of the fixture data produced by the Worker test (synthetic identity; no user data), `--mailbox-url https://127.0.0.1:1` (never reaches a server). Window 900×763. Both PNGs read back with the vision tool:
- `~/.hermes/cache/scratch/wp09/ui-default-off.png` (sha256 `ab3ed749…dfc0`): sidebar "Relay / Linked to 41d59aaa…87adf3 / This computer: WP09 synthetic desktop / Conversations: f48279dd…c27d68 Direct message"; header "Connecting…". Three file bubbles visible: outgoing blue `source-0.bin` "0 bytes · 1/1 parts · COMPLETE", incoming dark `from-phone.bin` "0 bytes · 1/1 parts · COMPLETE" with a progress bar, outgoing `source-19.bin` "19 bytes · 1/1 parts · COMPLETE", each with Retry / Cancel / Save as… / Delete; a fourth bubble is cut off at the scroll edge. "Attach file" is **greyed** (send default OFF). In this first shot the progress bar was invisible on the blue outgoing bubbles — fixed.
- `~/.hermes/cache/scratch/wp09/ui-send-enabled.png` (sha256 `50788616…660b`) launched with `--enable-file-send-for-tests`: same bubbles, progress bars now visible on the blue bubbles, "Attach file" rendered in the active link colour (**enabled**). Text readable, no clipping apart from the scrolled-off fourth bubble.
- App closed with SIGTERM; no `MainKt`/`relay` process remains (`ps`).

## NOT RUN / limits
- No phone, no physical hardware, no real network or live Cloudflare deployment; the "phone" is a JVM codec peer. The late own device is a JVM `DesktopFiles`/`SentSyncApplier` on the same local Worker.
- Native file chooser and drag/drop were not exercised (no UI automation); only the code path and a static screenshot. Opening a file through the OS, the executable-warning dialog and Save-as button flow were not clicked.
- No Argon2id/link-flow changes were run beyond the existing desktop suite; no Android/instrumented tests (phone evidence is `WP09-phone.md`, not reproduced here).
- Real ENOSPC and crash-between-fsync-and-link atomicity not injected (only an injected FileSystemException).
- Peak RSS is of the Gradle test JVM only; no 100 MiB run; Worker CPU/RSS not sampled.
- The WP05 SENT Worker E2E (part of the 92) writes its persist dir under the repo's ignored `.local/`; I did not read it.
- Group file send/receive deliberately not implemented. No independent code review happened; WP09 desktop stays below `for_review` on the board (board not touched by this task).
- No missing core/server API seam was found.


## Review fixes (2026-10-24, parent)

An independent read-only review returned NOT PASSED with two reproduced blockers; the parent (not a fix agent —
both were lost to provider rate limits) fixed them test-first and mutation-checked each fix.

| Finding | Fix | Test / mutation |
|---|---|---|
| B1: file SENT entries were exempt from the 24 h age drop and their expiry was signed from `createdAt`, so an old copy was a permanent 400 that `break`-ed the whole lane forever | expiry is computed at post time for every entry; age applies to every entry; a permanently rejected file copy gets one retry, then is dropped and the lane continues | `a rejected file copy does not block the text queued behind it` — fails with the old `break` (`outbox` size 1) |
| B1b: a dropped copy was silently marked `syncPublished` (no outbox row ⇒ "published"), which would let the sender confirm before grants exist | new `DesktopMessageStore.dropFileSync(wire)` removes the reference row when a copy is dropped | same test — fails when `dropFileSync` is a no-op |
| B2: `cancel(delete=true)` only wrote a tombstone; the worker skips COMPLETE/CANCELLED/DELETED so the decrypted file and every ciphertext part stayed on disk and in the quota | `cancel` deletes the transfer folder after releasing the fence; new `sweep()` at startup removes orphan/tombstoned folders and stale `.stage-*` files | `deleting a complete file removes plaintext and ciphertext from disk`, `cancel keeps the record but still reclaims its bytes`, `startup sweep removes orphans and stale staging but keeps live records` — all three fail when the delete is removed |

`queueFileSync` gained an injectable `now` so the age path is testable without waiting. The existing
"exact signed request bytes" test was amended to compare everything except `expires` and to assert `expires`
is in the future — the byte-identity it used to pin was the bug's premise.

**Open follow-ups (not fixed):** heavy work (`prepare`, `saveAs`, `verifySaved`) runs inside `synchronized(fence)`,
so Cancel/Delete from the UI thread can block behind a large encryption; FAILED/SEND_DISABLED records are retried
every 15 s forever with no backoff cap; a corrupt local `complete` file is never re-assembled from the verified
parts; `.stage-*`/`.relay-save-*` residue is only swept at startup; `Files.createLink` publish needs a
`Files.move` fallback for FAT/exFAT/FUSE; `desktopFileDialog` can NPE on a null directory; the executable-warning
list lacks html/svg/lnk/vbs/reg/deb/rpm/jnlp/pl/rb; `sendText` now prefers the stored contact key over
`--peer-key`.
