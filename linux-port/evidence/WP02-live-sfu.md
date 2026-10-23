# WP02 — parent-rerun live Cloudflare SFU media

Date: 2026-10-23 UTC. Branch: `feat/linux-port`. **WP02 remains doing**, not a production call implementation.

## Fresh final-source live suite

The parent independently executed the full suite after the implementation delegate returned:

```sh
cd linux-port/spikes/calls
RELAY_SPIKE_LIVE=1 PYTHONDONTWRITEBYTECODE=1 \
  JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 python3 sfu_media_suite.py
```

This invocation **made fresh live provider requests** (`live_executed_this_invocation=true`), rather than using
`--collect-existing`. It compiled the latest sources, ran 8 offline SFU units, killed all 7 guard mutations,
then verified the positive and three distinct real silent-publisher cases. The suite returned exit0.

Actual reports: [suite and source hashes](../spikes/calls/evidence/sfu-media-suite.json),
[positive](../spikes/calls/evidence/sfu-live-positive.json),
[silent0](../spikes/calls/evidence/sfu-live-silent-0.json),
[silent1](../spikes/calls/evidence/sfu-live-silent-1.json),
[silent2](../spikes/calls/evidence/sfu-live-silent-2.json).

All four runs created **three Linux native participants**, one published generated audio track per participant and
two received tracks per participant: **six distinct directed streams**. Each stream is mapped from the successful
provider subscription MID to its publisher and measured using its own native receiver statistics and PCM sink.
No mailbox/Worker deployment was involved; Python is the throwaway local server-side coordinator.

Positive measurements (publisher→receiver, RTP packets / payload bytes / decoded PCM samples / RMS):

- 1→0: 448 / 35,964 / 431,520 / 8,397.89
- 2→0: 446 / 35,964 / 431,520 / 8,420.02
- 0→1: 425 / 34,101 / 410,400 / 8,404.29
- 2→1: 424 / 34,182 / 410,400 / 8,415.65
- 0→2: 410 / 32,886 / 394,560 / 8,414.64
- 1→2: 410 / 32,886 / 394,560 / 8,395.77

Positive runtime **28.60s** including negotiation/cleanup; silent0/1/2 **12.83 / 13.39 / 13.96s**. Each silence case
kept RTP flowing but rejected low PCM energy on exactly the two receivers subscribed to the muted publisher;
the other four streams passed. An arbitrary timeout/error is not accepted as a successful silence mutation.
All four native processes exited normally. In each run **all nine allocated track records** were force-closed
and confirmed inactive by exact-session API readback.

## Review hardening and final-source rerun

Independent review of the first SFU delivery found three blockers: provider/pipe wall-clock bounds not enforced,
a shared recovery journal overwritten each run, and non-exception-safe native cleanup. Fixes: nested SIGALRM
watchdogs (Linux main thread) that kill/reap the native child, unique 0600 per-run journals with exact
inactive/expired/unresolved dispositions, known-MID force-close even when reconcile GET fails, an allowlisted public
report schema with exact six non-self pairs, and ownership-stack native cleanup. Offline: 28 tests passed
(23 Python, 5 native-cleanup) and 19/19 guard mutations killed; a final re-review passed with no blockers.

The first live rerun on the hardened source **failed** in the positive case (native SFU connection timeout,
participant 0 ICE FAILED, ~47 s; cleanup readbacks showed expired sessions). That is the same intermittent network
failure seen earlier, not a pass. The next full live run on the same source passed: positive plus three silence
cases, 6 streams each, 9/9 inactive records each, normal native exit. Positive RTP 411-447 packets per stream,
PCM RMS 8,385-8,426. Evidence: `../spikes/calls/evidence/sfu-media-suite.json` and `sfu-live-*.json`; the pre-hardening
run is kept in `sfu-pre-hardening-parent.json`. Reliability is therefore roughly "passes most runs", not proven stable.

## Regressions and dependencies

Parent also reran, with strict dependency verification and `--rerun-tasks`:

```sh
./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" -Dorg.gradle.jvmargs=-Xmx768m \
  --max-workers=1 --dependency-verification=strict --console=plain --rerun-tasks \
  -p linux-port/spikes/calls installDist probe
./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" -Dorg.gradle.jvmargs=-Xmx768m \
  --max-workers=1 --dependency-verification=strict --console=plain --rerun-tasks \
  -p linux-port/spikes/calls/android assembleDebug
```

Desktop **5 tasks executed**, Android **34 tasks executed**, both BUILD SUCCESSFUL. Existing Android SDK XML /
compileSdk36-vs-AGP8.3.2 and unstripped-native warnings remain visible. Local two-Linux-peer generated media,
two data messages and native cleanup passed. No new dependencies or checksum pins.

Combined final offline Python sweep: **48 tests, 47 passed, 1 skipped, 0 failures/errors**.
[Parsed summary](../spikes/calls/evidence/wp02-unit-summary.json). The skipped case is the older env-gated SFU
API-only smoke; it is not counted as a provider pass. Actual live media is independently proven by the separate
four-run suite above. [Live TURN evidence](WP02-live-turn.md) records actual Android↔Linux relay/TLS443.

## Historical failures and remaining gates

The [delegate snapshot](../spikes/calls/SFU-LIVE-EVIDENCE.md) retains earlier successes, intermittent connection/
publication failures and a cleanup hole found during development. The final coordinator reconciles exact session
allocations even if a tracks/new response failed, before closing them. One earlier exploratory allocation's eventual
expiry was not independently recovered after its old private journal was superseded; do not claim every exploration
was proven clean. All four **parent final-suite** runs did verify their nine inactive records.

A fresh suite passing once supersedes the delegate's missing final-source sweep, **not** the observed network
intermittency. No long-duration/repeated-load guarantee follows. In particular:

- SFU is **Linux-only here**. Android↔SFU publish/subscribe is unrun, so AC2 stays partial.
- No provider-dashboard/account-wide egress-per-minute or billing verification; RTP byte counts are not billing.
- Three participants do not establish the five-participant readiness gate or proposed17-participant product cap.
- Generated audio is not microphone capture, physical phone/headset testing or listening-quality acceptance.
- **No frame E2EE**: the SFU terminates transport encryption and can access media. No E2EE claim is authorized.
- No room authorization, production Relay call engine, Worker room proxy, mute-expiry recovery or client UI.
- Independent UDP firewall blocking, final Android/TURN encrypted signal-size specimens, minimum OS/macOS and
  account payment-method history remain separate gates. No billing/plan, firewall or deployment changes were made.

The result retires the basic live-SFU media feasibility unknown. It does not close WP02 or authorize merging main.
