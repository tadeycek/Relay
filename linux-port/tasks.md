# Linux port, calls, group voice: lane board

Lanes: `planned` → `doing` → `for_review` → `done` (owner merged). Update the WP frontmatter, this table and
the log together.

| WP | Phase | Title | Depends on | Lane |
|---|---|---|---|---|
| [WP01](wps/WP01-spike-jvm-transport.md) | L0 | Spike: JVM transport and crypto interop with a real phone | — | doing |
| [WP02](wps/WP02-spike-webrtc-sfu-turn.md) | L0 | Spike: WebRTC Android↔Linux audio, Cloudflare TURN and SFU, signal sizes | — | doing |
| [WP03](wps/WP03-core-module-extraction.md) | L1 | Extract the shared `:core` JVM module | WP01 | doing |
| [WP04](wps/WP04-server-device-cursors.md) | L1 | Server: per-device cursors, dormancy, per-device media confirmation | — | doing |
| [WP05](wps/WP05-phone-linking-and-sync.md) | L1 | Phone: link a computer, link slot, sync channel, sent-copies | WP03, WP04 | doing |
| [WP06](wps/WP06-desktop-shell-link-text.md) | L2 | Desktop shell: storage, secrets, linking, text messaging | WP03, WP05 | doing |
| [WP07](wps/WP07-desktop-groups-and-ui.md) | L2 | Desktop: groups, profiles, Discord-style UI completion | WP06 | doing |
| [WP08](wps/WP08-desktop-media.md) | L2 | Desktop: photos and videos | WP06 | doing |
| [WP09](wps/WP09-files-both-platforms.md) | L2 | Files on phone and desktop (`TYPE:FILE`, up to 100 MiB) | WP03, WP04, WP06 | doing |
| [WP10](wps/WP10-linux-packaging-release.md) | L2 | Linux packaging and desktop release | WP06, WP07, WP08, WP09 | doing |
| [WP11](wps/WP11-server-call-support.md) | L3 | Server: TURN credentials, urgent wake, signal size | WP02, WP04 | doing |
| [WP12](wps/WP12-core-call-engine.md) | L3 | `:core` 1:1 call engine and `TYPE:CALL` codec | WP03 | doing |
| [WP13](wps/WP13-android-1on1-calls.md) | L3 | Android: 1:1 calls (WebRTC, Telecom, FCM ring) | WP11, WP12 | doing |
| [WP14](wps/WP14-desktop-1on1-calls.md) | L3 | Desktop: 1:1 calls | WP06, WP12 | doing |
| [WP15](wps/WP15-server-callroom-sfu.md) | L4 | Server: CallRoom Durable Object and SFU proxy | WP02, WP11 | doing |
| [WP16](wps/WP16-group-call-clients.md) | L4 | Group voice on phone and desktop | WP13, WP14, WP15 | doing |
| [WP17](wps/WP17-group-call-e2ee.md) | L4 | Group call E2EE (frame encryption) — gated | WP16 | doing |
| [WP18](wps/WP18-macos-unsigned-dmg.md) | L2 | macOS unsigned `.dmg` (Apple Silicon) | WP06, WP10 | doing |

Totals: 18 work packages, 80 acceptance criteria, 18 decisions (LP-D01–LP-D18), 14 risks, 6 open questions.

## Current evidence

- [WP09 phone](evidence/WP09-phone.md) and [desktop](evidence/WP09-desktop.md): 100 MiB encrypted files implemented; app 244 tests, desktop 97 gated tests passed before later WP07/WP13 edits. Real phone + Android memory gate unrun.
- [WP11](evidence/WP11.md), [WP12](evidence/WP12.md): call server + shared engine committed `294e31d`; core 500 (one opt-in provider skip), server 360. Deployment, real calls unrun.
- [WP15](evidence/WP15.md) / [WP15-g5](evidence/WP15-g5.md): CallRoom and SFU proxy committed `645c710`, frozen roster and revocation `a016e91`; owner-signed roster attestation now verified server-side (`c5321dc`). Still unrun: live SFU, deploy, 48 h pilot.
- [WP16](evidence/WP16-android.md) / [WP16-desktop](evidence/WP16-desktop.md) / [WP16-core](evidence/WP16-core.md): both clients wired to the real chat path, SFU, UI and foreground service (`785bc46`, `f92b521`). No device, emulator or live SFU run.
- [WP17](evidence/WP17.md): key schedule, frame crypto, replay window (desktop only) and security-label rule committed and wired into both peers (`785bc46`, `f92b521`). Android's Java `FrameCryptor` exposes no per-frame hook, so the replay window cannot be enforced there. WP17-AC1/AC2 unrun.
- [WP07](evidence/WP07.md), [WP13](evidence/WP13.md): committed and reviewed; WP07-AC2 phone roster sync and the WP13 device criteria remain unrun.


- [WP05](evidence/WP05.md): device authorization (G1) — phone-only admin key, per-device auth keys, migration, revocation with socket close, duplicate/cross-purpose key rejection, send/revoke race and phone lost-reply handling. Linking (link slot, bundle, sync, sent copies) is part B and still open.

