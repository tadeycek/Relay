# Relay for Linux (and macOS), 1:1 calls and group voice: plan

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

Status: **planning only, nothing implemented.** Written 2026-10-23 on branch `feat/linux-port`, from `main` at
`af86743` (Calculator 1.15 preview), after a design discussion with the owner plus web research done the same day.
Nothing in `app/` or `server/` is changed by this folder.

## Execution status

WP01 and WP02 now have isolated runnable spikes and [evidence](tasks.md). Both remain `doing`: local results
are verified, but original-app, live-provider and cross-platform call acceptance remain open. No production
application/server implementation has been changed. The original planning status below describes the design docs.

## One-paragraph summary

A Linux desktop client for Relay that is **the same app under the hood**. It uses the same Cloudflare mailbox, the
same NIP-17 gift wraps with the inner Tink layer, and the same encrypted media store. Messages, photos, videos and
files are kept only in a local encrypted database, and the server holds them only until every device has them. The
desktop is a **linked device of the phone's identity**: it holds the same keys and reads the same mailbox with its
own server-side cursor. Contacts' phones therefore need no update to reach it. The UI is Discord-shaped:
- a sidebar of people and groups;
- a conversation pane;
- a voice bar when a call is active.

Files become a first-class message type on both phone and desktop, up to 100 MiB. **1:1 calls** are peer-to-peer
WebRTC audio, signalled over the existing mailbox socket, with Cloudflare TURN as fallback. They stay end-to-end
encrypted. **Group calls** use Cloudflare Realtime SFU (option A), with an initial 17-participant product cap subject to load testing, and a Durable Object
owns the room state. Frame-level E2EE for group calls is planned as a gated follow-up.

## Files in this folder

- [01-scope-and-goals.md](01-scope-and-goals.md): what is being built, and what deliberately is not.
- [02-research-findings.md](02-research-findings.md): verified facts with sources and dates, plus labelled estimates.
- [03-decisions.md](03-decisions.md): decisions LP-D01 to LP-D17, each with rationale and the trade-off accepted.
- [04-architecture.md](04-architecture.md): modules, desktop stack, storage, linked-device model, sync.
- [05-protocol-changes.md](05-protocol-changes.md): server API changes and new message bodies. This is the contract.
- [06-calls-1on1.md](06-calls-1on1.md): 1:1 voice call spec, covering the state machine, signalling, wake-up, Android and desktop.
- [07-group-calls-sfu.md](07-group-calls-sfu.md): group voice over Cloudflare Realtime SFU, covering the room DO, joins, budget and E2EE.
- [08-phases.md](08-phases.md): phases L0 to L4 with exit gates.
- [09-risks-and-open-questions.md](09-risks-and-open-questions.md): risks, mitigations, and questions for the owner.
- [10-readiness-gates.md](10-readiness-gates.md): binding security, quota and platform acceptance gates.
- [tasks.md](tasks.md): lane board for WP01 to WP18.
- [wps/](wps/): one prompt per work package, with owned files and acceptance criteria.

## Headline decisions

- **Linked device, shared identity, per-device server cursors (LP-D02).** The desktop is not a new contact. The
  mailbox keeps one queue per identity and deletes a message only after every *active* device has acked it.
- **Kotlin + Compose Desktop (LP-D04)**, with the protocol code extracted into a shared `:core` JVM module used by
  both apps.
- **Files up to 100 MiB as 25 MiB encrypted parts (LP-D07)** on the existing `/v1/media` store.
- **1:1 calls are P2P WebRTC with TURN fallback (LP-D09)**, so audio stays end-to-end encrypted (DTLS-SRTP between
  the two endpoints).
- **Group calls use Cloudflare Realtime SFU (LP-D11)** with a `CallRoom` Durable Object. The SFU can technically
  read group audio until the E2EE package (WP17) lands.
- **macOS as an unsigned `.dmg` (LP-D17, WP18)** for Apple Silicon, built in CI. Linux (`.deb` + AppImage) is the
  primary target.
- **The phone is the primary device (LP-D03).** It alone rotates keys, pairs contacts and links or unlinks
  computers.

## Reading order

Read `01`, then `03`, `04` and `05`, then whichever of `06` or `07` you are working on. Then read the WP and
`tasks.md`.
