---
id: WP06
title: "Desktop shell: storage, secrets, linking, text messaging"
phase: L2
lane: planned
depends_on: [WP03, WP05]
specs: ["04 §3–5", "LP-D04, LP-D05, LP-D08"]
owned_files:
  - "desktop/**"
  - "settings.gradle"
  - "gradle/verification-metadata.xml"
---

# WP06 — Desktop shell: storage, secrets, linking, text messaging

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

A runnable Compose Desktop app that links to a phone and sends/receives direct text messages, durable across restarts.

## Steps

1. `:desktop` module with Compose plugin; app skeleton, Discord-shaped layout scaffold, theme from `PaletteSpec`.
2. `SecretStore` (java-keyring; Secret Service on Linux, Keychain on macOS) + optional Argon2id passphrase; `sqlite-jdbc` encrypted DB and migrations.
3. Link screen: show `RELAYLINK` QR, poll slot, open bundle, persist identity, register cursor start.
4. Mailbox client loop (`device=`), sync → store → ack ordering, gap banner; outbox with the same retry rule as the phone.
5. Direct chats: list, conversation, send, receipts, replies, delete-for-me, typing (receive + send), notifications, tray, 7-day retention job.

## Acceptance criteria

- **WP06-AC1** Fresh install → link with emulator phone → first incoming message shown; restart → history and identity persist; DB file is not readable as plain SQLite.
- **WP06-AC2** Ack is sent only after the local commit (test with injected crash between store and ack: message re-delivered, stored once).
- **WP06-AC3** Messages from an unmodified 1.15 contact appear on both phone and desktop; replies from desktop arrive at the contact and appear as outgoing on the phone.
- **WP06-AC4** With no secret service available, the app requires a passphrase and refuses to start without it.
- **WP06-AC5** Retention removes messages older than 7 days on desktop.

## Evidence

Write `linux-port/evidence/WP06.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
