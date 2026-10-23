---
id: WP09
title: "Files on phone and desktop (`TYPE:FILE`, up to 100 MiB)"
phase: L2
lane: planned
depends_on: [WP03, WP04, WP06]
specs: ["04 §6", "05 §2", "LP-D07", "09 R10, R13"]
owned_files:
  - "core/src/main/kotlin/**/files/**"
  - "app/src/main/java/com/relay/app/media/** (file send/receive)"
  - "app/src/main/java/com/relay/app/ui/** (file bubbles, picker, save)"
  - "desktop/**"
  - "server/cloudflare/test/** (multi-part media tests)"
---

# WP09 — Files on phone and desktop (`TYPE:FILE`, up to 100 MiB)

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Send any file, phone↔desktop↔phone, streamed in ≤25 MiB encrypted parts, with progress, resume and safe opening.

## Steps

1. `:core FileBody` codec + validation, streaming part encryptor/decryptor, part planner.
2. Phone 1.16 behaviour: unknown-type renderer and **receive** files; 1.17 enables **send** (feature flag).
3. Phone UI: attach file (SAF), file bubble (icon, name, size, progress), save to Downloads, open via `content://` grant.
4. Desktop UI: drag & drop, file bubble, save-as, open with warning for executables.
5. Resume: per-part state in DB; retry failed parts; cancel.

## Acceptance criteria

- **WP09-AC1** `FileBody` rejects: traversal names, oversized name, >100 MiB, >4 parts, wrong part sizes, foreign URLBASE (unit tests).
- **WP09-AC2** 60 MiB file desktop→phone and phone→desktop, SHA-256 of saved file equals original (real phone run).
- **WP09-AC3** Peak heap during a 100 MiB transfer on the phone stays under 64 MiB (measured).
- **WP09-AC4** Interrupted download resumes from the next part; each part confirmed once per device.
- **WP09-AC5** 1.15 phone receiving `TYPE:FILE` (before update) does not crash; 1.16 shows file; recorded.
- **WP09-AC6** Rows written for one 60 MiB file to one recipient with two devices measured and recorded.

## Evidence

Write `linux-port/evidence/WP09.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