- [WP06](evidence/WP06.md): Compose Desktop module builds, 17 tests pass, SQLCipher-encrypted store proven (wrong key refused, no plaintext on disk), encrypted text sent/received against a local Worker and stored, packaged distributable launches. Phone linking not implemented.

- [WP04](evidence/WP04.md): per-device cursors, dormancy/gap and per-device media confirmation implemented with 159 local tests; AC4 blocked by the upload→enrol→send race, G1 primary spoofing is a WP05 gate, deploy/pilot not run.

- [WP02 E2EE interop](evidence/WP02-e2ee-interop.md): Android FrameCryptor and Linux webrtc-java share one AES-GCM frame layout, local and through the live SFU; WP17 feasible; no frame-level replay protection.

- [WP02 live SFU + Android](evidence/WP02-live-sfu-android.md): Android and Linux publish/subscribe through the real SFU; silence rejected both ways; E2EE/egress/hardware open.

- [WP02 live SFU](evidence/WP02-live-sfu.md): parent fresh four-run suite verified three Linux participants/six streams; Android SFU, egress, E2EE and hardware gates remain open.

- [WP02 live TURN](evidence/WP02-live-turn.md): Android↔Linux forced relay/TLS443 and silence mutations independently rerun; SFU/E2EE/hardware and independent UDP-blocking gates remain open.

- [WP02 Android↔Linux](evidence/WP02-cross-platform.md): native generated audio and negative mutations verified; live/hardware gates open.

- [WP01 actual app UI](evidence/WP01-app-ui.md): production conversation receive/reply verified locally; live gate open.

- [WP02](evidence/WP02.md): Linux native audio and Android initialization passed; live/cross-platform acceptance open.

- [WP01](evidence/WP01.md): local interop passed; original-app UI and live-provider checks open.

## Log

- 2026-10-24 UTC — WP09 phone+desktop files committed `c3b6aa8`, WP11/12 calls committed `294e31d`. WP07/WP13/WP15 in uncommitted implementation/review-fix, WP10 packaging in progress; no Worker deployment or main merge. Lanes updated to doing only; no WP marked review-ready.


- 2026-10-24 UTC — WP11 + WP12 committed `294e31d` (TURN ICE credentials from env only, urgent wake budgets, signal bounds; `TYPE:CALL` codecs + pure 1:1 state machine with glare/accept-race/busy/ICE-restart/timeouts). Review fixes: urgent budget spent synchronously, full nonce cache refuses instead of evicting, a busy device no longer ends a call another device can answer, winning device notifies siblings. Verified core 500 + server 360 + tsc clean. Not deployed, no live TURN, no FCM, no device.

- 2026-10-24 UTC — WP02 5-party SFU load + egress measured (`40a0116`); WP03 `2a161d3`; WP04 `9477b28`; WP06 desktop shell `45ac4a1`; WP05-A implemented + security-reviewed + hardened. Nothing deployed or merged.

- 2026-10-23 UTC — WP03 `doing` (AC1–AC3 met, single-emulator UI smoke green, AC4 two-emulator not run); WP04 `doing` (159 tests, AC4 blocked, AC6 not run). Neither deployed or merged.

- 2026-10-23 UTC - WP02 remains `doing`; AC5 frame-E2EE interop proven (local + live SFU), parent rerun. Key distribution, egress, hardware, UDP-block gates open.

- 2026-10-23 UTC - WP02 remains `doing`; Android+Linux live SFU publish/subscribe independently rerun (3 cases). E2EE, egress, hardware, UDP-block gates open.

- 2026-10-23 UTC — WP02 remains `doing`; fresh parent live SFU suite passed positive + three silence cases, each three Linux participants/six streams and nine inactive track readbacks. Android SFU/egress/E2EE/hardware gates remain open.

- 2026-10-23 UTC — WP02 remains `doing`; live Android↔Linux TURN/TLS443 and both silence mutations independently rerun. No firewall/billing/deployment changes; SFU/E2EE/hardware gates remain open.

- 2026-10-23 UTC — WP02 remains `doing`; local Android↔Linux media proof verified, cloud/hardware gates open.

- 2026-10-23 UTC — WP01 remains `doing`; actual app UI acceptance added, root unit suite 329 passed. No main merge.

- 2026-10-23 UTC — WP02 `doing`: parent-rerun native probes and encrypted frame measurements; live/cross-platform gates open.

- 2026-10-23 UTC — WP01 `doing`: JVM + Android companion and local Worker verified; original-app/live-service gates open.
- 2026-10-23 UTC — Planning set created on `feat/linux-port` from `main@af86743`. All WPs `planned`; nothing implemented.
- 2026-10-23 UTC — WP03 `doing`: `:core` extracted (70 files moved), AC1–AC3 verified locally (329 → 198 core + 146 app + 15 new; 328 golden entries); AC4 two-emulator harness NOT RUN. Evidence: evidence/WP03.md. Uncommitted.
