# Linux port, calls, group voice: lane board

Lanes: `planned` → `doing` → `for_review` → `done` (owner merged). Update the WP frontmatter, this table and
the log together.

| WP | Phase | Title | Depends on | Lane |
|---|---|---|---|---|
| [WP01](wps/WP01-spike-jvm-transport.md) | L0 | Spike: JVM transport and crypto interop with a real phone | — | planned |
| [WP02](wps/WP02-spike-webrtc-sfu-turn.md) | L0 | Spike: WebRTC Android↔Linux audio, Cloudflare TURN and SFU, signal sizes | — | planned |
| [WP03](wps/WP03-core-module-extraction.md) | L1 | Extract the shared `:core` JVM module | WP01 | planned |
| [WP04](wps/WP04-server-device-cursors.md) | L1 | Server: per-device cursors, dormancy, per-device media confirmation | — | planned |
| [WP05](wps/WP05-phone-linking-and-sync.md) | L1 | Phone: link a computer, link slot, sync channel, sent-copies | WP03, WP04 | planned |
| [WP06](wps/WP06-desktop-shell-link-text.md) | L2 | Desktop shell: storage, secrets, linking, text messaging | WP03, WP05 | planned |
| [WP07](wps/WP07-desktop-groups-and-ui.md) | L2 | Desktop: groups, profiles, Discord-style UI completion | WP06 | planned |
| [WP08](wps/WP08-desktop-media.md) | L2 | Desktop: photos and videos | WP06 | planned |
| [WP09](wps/WP09-files-both-platforms.md) | L2 | Files on phone and desktop (`TYPE:FILE`, up to 100 MiB) | WP03, WP04, WP06 | planned |
| [WP10](wps/WP10-linux-packaging-release.md) | L2 | Linux packaging and desktop release | WP06, WP07, WP08, WP09 | planned |
| [WP11](wps/WP11-server-call-support.md) | L3 | Server: TURN credentials, urgent wake, signal size | WP02, WP04 | planned |
| [WP12](wps/WP12-core-call-engine.md) | L3 | `:core` 1:1 call engine and `TYPE:CALL` codec | WP03 | planned |
| [WP13](wps/WP13-android-1on1-calls.md) | L3 | Android: 1:1 calls (WebRTC, Telecom, FCM ring) | WP11, WP12 | planned |
| [WP14](wps/WP14-desktop-1on1-calls.md) | L3 | Desktop: 1:1 calls | WP06, WP12 | planned |
| [WP15](wps/WP15-server-callroom-sfu.md) | L4 | Server: CallRoom Durable Object and SFU proxy | WP02, WP11 | planned |
| [WP16](wps/WP16-group-call-clients.md) | L4 | Group voice on phone and desktop | WP13, WP14, WP15 | planned |
| [WP17](wps/WP17-group-call-e2ee.md) | L4 | Group call E2EE (frame encryption) — gated | WP16 | planned |
| [WP18](wps/WP18-macos-unsigned-dmg.md) | L2 | macOS unsigned `.dmg` (Apple Silicon) | WP06, WP10 | planned |

Totals: 18 work packages, 80 acceptance criteria, 17 decisions (LP-D01–LP-D17), 14 risks, 6 open questions.

## Log

- 2026-10-23 UTC — Planning set created on `feat/linux-port` from `main@af86743`. All WPs `planned`; nothing implemented.
