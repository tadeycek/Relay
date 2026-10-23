---
id: WP01
title: "Spike: JVM transport and crypto interop with a real phone"
phase: L0
lane: doing
depends_on: []
specs: ["02 §A–B", "04 §1–2"]
owned_files:
  - "linux-port/spikes/jvm-transport/** (throwaway, not built by Gradle root)"
  - "linux-port/evidence/WP01.md"
---

# WP01 — Spike: JVM transport and crypto interop with a real phone

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its corrections are mandatory
acceptance requirements. An unrun or failed release gate keeps this WP below `for_review`.

## Goal

Show that a plain JVM process on Linux can authenticate to the test mailbox and exchange messages with a 1.15 phone using the same libraries, before any code is extracted.

## Steps

1. Standalone Gradle project using `nostr-sdk-kmp-jvm:0.44.8`, `tink:1.24.0`, OkHttp.
2. Import a **test** identity (emulator phone's exported nsec + Tink keysets via a debug-only export, never a real user's keys).
3. NIP-98 auth against `relay-mailbox-test`, open `/v1/ws`, sync, decode a gift wrap from the emulator phone, open `TYPE:ENC` with JVM Tink, ack.
4. Send a text from the JVM back; confirm the phone displays it.
5. Record jar sizes, native lib load on Ubuntu 24.04, cold-start time.

## Acceptance criteria

- **WP01-AC1** A gift wrap sent by the 1.15 app (emulator) is unwrapped and its `TYPE:ENC` body decrypted on the JVM; plaintext matches.
- **WP01-AC2** A message sent from the JVM is shown by the 1.15 app as a normal incoming message from the test identity.
- **WP01-AC3** Tink keysets serialized on Android (cleartext JSON, debug export) load on JVM Tink and decrypt; reverse direction works.
- **WP01-AC4** Evidence lists exact library versions, native library path loaded, and what did not run (real phone, Tor).

## Progress

- 2026-10-23 UTC — Local Android companion/JVM crypto and real local Worker exchange verified. Original 1.15 UI
  and live test Worker acceptance remain open; see [evidence](../evidence/WP01.md).

## Evidence

Write `linux-port/evidence/WP01.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
