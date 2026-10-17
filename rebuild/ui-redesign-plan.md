# UI redesign plan: from "dark terminal" to a messenger with its own identity

Written 2026-09-21. Status: **plan only, nothing built.** Follows the `frontend-design` skill: subject first, a token
system, a self-review of the first draft against the brief, then a build order.

## 1. What is wrong today (evidence, not taste)

From screenshots taken on the phone and a read of the UI code:

| # | Problem | Evidence |
|---|---|---|
| 1 | The look is a generic default: pure black, white accent, zero corner radius, ALL-CAPS monospace labels for tabs and section headers. It could be any developer tool; nothing says "private messenger between friends". | Bottom bar "PEOPLE / MESSAGES / ACCOUNT", section headers "LOCATION REQUESTS", "CONNECTION", "PRIVACY" |
| 2 | **The Theme setting (Dark / Light / System) does nothing.** It is saved but never read; the app is dark-only. | `RelayTheme` takes no theme argument; `prefs.theme` is never used |
| 3 | Account is one very long wall of identical full-width rows. Rare, technical settings (key rotation, Do-Not-Disturb hours, Tor) sit at the same level as the profile. | `SettingsScreen` 563 lines, ~25 rows on one scroll |
| 4 | Explanations are grey walls of small text (the Tor guide and pros/cons). | Tor screenshot |
| 5 | Dropdowns were clipping ("System defaul") and labels wrapped one word per line. Symptoms of hand-tuned row layouts. | Earlier Account screenshots |
| 6 | **Swipe-to-delete removes a contact and all their messages with no confirmation** (database cascade). | `SwipeableContactRow` calls delete straight away |
| 7 | Trust is changed by tapping an unlabelled badge that cycles Ask → Blocked → Trusted. Blocking is one accidental tap. | `onTrustLevelClick` |
| 8 | Avatars are a grey square with an initial, and a "?" when you have no name. Trust (met in person or not) shows only as small grey text. | People and Account screens |
| 9 | Chat bubbles are flat squares with no grouping, no day separators, no unread divider; the location card and media bubbles use a different visual language. | `MessageBubble.kt` |
| 10 | No motion anywhere except scrolling, including the one moment that matters most (pairing succeeded). | grep for animation APIs: none |

Code quality, measured:

- `fontFamily = ...` written inline **119** times and `fontSize = ...` **76** times instead of using theme text styles.
- The same `OutlinedTextFieldDefaults.colors(...)` block copy-pasted in **6** files.
- Hard-coded reds (`#FF6E5D`, `#CC2200`) and `Color.White/Black` overlays outside the theme.
- **198** raw `dp` literals and `RectangleShape` written **33** times.
- Oversized files: `ContactsScreen` 718 lines, `SettingsScreen` 563, `QrExchangeScreen` 511, `MessageBubble` 429, `ChatScreen` 427.
- The monochrome theme is defined as loose top-level `val`s (`Accent`, `Surface1`...) so a light theme is impossible without touching every file.

## 2. Design direction

**Subject.** Relay is a private messenger for people who know each other. Its defining ritual is *meeting in person and
swapping a code*: that is what makes a contact trustworthy. Everything else (relays, encryption, Tor) should stay
quiet in service of that.

**Audience and job.** A friend group. The job of the UI: make it obvious who is a verified friend, get messages
read and sent with no friction, and hide the machinery until someone asks for it.

**Concept: "the mark of someone you've met."** Every contact gets a small square **glyph** generated from their Relay
key, drawn as quarter-circle tiles in a 4×4 grid (a Truchet-style pattern). It replaces the initial-on-grey avatar
everywhere. The glyph carries the trust state:

- **Verified in person:** strokes in verdigris.
- **Unverified (knows your key, never scanned):** strokes in dim grey inside a dashed frame.
- **You:** strokes in bone white.
- **Group:** a 2×2 of up to four member glyphs.

Because it is derived from the key, the same person looks the same on every phone, and a changed key changes the
glyph, which is a visible, memorable version of the existing "key changed" warning.

**Where boldness is spent (one place):** the glyphs, plus a single motion moment when pairing succeeds: the two glyphs
slide together and lock. Everything else is deliberately calm.

## 3. Tokens

### Colour (keeps the black-and-white base; adds one meaningful hue)

Dark (default):

| Name | Hex | Role |
|---|---|---|
| Ink | `#000000` | Background (OLED black is kept) |
| Graphite | `#0E0F10` / `#17191B` | Surface / raised surface |
| Line | `#26292C` | Dividers and outlines |
| Bone | `#ECEAE4` | Primary text and sent bubbles (a warm white, not `#FFFFFF`) |
| Ash | `#8B9094` | Secondary text |
| Verdigris | `#6FB39D` | Verified, connected, success. The only accent hue, used only when it *means* something |
| Ember | `#E5604D` | Danger and failure (replaces the two ad-hoc reds) |

