---
id: WP03
title: "Extract the shared `:core` JVM module"
phase: L1
lane: doing
depends_on: [WP01]
specs: ["04 §1–2", "09 R1"]
owned_files:
  - "settings.gradle"
  - "core/**"
  - "app/build.gradle (dependency on :core)"
  - "app/src/main/java/com/relay/app/** (moved files only)"
  - "app/src/test/** (moved tests)"
  - "gradle/verification-metadata.xml"
---

# WP03 — Extract the shared `:core` JVM module

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Move pure protocol code into `:core` with zero behaviour change, and give it the injectable seams the desktop needs.

## Steps

1. Create `:core` (Kotlin/JVM, same Kotlin version as `:app`, JVM target 17).
2. `git mv` the files listed in 04 §2 keeping package names; move their tests.
3. Split `InnerEnvelope`, `MailboxAuth`, `MailboxMedia`, crypto calls into pure parts with interfaces (`KeyMaterial`, `NostrSigner`, `HttpTransport`).
4. Replace `android.util.Base64` with `java.util.Base64` in moved code; byte-identical outputs asserted by tests.
5. Regenerate dependency verification metadata with the full task set.

## Acceptance criteria

- **WP03-AC1** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` green; test counts before/after recorded (moved tests counted once).
- **WP03-AC2** Golden tests: every body codec in `:core` produces byte-identical output to the pre-move implementation for a fixed corpus (recorded from `main` before moving).
- **WP03-AC3** `:core` has no dependency on Android artifacts (`./gradlew :core:dependencies` shows none) and no `android.` import (grep).
- **WP03-AC4** Two-emulator harness passes text, media, group message before and after (evidence).

## Evidence

Write `linux-port/evidence/WP03.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
