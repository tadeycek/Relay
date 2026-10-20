# Relay handoff for Claude — 2026-10-20

> **Update, later on 2026-10-20:** this batch plus follow-up fixes (outbox lanes, retry reset, chat scroll, create-group progress) was committed locally on `fix/messaging-regressions`, compiled but not tested. The APK described below predates those follow-ups. See the top of `FIXES-2026-10-19.md`.

## Start here: current truth and authorization

Relay's remaining loading/messaging fixes are written locally and an original-key-signed **1.13-preview / versionCode 14** APK exists. Debug/release JVM suites, server checks and release lint passed. **The new APK has NOT passed emulator or real-phone runtime acceptance; do not promise that the reported delays are fixed.**

Tadej explicitly said **“stop”** during the subsequent emulator attempt. Testing remains stopped. His latest request authorized creating this handoff only—not restarting emulators, installing anything, editing application behavior, committing/pushing, releasing or deploying. Present a brief plan and get approval before further changes; never delete/reset/uninstall/clear storage without explicit approval.

This handoff supersedes outdated status paragraphs in older handoffs. No app source, tests or server state were changed while preparing it. Git/PR/issue refs, APK hash, process state and monitor status were rechecked on 2026-10-20.

## 1. Repositories: do not mix the two dirty checkouts

