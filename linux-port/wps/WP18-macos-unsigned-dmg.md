---
id: WP18
title: "macOS unsigned `.dmg` (Apple Silicon)"
phase: L2
lane: doing
depends_on: [WP06, WP10]
specs: ["LP-D17", "02 §B macOS", "09 R12, Q6"]
owned_files:
  - "desktop/build.gradle.kts (macOS target, Info.plist keys)"
  - "desktop/packaging/macos/**"
  - ".github/workflows/desktop-macos.yml (new)"
  - "linux-port/evidence/WP18.md"
---

# WP18 — macOS unsigned `.dmg` (Apple Silicon)

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its corrections are mandatory
acceptance requirements. An unrun or failed release gate keeps this WP below `for_review`.

## Goal

Build, in CI, an unsigned `.dmg` of the same desktop app for Apple Silicon Macs, with documented first-launch steps.

## Steps

1. `TargetFormat.Dmg`, bundle id, icon, `NSMicrophoneUsageDescription`; no signing identity.
2. GitHub Actions macOS runner job: build, smoke-launch (`--self-test` flag that opens DB/secret store and exits), upload artifact + SHA-256.
3. Keep only `darwin-aarch64` nostr natives and `macos-aarch64` webrtc-java natives in the bundle.
4. `INSTALL-macOS.md`: Open Anyway / `xattr` steps, expected re-prompts after updates.

## Acceptance criteria

- **WP18-AC1** CI job produces a `.dmg` and its SHA-256 on each tag; smoke launch passes on the runner.
- **WP18-AC2** `codesign -dv` output recorded (ad-hoc or unsigned stated exactly).
- **WP18-AC3** On a real Apple Silicon Mac: first launch via Open Anyway works, Keychain item created, link with phone, send/receive text — or recorded as **not run** with the reason (Q6).
- **WP18-AC4** Microphone prompt appears and a 1:1 call works once WP14 has landed (may be a follow-up row).

## Evidence

Write `linux-port/evidence/WP18.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