Light (so the Theme setting finally works): background `#F6F7F8`, surfaces `#FFFFFF` / `#ECEEF0`, text `#15181A`,
secondary `#5C6266`, verdigris `#2F7F6B`, ember `#C0392B`. Contrast is checked in the build (see §8).

Colours become **semantic roles** in the Material colour scheme plus a small `RelayColors` extension
(`verified`, `danger`, `sent`, `received`, `line`), not loose constants.

### Typography

Two families, clearly different:

- **Bricolage Grotesque** (OFL, bundled) for screen titles and names: `Display 30/34 semibold`, `Title 20/26 semibold`.
  A characterful grotesque with tight, slightly quirky forms.
- **IBM Plex Sans** (already bundled) for everything you read: `Body 16/24` (messages), `Secondary 14/20`,
  `Caption 12/16 medium`.
- **IBM Plex Mono only for data:** Relay IDs and coordinates. Not for timestamps or labels.

Sentence case everywhere. No ALL-CAPS labels. Timestamps use tabular numerals in Plex Sans. All of it is defined once as
Material `Typography` roles, so no screen sets `fontFamily` or `fontSize` inline.

### Space, shape, motion

- Spacing scale: 4, 8, 12, 16, 24, 32 (`RelaySpacing`), no other literals.
- Shape hierarchy instead of one radius: glyphs stay **square** (they are the tile canvas); message bubbles **14 dp** with a
  **4 dp** corner on the sender's side; buttons and fields **10 dp**; bottom sheets and dialogs **20 dp**.
- No shadows and no gradients; depth comes from the graphite steps and hairlines.
- Motion only where it answers an action: tab indicator, expanding a section, bubble state change, sending, and the one
  pairing moment. All respect the system "remove animations" setting.

## 4. Layout concepts

Left-aligned throughout. Rows are flat with inset hairline dividers (no boxed cards). Wireframes:

```
Messages                         People                           Account
┌──────────────────────────┐    ┌──────────────────────────┐     ┌──────────────────────────┐
│ Messages            [ + ]│    │ People              [ + ]│     │ Account                  │
│                          │    │ Verified friends         │     │ ┌────┐  Ana Novak          │
│ ┌──┐ Marko           14:05│    │ ┌──┐ Marko        ✓      │     │ │glyph│ Relay ID 9a66…0cbe │
│ │gl│ You: see you there ②│    │ ├──┤ Ana          ✓      │     │ └────┘ ● Connected        │
│ ├──┤ ─────────────────────│    │ Not verified yet         │     │ [ My QR code ]  [ Scan ]  │
│ │gl│ Ana          Yesterday│    │ ┌┄┄┐ Ivan  (dashed)     │     │                           │
│ │  │ Shared a location    │    │ Groups                   │     │ Notifications          >  │
│ ├──┤                      │    │ ┌┌┐┐ Weekend trip       │     │ Location requests      >  │
│ │gl│ Weekend trip     Mon │    │                          │     │ Privacy and Tor        >  │
│ Requests (1)              │    └──────────────────────────┘     │ Security               >  │
│ ┌┄┄┐ Ivan wants to chat   │                                     │ Connection             >  │
│ [ People ][●Messages][Acct]│                                    │ Appearance             >  │
└──────────────────────────┘                                      └──────────────────────────┘
```

Chat: glyph in the top bar with a verified tick, day separators, consecutive messages from one person grouped with one
timestamp, an "unread" divider where the new ones start, a composer with attach / text / send and a quiet "encrypted"
line only when it is useful.

Contact detail (new bottom sheet, replaces the cycling badge): large glyph, name, verified state with what it means,
Relay ID fingerprint, trust choice as a three-way segmented control (Ask, Trusted, Blocked), "Key changed" review, and
a destructive "Delete contact" with a confirmation that says how many messages go with it.

Pairing (the hero): "Meet in person" screen. Your code is large with your glyph above it; a switch to scan. On success the
scanned person's glyph animates in beside yours and locks, with the line "You and Ana are verified." One motion, once.

Account sub-pages hold the rare, technical things: Privacy and Tor (with the four-step guide as a real numbered sequence,
because it is one), Security (key rotation), Connection (status, background, relays), Location requests (including
Do-Not-Disturb), Appearance (Dark / Light / System as a segmented control).

