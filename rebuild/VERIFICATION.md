# Calculator 1.7 preview verification — 2026-10-17

The supplied screenshot shows a separate leading app icon beside the custom sender
photo. This revision removes the custom content layout and returns to native
MessagingStyle, matching the structure of the Exion conversation reference.

## Host checks

- Application/test APK compilation, all 257 JVM tests and lint pass. Tests have no
  failures, errors or skips. JVM checks cover preview history and the existing
  conversation/session policies, not Android's rendered notification shade.
- Version 1.7-preview, code 8. Package com.relay.app and the existing debug signing
  certificate are unchanged. APK signature and manifest identity are verified.
- The compiled application label is Viber. Application icon and roundIcon point to
  notification_sender_badge.png, preserving the supplied 66 x 64 image. MainActivity
  retains the Calculator name and calculator launcher icon. The monochrome small
  notification/status-bar vector is unchanged.
- Host SQLite group-schema regression checks pass; group/database schema is unchanged.
- No phone is connected; no emulator profiles were recreated. Android tests compile
  but have not been run. Exact phone rendering is not verified.

## Notification changes

The builder uses native MessagingStyle with the sender Person, avatar, large icon
and conversation shortcut. It supplies no custom content, expanded or heads-up views.
Android receives the updated application icon for its native app badge. Sender
bitmaps retain the rounded source crop but contain no painted logo, avoiding a
second overlay. The custom layout and its rendering-only test helper are removed.

Existing 1.6 namespaced preview history is read and rendered through native messages.
Older MessagingStyle histories are also supported. Both histories are bounded to
five entries; late profile refresh preserves their original text/timestamps. Tap
still only dismisses the preview, without launching the app, reading or deleting
messages. Hold-to-reply and startup/background service behavior from 1.6 are retained.

Android controls the final photo mask, app-badge position, header and spacing. This
revision does not force the same rounded-square shape on every phone. The native
MessagingStyle/shortcut combination restores native conversation eligibility, subject
to Android/user settings. See [Android MessagingStyle](https://developer.android.com/reference/android/app/Notification.MessagingStyle)
and [custom-notification decoration](https://developer.android.com/about/versions/12/behavior-changes-12#custom-notifications).

## Phone checks still required

Install 1.7 on both phones, dismiss the old previews and receive fresh direct messages.
Compare the single sender-photo/app-badge arrangement against the Exion reference.
Check collapsed/expanded/heads-up previews, sender name/photo refresh, history,
notification dismissal, light/dark modes and large fonts. Confirm the registered
purple icon appears in the intended native badge position on the target Samsung UI.
Exact rendering and OEM masking remain unverified without a connected device.

The Android notification tests now assert native MessagingStyle metadata, sender
identity/avatar, bounded history and dismiss-only behavior, rather than expecting
custom ImageViews. Prior runtime checks and the 1.6 report are historical, not new
phone verification. This remains a debug-signed test preview without a security audit.
