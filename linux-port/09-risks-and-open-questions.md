# 09. Risks and open questions

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

## Risks

| ID | Risk | Likelihood / impact | Mitigation | Owner WP |
|---|---|---|---|---|
| R1 | Extracting `:core` changes Android behaviour without anyone noticing. | Medium / high | Move files without editing them, run the existing suite unchanged, check that byte-identical bodies come out of the codec round-trip tests, and run the two-emulator harness before and after. | WP03 |
| R2 | A dormant desktop blocks deletion and fills a recipient's 32 MiB queue. | Medium / medium | Devices go dormant after 72 h, `gap` flag, and a primary-only device cap of 3. | WP04 |
| R3 | A linked desktop is stolen, and it holds the full identity. | Low / high | Optional passphrase. Unlinking cuts server access only in the sense of cursors: the key still authenticates. The real fix is **"Reset identity"** (new keys, re-pair contacts), and the UI states this plainly. A future server-side device key (each device signs with its own NIP-98 sub-key, authorised by the primary) would make unlinking real. It is recorded as a follow-up, not planned here. | WP05, WP06 |
| R4 | The ordering assumption for rotation breaks, so a contact message encrypted to the new key reaches the desktop before `SYNC KEYS`. | Low / medium | Same-queue ordering by `seq`. The rotation broadcast waits for `SYNC KEYS` to be `stored`. The desktop holds back undecryptable `TYPE:ENC` for 10 min and retries when keys arrive. | WP05 |
| R5 | Call frames exceed the 16 KiB signal cap. | Medium / low | Deflate, trickle ICE, candidate batching, and the server variable at 32 KiB. Measured in WP02. | WP02, WP11 |
| R6 | A sleeping Android phone rings late or not at all (Doze, OEM battery killers, FCM deprioritisation). | Medium / high | High-priority FCM sent only for invites (so Google doesn't deprioritise the app), a direct socket fetch inside the FCM callback, and a per-device ring-latency measurement. The OEM "don't optimise battery" guidance already exists for messaging. | WP13 |
| R7 | `webrtc-java` audio problems on PipeWire-only systems or Wayland. | Medium / medium | Test on Ubuntu 24.04 (PipeWire with pipewire-pulse), plus one other distro. Fallback is ALSA default devices. No global hotkeys on Wayland. | WP02, WP14 |
| R8 | The Cloudflare free Realtime allowance needs a card, or changes. | Low / medium | Check during WP02. Egress alerting. Group calls fail gracefully with "Voice unavailable" if the SFU returns 402/403. | WP02, WP15 |
| R9 | The LiveKit `FrameCryptor` format can't be reproduced on the desktop, so there's no group E2EE. | Medium / medium | Gated (LP-D16). The honest UI label stays. | WP17 |
| R10 | Malicious files: a hostile peer sends an executable or a path-traversal name. | Medium / high | Sanitise names, never auto-open, warn on executables, and add no exec bit when saving. | WP09 |
| R11 | Installer size: a bundled JVM plus 58 MB of nostr natives (all platforms) plus WebRTC natives. | High / low | Strip the non-target natives from the nostr jar at packaging time (keep `linux-x86-64` or `darwin-aarch64` only), and use a jlink-trimmed runtime. Target ≤150 MB. | WP10, WP18 |
| R12 | Gatekeeper and permission friction on unsigned macOS builds. | High / low | Documented first-launch steps. Re-prompts after updates are expected. Notarization is available later for $99/year. | WP18 |
| R13 | Free-plan rows budget exhausted by sync and files. | Low / high | Measure in WP04 and WP09. Sent-copies are capped to text plus refs, and blobs are never duplicated (the self pubkey is added as a recipient instead). | WP04, WP09 |
| R14 | Licences of new dependencies (video player, dbus-java, Bouncy Castle) clash with distribution. | Medium / medium | A licence table in the WP10 evidence, built from the shipped archive, not from the dependency list. | WP08, WP10 |

## Open questions for the owner

The defaults below are what the plan assumes until you answer.

- **Q1. Answering a call while Relay is locked.** Default: answering opens a call-only screen, and the chats still
  need the calculator code.
- **Q2. Caller name on the lock screen.** Default: it follows the existing notification preview setting.
- **Q3. Desktop retention.** Default: the same 7 days as the phone (LP-D08). A longer "keep on this computer"
  option could come later.
- **Q4. Who may end a group call for everyone.** Default: whoever started it. Alternative: the group creator.
- **Q5. Maximum file size.** Default: 100 MiB. Higher is possible, at about 1 row per MiB per file on the free plan.
- **Q6. Mac testing.** Who has an Apple Silicon Mac to run the WP18 acceptance? Until someone does, macOS stays
  "built in CI, not verified".
