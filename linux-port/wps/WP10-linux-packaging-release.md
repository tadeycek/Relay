---
id: WP10
title: "Linux packaging and desktop release"
phase: L2
lane: doing
depends_on: [WP06, WP07, WP08, WP09]
specs: ["LP-D15", "09 R11, R14", "rebuild/DISTRIBUTION.md"]
owned_files:
  - "desktop/build.gradle.kts (nativeDistributions)"
  - "desktop/packaging/linux/**"
  - "rebuild/dev/package-desktop.sh (new)"
  - "linux-port/evidence/WP10.md"
---

# WP10 — Linux packaging and desktop release

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Installable `.deb` and AppImage for x86_64 with checksums, provenance and licence notices.

## Steps

1. Compose `nativeDistributions` Deb target, jlink-trimmed runtime, desktop entry, icon, MIME/URL handlers none.
2. Strip non-target natives from the nostr jar at packaging; verify only `linux-x86-64` remains.
3. AppImage from the jpackage app image with `appimagetool` (pinned version + digest).
4. Licence table built from the shipped archive; `SHA256SUMS`; version literals (`packageVersion`) bumped together.

## Acceptance criteria

- **WP10-AC1** `.deb` installs and launches on clean Ubuntu 24.04 (VM) and the AppImage runs on one non-Debian distro.
- **WP10-AC2** Installer sizes recorded; ≤150 MB target met or deviation explained.
- **WP10-AC3** Licence table covers every jar/native in the archive (count matches the archive listing).
- **WP10-AC4** Release notes state what was not tested.

## Evidence

Write `linux-port/evidence/WP10.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.

<!-- 2026-10-24 UTC: WP10 moved to doing; implementation/evidence incomplete, release gates remain open. -->
