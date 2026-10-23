# 08. Phases

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

There is one branch and one PR per phase, cut from a merged `main`. Only the owner merges. The app stays buildable
and releasable after every phase. The order puts the cheap kill-gates first (L0), then the server and shared code
that everything depends on (L1), then the clients.

| Phase | WPs | Exit gate |
|---|---|---|
| **L0 Spikes** | WP01, WP02 | Go/no-go on each of three things: the JVM transport talks to a real phone, WebRTC audio works between Android and Linux, and the Cloudflare SFU and TURN work on our account with the free allowance. Signal frame size is measured. |
| **L1 Shared core and multi-device server** | WP03, WP04, WP05 | `:core` extracted with an unchanged, green Android suite. Server device cursors are deployed to the **test** Worker (`relay-mailbox-test`) with 1.15 phones still working. The phone can link a test client, sync rotation, and send sent-copies. |
| **L2 Desktop messaging and files** | WP06, WP07, WP08, WP09, WP10, WP18 | The desktop is linked to a real phone, does messaging, groups, media and files, and is packaged as `.deb` + AppImage. Phone file support is released **before** anyone sends a file (rollout below). The macOS `.dmg` builds in CI and has been opened on one Mac. |
| **L3 1:1 calls** | WP11, WP12, WP13, WP14 | 06 §6 acceptance on real devices, including the TURN-only path and ringing a sleeping phone. |
| **L4 Group calls** | WP15, WP16, WP17 | A 5-person call per 01 success criteria. Egress is measured. WP17 is either shipped or explicitly recorded as not shipped, with the reason. |

## Rollout order (users run mixed versions)

1. **Server first** (WP04). Device cursors are backward compatible (LP-D14), so a 1.15 phone sees no change. Deploy
   to the test Worker, run the pilot probe for 48 h, then deploy to production.
2. **Phone 1.16**:
   - `:core` extraction (no visible change);
   - the unknown-`TYPE` renderer;
   - **receiving** `TYPE:FILE`;
   - linked computers;
   - sync.
   Every contact should be on 1.16 before step 3.
3. **Phone 1.17**: **sending** files is enabled. Desktop 0.1 is released at the same time.
4. **Server call endpoints** (WP11), then **phone 1.18 and desktop 0.2** with 1:1 calls. A pre-1.18 contact
   receives the `invite` as an unknown message. The caller's UI shows "<name> needs to update Relay to receive
   calls" when there is no `ringing` response within 10 s.
5. **CallRoom** (WP15), then **phone 1.19 and desktop 0.3** with group voice. Group members on older builds see
   "voice active" as an unsupported message and can't join.

## Test environments

- **Server:** the `vitest` Workers runtime (existing `npm test`), plus the live test Worker for probes. New tests
  per WP are listed in the WP.
- **Android:** unit tests on the JVM. The two-emulator harness (`rebuild/dev/test-mailbox-emulators.py`) is
  extended for sync and link. Calls need **real phones** for the audio, FCM wake and Telecom checks. Emulators are
  recorded as such in evidence.
- **Desktop:** JVM unit tests plus Compose UI tests (`runComposeUiTest`). An end-to-end run against the test Worker
  with a headless emulator phone. Audio uses a virtual PulseAudio sink and source (`pactl load-module
  module-null-sink`) for automated call tests, and real headsets for acceptance.
- **macOS:** the GitHub Actions macOS runner builds and smoke-launches the app. Interactive checks (Gatekeeper,
  microphone prompt, call audio) need a person with a Mac (09 Q6).

## Evidence

Each WP appends to `linux-port/evidence/WPxx.md` in the format of `rebuild/VERIFICATION*.md`: commands, parsed
numbers, and **what did not run**. Rows-written measurements come from `EXPOSE_METRICS=1` and are quoted with dates.
