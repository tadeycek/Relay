# Calculator 1.5 preview verification — 2026-10-17

This revision repairs explicit media-picker handoffs and adds shared text groups,
quoted replies and short-lived typing indicators. It remains a test preview.

## Checks

- Preview application APK and Android test APK compile; JVM tests and lint pass.
  Version 1.5-preview, code 6. Lint: zero errors, 18 warnings, 7 information items.
- All 254 JVM tests pass with zero failures, errors or skips. New tests cover:
  picker retention only inside the unchanged session deadline; verified-friend
  eligibility; creator-only roster updates; member/author checks; stale rosters,
  removal and delayed posts; malformed/oversized group controls; Unicode payload
  budgets; quoted text and media-caption encoding; reply previews; fresh typing
  signals, expiry, ordering, stop events and conversation isolation.
- A separate host SQLite check executes the group table definitions and additive
  v16 migration statements. Existing v15 group/message data is retained. Event
  uniqueness, rollback and foreign-key cleanup pass. This is a host schema check,
  not an Android SQLCipher runtime or multi-phone integration test. Reproduce with
  `python3 rebuild/dev/check-group-schema.py`.
- The final APK signature verifies against the existing debug signing certificate.
  Its package is com.relay.app; Android application label/icon remain Viber and the
  supplied purple phone picture. MainActivity remains Calculator with its calculator
  launcher icon. In-place installation preserves the package/signing identity.
- No Android devices are connected. Emulator/profile listings remain empty; no
  profiles were recreated. Android instrumentation tests were compiled, not run.

## Behavior and limits

An explicitly launched gallery, camera or profile picker retains the private
navigation tree while the original fixed ten-minute session remains active.
Picker cancellation/selection clears that handoff; expiry still removes private
screens. Process death never restores a session. Ordinary backgrounding keeps the
existing calculator gate behavior. Missing pickers show an error rather than throw.

People → New group invites up to sixteen verified active friends with accepted
keys. New groups have a shared random ID and creator-controlled versioned roster.
The creator stores/fans out replies with durable outbox entries in one transaction;
failed inserts roll back. Received group events are deduplicated. Individual direct
messages are no longer mirrored into groups. Existing local-only groups are kept
readable and must be recreated to become shared conversations.

The creator is a routing participant, not an external service: its phone must
reconnect to forward members' replies. Members authenticate the creator's forwarded
posts; there is no independent original-author signature for every recipient.
Members see shared names and messaging IDs without automatically becoming verified
contacts. Only the creator changes membership; removed members cannot submit.
Creator deletion closes the group; other members delete only their local copy.
A local deletion retains a hidden tombstone so later messages cannot restore it.
This group version supports text and quoted replies, not group media/location.

Reply buttons quote a bounded snapshot. Text, media captions and previews decode
that snapshot for display. Typing signals use authenticated encrypted NIP-17 sends,
without the persistent outbox, chat history or durable payload replay table. Signals
expire after eight seconds and stop on pause/send/exit; transport delivery is best
effort. Encrypted relay events may still be retained by relays. Users can disable
indicators under Account → Privacy and Tor → Typing indicators.

All participants need 1.5-preview or newer for these features. Existing contacts,
profile sharing, Calculator entry, Control Panel recovery, notification source
identity and tap-to-dismiss previews are preserved.

## Phone verification still required

- Select/cancel gallery photo or video and return to the same chat with the draft;
  test camera permission denial/capture and profile-photo selection too.
- Keep a picker open across session expiry; returning must require the code.
  Repeat with process death/rotation and the optional biometric app lock.
- On three updated phones: creator invites two verified friends; both receive the
  same group and can reply to everyone. Verify offline reconnect, replay/dedup,
  member addition/removal, local deletion and creator closure.
- Check quoted text/photo/video captions, notification dismissal and typing
  appearance/expiry/pause/disabled behavior on real phones and Samsung's UI.

Earlier device/network checks in archived verification reports are historical;
they were not rerun for this revision. Current lint warnings include the intentional
preview-dismiss broadcast PendingIntents as well as existing compatibility/resource
findings. This is debug-signed, has no independent security audit, and is not a claim
that the new Android interactions or all app features are free of bugs.