## 5. Reusable components (each replaces copy-pasted code)

`RelayTheme` (semantic colours, typography, shapes, spacing), `Glyph` and `GroupGlyph`, `ListRow` (leading, headline,
supporting, trailing), `SectionHeader`, `SegmentedControl` (replaces the clipping dropdowns for 2 to 3 options),
`RelayTextField`, `PrimaryButton` / `SecondaryButton` / `DangerButton`, `Chip` (status: verified, unverified, queued),
`EmptyState`, `ConfirmDialog`, `BottomSheet`, `SettingToggleRow`, `ChatBubble` and `LocationCard`, `DaySeparator`.

## 6. Copy rules

Sentence case, plain verbs, the same word for the same action everywhere. Empty screens invite an action ("Nobody here
yet. Meet a friend and scan each other's codes."), errors say what happened and what to do ("Can't reach Tor. Open Orbot
and tap Start."). Nothing apologises and nothing is clever.

## 7. Code cleanup plan

1. One theme file: tokens above, light and dark schemes, honouring the Theme setting (fixes problem 2).
2. Zero inline `fontFamily` / `fontSize` / hard-coded colours outside the theme (enforced with a simple grep check in CI or a test).
3. Replace the 6 text-field colour blocks with `RelayTextField`; replace dropdowns with `SegmentedControl`.
4. Split the big files by responsibility: `ContactsScreen` into People screen + contact sheet + group dialogs; `SettingsScreen` into one file per sub-page;
   `QrExchangeScreen` into scanner + my-code + result; `MessageBubble` into text / media / location / request bubbles.
5. Remove dead code found on the way (unused `SliderRow`, leftover imports, `NavIconButton` if unused).
6. Keep ViewModels and data code untouched except where a screen needs a new field (e.g. glyph seed).

## 8. Build order (two commits per step, each verified on the phone with a screenshot)

1. **Foundation:** tokens, fonts, light and dark schemes, honour the Theme setting, shared text styles, `RelaySpacing`.
2. **Components:** `ListRow`, `SegmentedControl`, `RelayTextField`, buttons, `ConfirmDialog`, `EmptyState`, sheet; tests for pure parts.
3. **Glyph:** deterministic generator (unit-tested: same key gives same glyph, different keys differ, all bits used), `Glyph` and `GroupGlyph`, trust states.
4. **Messages and bottom bar:** new rows, sentence-case tab labels, requests section.
5. **Chat:** bubbles with radius hierarchy, grouping, day separators, unread divider, location and media cards, composer.
6. **People and contact sheet:** grouped list, sheet with segmented trust control, delete confirmation (fixes 6 and 7).
7. **Pairing:** "Meet in person" screen and the one animation, reduced-motion aware.
8. **Account and sub-pages:** short Account, sub-pages, Tor guide as a stepped list with a status chip.
9. **Polish and audit:** contrast checks (WCAG AA for text on both themes), font scaling to 200%, small screens, TalkBack labels, empty and error states; update README and screenshots.

Verification: unit tests for the glyph generator, contrast maths and grouping/separator logic; on-device screenshots of every
screen in both themes; a scripted check that no screen sets fonts or colours inline.

## 9. Risks and open decisions

- **Fonts:** Bricolage Grotesque must be downloaded and bundled (OFL, about 100 KB per weight). Fallback: keep Plex for titles.
- **Light theme is new work.** If you would rather stay dark-only, the plan is to remove the Theme setting instead (smaller).
- **Glyph collisions:** 32 bits of pattern is plenty for a friend group; the Relay ID fingerprint stays visible for real verification. The glyph is a recognition aid, not a proof.
- **Accessibility:** glyph colour must never be the only signal; the tick and the text "Verified" always appear too.
- **Scope:** this touches every screen. Each step ships independently so it can stop after any of them.

## 10. What changed after reviewing my first draft

The first draft was reviewed against the brief and the generic defaults; three parts failed and were revised:

- **"Sealed letters / wax stamp" concept** read as decoration with no link to how the app works. Replaced by key-derived glyphs,
  which come straight from the product's real mechanism (keys and in-person verification).
- **Brass accent** sat too close to the warm-clay look that is common in generated designs and had no meaning here.
  Replaced by verdigris, used only for "verified / connected".
- **Space Grotesk headings** were a default choice. Replaced by Bricolage Grotesque paired with the Plex Sans already in the app.
- **One large radius on everything** was dropped for a hierarchy (square glyphs, 14 dp bubbles, 10 dp controls, 20 dp sheets).
- Also removed: ALL-CAPS labels, monospace for timestamps and labels, decorative gradients and shadows.
