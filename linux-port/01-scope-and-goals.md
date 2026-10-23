# 01. Scope and goals

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

## Why

The owner wants to use Relay from a Linux computer the way people use Discord: a big window, keyboard, drag-and-drop
files, and voice calls with one person or a whole group. The phone app stays the primary device and the disguise
(Calculator launcher) stays a phone-only feature.

## Goals

1. **Same system, new client.** The desktop talks to the existing Cloudflare mailbox (`server/cloudflare`), speaks
   the existing NIP-17 + inner Tink envelope, the existing body formats (`TYPE:MEDIA`, replies, groups v2, typing,
   deletions, receipts) and the existing `/v1/media` encrypted blob store. No second server, no public relays.
2. **Local-only history.** Each device keeps its own encrypted database. The server keeps nothing after every
   active device of the recipient has acked (messages) or confirmed (media), or after 8 days.
3. **Messaging parity on desktop:** direct chats, groups (v2 direct delivery), replies, deletions, typing,
   read receipts, photos, videos, profile names/photos, notifications.
4. **Files (top priority from the owner):** any file type, up to 100 MiB, on **both** phone and desktop, with
   progress, resumable upload/download per part, and explicit save/open.
5. **1:1 voice calls** phone↔phone, phone↔desktop, desktop↔desktop, end-to-end encrypted.
6. **Group voice calls** in any Relay group, initial product cap of 17 participants, subject to real load testing,
   Discord-like: join/leave freely while the call is up, mute, deafen, speaking indicator.
7. **macOS too, unsigned.** The same desktop app is also packaged as an unsigned `.dmg` for Apple Silicon Macs
   (LP-D17). Linux stays the primary, fully tested target.
8. **Stay inside the free Cloudflare plans** at the current scale (≤100 members, roughly 10 active), with measured
   headroom (see 02 §Budget).

## Non-goals (this plan)

- Video calls and screen sharing (owner excluded them). The design does not block adding them later.
- A desktop that works **without** a phone. The desktop is always linked to a phone identity (LP-D03).
- Windows builds. Compose Desktop could produce them, but they are not planned, tested or packaged.
- Signed or notarized macOS builds and the Mac App Store. macOS ships as an **unsigned `.dmg`** (Apple Silicon),
  built on a GitHub Actions macOS runner (LP-D17, WP18). Users allow the first launch once in Privacy & Security.
- History backfill from phone to desktop. A newly linked desktop starts empty (LP-D06); contacts and groups are
  copied, messages are not.
- Calculator disguise, PIN wipe, 10-minute session on desktop. Desktop has its own lock (LP-D05).
- Push notifications to a closed desktop. The desktop receives while running (tray), and catches up from the
  mailbox when started.
- Public Nostr relays, Blossom servers or any third-party message transport.
- Changing the Android app's existing behaviour beyond what files, linked devices and calls require.

## Success criteria (whole plan)

- A message sent from any contact's unmodified 1.15 phone appears on both the owner's phone and linked desktop, and
  the server row is deleted only after both acked (server test + two-client acceptance run).
- A 60 MiB file goes desktop→phone and phone→desktop, hash-verified, on a real phone.
- A 1:1 call connects phone↔desktop on two different networks, one behind a NAT that forces TURN, with audio both
  ways for 10 minutes.
- A 5-person group call (≥1 desktop, ≥2 phones) holds for 15 minutes with join/leave mid-call.
- The Worker's rows-written per day under a one-week pilot stays under 60% of the 100,000 free limit.
