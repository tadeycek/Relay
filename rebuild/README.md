# Relay rebuild plan: SMS/MMS to internet messaging over Nostr

Status: **proposal, nothing built yet.** Written 2026-09-21 from a long design discussion plus web research.
Nothing in `app/` has been changed by this document.

## One-paragraph summary

Relay today is an SMS/MMS client whose end-to-end encryption rides on carrier texts. That design forces the app to
become the phone's default SMS app (otherwise the stock messenger also receives and shows the ciphertext, MMS receive
is unreliable, and Relay cannot store messages in the system provider). The owner has ruled out becoming the default
SMS app. The rebuild replaces the SMS/MMS transport with **internet messaging over Nostr relays using NIP-17 private
direct messages**. The relays act as anonymous, dumb mailboxes, so no server of our own is required, offline delivery
works, and the stock messenger is no longer involved. The existing QR pairing, crypto (Tink), encrypted database, map
UI and location features are kept. An optional **Tor** mode hides the user's IP address from relays.

## Files in this folder

| File | What it covers |
|---|---|
| [01-problem-and-goals.md](01-problem-and-goals.md) | Why SMS does not work for this app, requirements, non-goals |
| [02-research-findings.md](02-research-findings.md) | Everything learned, with sources and confidence labels |
| [03-options-compared.md](03-options-compared.md) | Every transport option considered and why Nostr won |
| [04-architecture.md](04-architecture.md) | Target design: identity, pairing, messages, relays, offline, wake-up, media, groups |
| [05-tor.md](05-tor.md) | Optional Tor mode: design, UX text, pros and cons, leak checklist |
| [06-codebase-impact.md](06-codebase-impact.md) | File-by-file: what stays, what changes, what is deleted |
| [07-phases.md](07-phases.md) | Build order with exit criteria and a test plan |
| [08-risks-and-open-questions.md](08-risks-and-open-questions.md) | What could go wrong, decisions still open, things to verify first |
| [09-dev-environment.md](09-dev-environment.md) | Toolchain versions and how to test on a physical phone |

## Decisions made so far

1. Relay will **not** be the default SMS app. (Owner decision.)
2. Plain SMS/MMS and data SMS are **out** as the core transport (see 03).
3. Transport becomes **Nostr, NIP-17 private DMs**, over public relays, with no server of our own.
4. **Tor is an optional setting, off by default**, with the pros and cons explained in the UI.
5. Existing Tink crypto is **kept as an inner layer** for now (revisit in 08).

## Decisions still open (details in 08)

- Keep Tink inner encryption on top of NIP-44, or drop it and rely on NIP-17 alone.
- Wake-up strategy for a closed app: foreground service vs. a small notification bridge (which is a server).
- Media host (Blossom server choice), or text-only first.
- Whether to keep an SMS "invite a friend" intent (needs no permissions).

## Corrections to earlier claims made in the design discussion

These are recorded so the plan does not inherit mistakes:

1. **Iroh does not use a DHT.** It dials by public key using relay servers and hole punching. Earlier discussion said
   it used a DHT; that was wrong.
2. **Push to a closed app is not free with Nostr.** Earlier discussion said to "add UnifiedPush or FCM". A Nostr relay
   cannot wake a phone by itself. Something has to watch relays and send the push, and that something is a server
   (see 04, section "Getting the message to a closed app"). Without it the app must hold its own connection open.
3. `plan.md` in the repo root says the crypto would use `libsignal-client` with a Double Ratchet. The code actually
   uses **Tink** (HPKE encryption, Ed25519 signatures, per-message keys, periodic key rotation) and has **no ratchet**.
   Forward secrecy is therefore limited to key rotation.

## Suggested first step

Phase 0 in [07-phases.md](07-phases.md): a throwaway spike with two phones exchanging one NIP-17 message through
public relays, including the "recipient offline for a day" test. It answers the two biggest unknowns (relay retention
and Android background behaviour) before any real code is rewritten.

## Build status (added at the end of the rebuild)

The plan was implemented on branch `feat/rebuild` in 18 commits (two per phase, phases 0 to 8). It compiles, lints
without errors and has 92 passing JVM unit tests; it has **not** been run on a device. Read
[PROGRESS.md](PROGRESS.md) for per-phase results, deviations from this plan, what is unverified, and the ordered next
steps. Docs 01 to 09 describe the plan as written and were not rewritten after the build, so where they differ,
PROGRESS.md wins.
