# UI plan: social-app layout with a bottom navigation bar

Written 2026-09-21. Status: **approved, being built** on branch `feat/rebuild`.

## Where the app was
- One flat `NavHost` starting on the Map; every other screen a separate full-screen route.
- `MapScreen` had floating buttons to Settings, Pin History and Contacts.
- No conversation list: `ContactsScreen` (contacts + groups, tap to chat) was the closest thing.
- No unread tracking for incoming messages (`read_at` exists only for messages we sent).

## Target
Bottom bar with three tabs, left to right: **Map**, **Messages**, **Account**.

| Tab | Content |
|---|---|
| Map | Existing map; Settings/Contacts buttons removed; Pin History kept as a small shortcut. |
| Messages | New conversation list sorted by latest activity: avatar, name, last-message preview, time, unread badge, delivery tick. Groups in the same list. Top actions: new chat ("+" opens QR pairing) and new group. Unverified senders in a "Requests" section at the top. |
| Account | Profile card (name, Relay ID fingerprint, QR code entry), connection status with Reconnect, the settings sections, and a link to contact management. |

## Decisions (the user had not answered the four questions; these are my recommendations, used as defaults)
1. Adding a contact: "+" on the Messages tab opens QR pairing; a QR entry is also on the Account tab.
2. The app starts on **Messages**.
3. Groups sit in the same list as chats.
4. Unread badges: **yes** (needs DB v13).

## Structure
- Single `NavHost`; the three tab routes (`map`, `messages`, `account`) are top-level destinations navigated with
  `popUpTo(start){saveState}`, `launchSingleTop`, `restoreState`, so each tab keeps its own back stack.
- Detail routes (chat, group chat, QR, contacts, pin history) hide the bar.
- The shell `Scaffold` owns the bottom bar and consumes its insets so the nested screen scaffolds do not double-pad.
- Deep links (notification to chat, key-change alert to Contacts) keep working through the same graph.
- Reuse the existing theme, IBM Plex, flat square style; accent-coloured selected icon; badge on Messages.

## Data
- `ConversationRepository`: last message per contact and group in one query (LEFT JOIN on a max-timestamp subquery).
- DB v13: `messages.unread` and `group_messages.unread` (received rows start unread; opening a chat clears them).
  A per-row flag rather than a `last_read_at` timestamp, because sender timestamps can be older than the read time when
  messages arrive late after an offline period.
- Live refresh via the existing `NEW_MESSAGE` / `NEW_GROUP_MESSAGE` broadcasts.
- Pure, unit-tested helpers: preview text, list-time formatting, sort order, request classification.

## Build order (two commits per step)
1. Shell and navigation: bottom bar, tab routes, bar visibility, insets, start destination, placeholders.
2. Messages tab: unread schema, conversation query, list screen, badge, mark-read on open, requests section.
3. Account tab: profile card, settings embedded, connection status, contact-management link.
4. Map tab cleanup: remove the old floating buttons, keep the history shortcut, keep camera state across tabs.
5. Polish and device check: fix what the phone shows; update docs.

## Risks
- Unread migration touches the database: test the upgrade on the phone (an existing v12 install).
- The map is heavy to recreate: keep its camera (centre/zoom) in the ViewModel so tab switches restore it.
- The Settings screen is long: embed its content under the profile card instead of rewriting it.