| Location | Current state |
| --- | --- |
| **Work on the fixes here:** `/home/tadej/Documents/GIT/Relay-cloudflare-mailbox` | Branch `feat/cloudflare-mailbox`, HEAD `4f97ba0aab14c9fe8d734b65ac970adbb252436c`; extensive tracked modifications and new untracked production/test/documentation files. The current fixes are **not committed or pushed**. |
| **Preserve this independent work:** `/home/tadej/Documents/GIT/Relay` | Branch `perf/messaging-baseline`; unrelated uncommitted performance instrumentation, including untracked benchmark/trace files. It overlaps many app files changed in the mailbox worktree. Do not copy over, reset, clean or switch this checkout. |
| Remote | `https://github.com/tadeycek/Relay.git` |
| Remote `main`, verified October 20 | `1f72d8238a09cbcf9438c1796def16e0b44aff91` |
| [PR #44](https://github.com/tadeycek/Relay/pull/44) | **MERGED** on October 18; old handoff statements saying it is open/unmerged are obsolete. The current dirty patch is not a new published PR. |
| User-requested rollback branch | `release/nostr-version` exists remotely at `fab14d0e6aecbded01e91027a13fd30c6a63e251`. Preserve the exact name. This preserves old code, not compatibility with a migrated v23 database. |

Primary checkout's **tracked-only** binary diff SHA-256 remains `924a8229841325bd007d884ae7bd91829283aa7382a9050f75bc3f48e0fc91d4`. This does not inventory its untracked files: preserve those too. Start any resumed work with `git status --short` in both checkouts and read applicable `AGENTS.md` instructions. No nested `AGENTS.md` was found in the app/server/rebuild trees during this handoff.

## 2. What Tadej reported

- First opening the app causes app-wide 10–20-second delays: People/contacts, chat lists, opening DMs and groups. A black screen may be followed by “Say hello” despite existing messages. This is **cold-process/loading** behavior, not merely a warm chat-open benchmark.
- Create group takes about five seconds to leave the screen; opening a group reportedly about twenty seconds; DM history about ten seconds.
- Sending reportedly takes about three seconds to show the sender's own bubble, then ten–twenty seconds to recipients; older messages could arrive many minutes late. Both real phones eventually showed Connected.
- Activity, typing and seen indicators are slow/unreliable.
- A red-send report was withdrawn: those peers had not updated. Do not revive it as a new confirmed regression.

All these tracking issues remain OPEN as verified October 20:

| Issue | Scope |
| --- | --- |
| [#39](https://github.com/tadeycek/Relay/issues/39) | Measurements/source findings/validation tracking |
| [#47](https://github.com/tadeycek/Relay/issues/47) | Slow group creation |
| [#48](https://github.com/tadeycek/Relay/issues/48) | Group history loading |
| [#49](https://github.com/tadeycek/Relay/issues/49) | DM history loading |
| [#50](https://github.com/tadeycek/Relay/issues/50) | Local bubble and recipient-send delays, including media observations |
| [#51](https://github.com/tadeycek/Relay/issues/51) | Activity/last-active |
| [#52](https://github.com/tadeycek/Relay/issues/52) | Typing |
| [#53](https://github.com/tadeycek/Relay/issues/53) | DM receipts and previously missing group seen path |

Implementation status was posted to each regression. Latest host-validation evidence is in [this #39 comment](https://github.com/tadeycek/Relay/issues/39#issuecomment-6271802167). Do not close issues based only on code or host tests. [#43](https://github.com/tadeycek/Relay/issues/43), the hosted pilot, is also still open.

## 3. Local implementation map — written, not device-accepted

Production paths below are relative to the mailbox worktree's `app/src/main/java/com/relay/app/`.

| Area | Changes and important files |
| --- | --- |
| Secure DB setup | `data/db/RelayDbPassphrase.kt` lazily reuses an encrypted-preference/keyset instance with synchronized publication; no additional plaintext passphrase cache. `ContactsViewModel` uses the shared `MessagingDb` helper. These were the original two-file batch. |
| Loading/refresh | New `messaging/RefreshQueue.kt` coalesces follow-up invalidations without cancelling unfinished initial reads. People/list/DM/group VMs distinguish initial loading/error/verified-empty; new `ui/components/InitialLoadState.kt`. Identity reads move off composition's UI thread. New local text rows survive stale in-flight refreshes. |
| Retention/read path | `HistoryWindow`, message/group/conversation repositories apply the existing seven-day cutoff in SQL before pagination/counting. Screen reads no longer await a global retention sweep. Periodic cleanup remains, plus expiry indexes. Group profile updates avoid unchanged broadcasts. |
| Group acceptance/fanout | New `groups/GroupDispatch.kt` stores durable encrypted fanout jobs, then seals/enqueues individual recipients in the background. `SharedGroups` stages intent instead of doing all per-recipient crypto before returning. Create returns before optional profile sync/list refresh; text VMs publish the durable accepted row before history rereads. Outbox scheduling/reset counts include staged jobs; dispatch/deleted-source/retention checks must be validated. |
| Presence/typing | Reconnect heartbeat, bounded peer-signal concurrency, saved/live observation merge and fresh lease labels. Typing lookups/transmission run off UI, stale/superseded sends are dropped, received typing keeps only its remaining TTL. Mailbox ephemeral signals have a separate bounded receive path, not the durable-message backlog. Relevant files: `PresenceSignals`, `TypingSignals`, `ContactActivity`, `Transport`, `MailboxTransport`, runtime/handler. |
| DM seen | New `messaging/ReadReceiptBody.kt`: bounded canonical exact-ID batches, strict malformed-ID handling, legacy timestamp compatibility. Removed the cooldown that discarded newer watermarks and the broad future timestamp tolerance. Receipt work is coalesced/off-main and gated by the matching foreground chat plus preference. |
| Group seen | New `groups/GroupReadReceipts.kt`, authenticated `read` group controls, durable sent markers, acknowledgements tied to original recipient snapshots, model/repository/UI read counts. “Sent” still means server acceptance, not read. Old peers do not implement group receipts; legacy unknown-target messages must not falsely become all-seen. |

DB version increases **22 → 23**. The new migration adds `group_dispatch`, `group_reads`, `group_read_sent`, recipient/source fields in `group_delivery`, and timestamp indexes. It does not rebuild/drop existing history tables. SQLCipher encryption, seven-day local retention and emergency wipe semantics are intended to stay unchanged, but actual Android upgrade/privacy/deletion/wipe checks are still open. **Do not install an older APK over a v23 database as a rollback strategy.**

New JVM tests: `ReadReceiptBodyTest`, `RefreshQueueTest`, `RetainedHistoryQueryTest`, `GroupReadProtocolTest`, plus delayed typing expiry in `ConversationExtrasTest`.

After the host-tested APK was built, `app/src/androidTest/java/com/relay/app/calculator/PeerConversationTest.kt` gained **test-only** timing/receipt/activity/schema probes and commands for history, bursts and typing. These compile in the instrumentation APK, but the updated helper was never installed/run on the emulator pair. Consequently the recorded host-build whole-`app/` source fingerprint predates these instrumentation edits; the delivered release APK itself remains unchanged.

## 4. APK and checks that actually passed

**Candidate:** `/home/tadej/Downloads/Relay-1.13-preview.apk`

- `com.relay.app`, `1.13-preview`, versionCode **14**, non-debuggable; 76,371,029 bytes.
- APK SHA-256: `4a4ed42a8529a9763e140a2aee37f92e033026852f9502a4907196b27ff277fa` (rechecked October 20).
- Original signer certificate SHA-256: `06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9`. Signature verifies and matches the preserved previous release APK. Friend's original keystore was recovered; certificate duplication alone is not signing capability.
- Version bump was explicitly approved by Tadej. Global/default debug signing was not replaced.
- Debug JVM: **328 tests / 52 suites**, zero failures/errors/skips.
- Release JVM: **the same 328 tests / 52 suites**, zero failures/errors/skips; not 656 distinct tests.
- Server typecheck and **110 tests / 9 files** pass using an isolated local Worker runtime.
- Release lint passes: **0 errors/fatals, 20 warnings, 13 informational findings**. Existing AGP/compileSdk and deprecated-API warnings were not repaired.
- Host SQLite smoke checks extracted relevant source DDL with synthetic data: fresh tables, v22→v23 fixture upgrade, preserved DM/group rows, legacy defaults, new-table cascades and foreign-key consistency. **This is not Android SQLCipher/Keystore upgrade acceptance.**
- Firebase Android package and generated release mailbox URL/version/non-debuggable configuration were checked.

No 1.13 install on a real phone or emulator, public release/upload, deployment, commit or push happened in this work. Install only as an update after approval; never uninstall/clear real phone data to bypass a signing mismatch. The original-key signed APK uses the configured existing pilot, not a newly provisioned production server for ten people.

Detailed record: `rebuild/VERIFICATION-1.13-preview.md`. Private host logs, prior APK/report backups and hashes: `.local/full-validation-20261019-NCMuDp/`.

## 5. Emulator attempt: incomplete and explicitly stopped

Private evidence: `.local/emulator-113-20261019-ODRY32/RESULTS.md`, snapshots, `memory.jsonl`, emulator/controller logs, observations and private `probe.py`/`cold.py` drivers.

What occurred:

1. Debug and test APKs built successfully in 22 seconds, before launching VMs.
2. Existing `RelayPerfAliceApi36` / `RelayPerfBobApi36` booted at `emulator-5554` / `emulator-5556` with their **old 1.12-preview/code13 debug installs**. Both were ONLINE and existing verified reciprocal pairing matched.
3. Private snapshots captured 1,073 DM rows on Alice and 33 on Bob, plus groups; existing contacts/history were not reset. One synthetic `QA113-GROUP-ANCHOR` was added to existing `ProbeOct19A`.
4. The supervisor's host-available-memory guard triggered below 2,800 MiB. Minimum sampled available RAM was **1,220 MiB**, and it shut down the VMs. Five-GiB-per-VM scope limits did not prevent aggregate host memory pressure. The precise source of that pressure was not investigated; do not assert OOM absence or a root cause from this evidence alone.
5. Cold-route probes subsequently failed on disconnected/offline emulators. **These are invalid latency measurements**, not evidence that either the old or new app has a particular loading speed.
6. Tadej explicitly said **stop**. Controllers/VMs/Java processes were verified off; none are running at handoff. No automatic resume is authorized.

**Neither emulator received 1.13.** New-version burst/photo/typing/activity/seen, Android SQLCipher migration and signed-release upgrade acceptance did not complete. No uninstall, wipe, re-pair, manual admission or server deployment occurred.

The older `.local/two-emulator-triage-20261019-B8x7IM/` baseline contains useful **1.12 warm-process** findings: slow native group creation, stale DM read state and a picture/caption viewport problem. Its roughly two-second hierarchy observations include automation overhead and do not refute the reported real-phone cold-start 10–20 seconds. No dedicated photo viewport fix is claimed in this batch.

### Signing distinction and emulator hazards

- Existing Alice/Bob fixtures use the machine debug certificate `2a2314abfc7b205b1814c12af586dd2e7d73bd05bb87439d6d54a08db2bb8366`, not the original release key. An original-key release cannot update those installs in place. Do not uninstall them to force it.
- The approved-but-unfinished plan was to update those debug fixtures with matching debug 1.13/test APKs for same-source automation, then stop both and check the exact signed release separately on retained `RelayUpgradeApi36` (`emulator-5558`). Debug emulator results are not identical-release phone performance.
- The upgrade AVD previously received an original-key 1.11→1.12 update, preserving UID/PIN/profile/key fixture files; it did not contain a representative message-history fixture. Its old evidence is `.local/signed-update-20261018-hSGL7b/`.
- AVDs are in `/home/tadej/.config/.android/avd`, not the assumed `~/.android/avd`. API 36 clamps requested 1,024 MiB memory higher (previously observed 2,560 MiB). Snapshots were disabled; software rendering is used.
- **Do not rerun the prior two-VM script verbatim.** It has an existing stop marker and the aggregate memory run failed. Investigate memory read-only, propose a safer plan, and obtain approval first. Build before VMs; never run Gradle alongside them. `/tmp` is tmpfs: large artifacts belong in ignored `.local/` or disk-backed cache.
- The stock `rebuild/dev/test-mailbox-emulators.py` always calls `forget_stale`; `--repair` deletes pairing and `--deploy-allowlist` redeploys the pilot. Do not run it blindly against preserved fixture state or any personal data.

## 6. Architecture/credentials/monitor: preserve these decisions

- Configured production-style app traffic uses the private Cloudflare mailbox, not public relays. Nostr key/gift-wrap formats remain crypto/identity formats. Tor is off; do not restore relays/Tor as a quick latency workaround.
- History is on phones, locally retained seven days. Mailbox pending delivery is deleted after durable recipient receipt; uncollected envelopes have an eight-day server TTL. A transport receipt is not a seen receipt.
- Photos/videos are encrypted and hosted on Relay's own server storage. Firebase wake messages carry no message content.
- New people join through member-vouched QR pairing; founders are bootstrap identities. Do not manually add every new member or change the allowlist without an explicit approved need.
- Owner recovery/control panel was removed; ten consecutive wrong calculator codes wipe app data. Do not probe PINs or run wipe tests on preserved/user data.
- Private config already exists: `app/google-services.json`, `.local/google-services.json`, `.local/mailbox.properties`, `.local/signing.properties` and ignored original signing material. Do not print/copy secrets into handoffs, issues or commits. Earlier “Firebase files missing” paragraphs are obsolete; no credential re-upload is needed for this task.
- `relay-mailbox-probe.timer` is **active**, rechecked October 20. It was left untouched. This is monitor status, not a new live server health/latency assertion. No server deployment changed in the fixes/build/emulator follow-up.

## 7. Resuming later: order and exact host commands

**Do not execute this section without a new approved plan.** Re-read Tadej's current instructions and this handoff, inspect dirty state, then ask what he wants next. The existing APK is ready for an approved update test; host tests already passed and need not be repeatedly rerun just to resume the conversation.

If host checks/build are requested, use the mailbox worktree and JDK 17, not the system JDK 27. Example reproducing the passed final host tasks, with a unique transient scope, no CPU quota and an 8-GiB memory ceiling:

```sh
cd /home/tadej/Documents/GIT/Relay-cloudflare-mailbox
findmnt --target .local
systemd-run --user --scope --unit="relay-host-check-$(date +%s).scope" \
  -p MemoryMax=8G -p MemorySwapMax=0 -p TasksMax=1024 \
  env JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
  PATH=/usr/lib/jvm/java-17-openjdk/bin:/usr/local/bin:/usr/bin \
  ./gradlew --offline --no-daemon --max-workers=4 --console=plain \
  -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk \
  '-Dorg.gradle.jvmargs=-Xmx3072m -XX:ActiveProcessorCount=8 -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -PrelayPreview=true \
  :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

For test APKs, replace task names with `:app:assembleDebug :app:assembleDebugAndroidTest`. Server host checks, with no live deployment:

```sh
cd /home/tadej/Documents/GIT/Relay-cloudflare-mailbox/server/cloudflare
npm run typecheck
npm test
```

Preserve generated logs/APKs/reports before replacing them when useful. Logs piped through `tee` need Bash `pipefail`/the actual command's exit code; do not mistake a wrapper success for test success. zsh does not split bare string variables like Bash, and its `path` variable controls `PATH`; use arrays or non-special variable names. For diff review use `git -c core.whitespace=cr-at-eol diff --check`, since existing Kotlin files include CRLF.

After separately approved device work, validate actual v22→v23 history/key/PIN preservation, true cold People/list/DM/group content (not false empty), native Create group, five rapid texts and a representative photo, recipient timing/order, offline/process restart, typing/activity, serial DM seen and group reader counts. Then synthetic-only deletion/retention/privacy/wipe checks. Keep failures and automation overhead explicit; do not close issues or publish on host tests alone. Real phones/larger groups and production readiness for ten people remain separate acceptance gates.

## 8. Further reading and vault

1. `rebuild/VERIFICATION-1.13-preview.md` — passed host checks, exact APK/signing facts.
2. `rebuild/FIXES-2026-10-19.md` — implementation/home acceptance map; its top update supersedes historical unbuilt paragraphs below.
3. `.local/emulator-113-20261019-ODRY32/RESULTS.md` — stopped emulator attempt; no 1.13 acceptance.
4. `rebuild/HANDOFF-CODEX-2026-10-18.md` — historical Firebase/signing/architecture background only; several top-level Git and validation claims are stale.

Vault: `/home/tadej/Documents/SecondBrain/`. Read `_CLAUDE.md` before writes and follow Obsidian skills. Key notes: `Projects/Relay.md`, `Debugging/2026-10-19 — Relay Cold Start Loading.md`, `Debugging/2026-10-19 — Relay Two Emulator Baseline.md`, `Debugging/2026-10-19 — Relay Phone Regression Triage.md`, and `Tasks/Relay — Firebase Push Acceptance.md`. Save meaningful updates with daily/log propagation; do not overwrite/delete existing notes without approval.
