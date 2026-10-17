# Calculator 1.3 preview verification — 2026-10-17

Branch: feat/rebuild. Previous checks and limitations are preserved in
[VERIFICATION-1.2-preview.md](VERIFICATION-1.2-preview.md).

## Changes and checks

- Notification avatars now use a 1:1 rounded square instead of a circle. The supplied
  flat purple phone image replaces the previous badge. Its proportions are preserved.
- Relative to 1.2's actual foreground bounds (237/354 wide and 249/349 high),
  the new status-bar symbol occupies 166/174.1 wide and 174/174.1 high. That increases
  its width by approximately 42.4% and height by 40.1%, centered without clipping.
  Android retains control of the physical icon slot, tint and system badge background.
- Tapping or swiping dismisses only the corresponding preview. A private, unexported
  receiver is reached by an immutable, per-contact broadcast PendingIntent. No activity
  is launched; message contents and unread state are not changed. There are no reply actions.
- Preview and Android-test APK builds succeeded. Version 1.3-preview, code 4; minimum
  Android API 26, target 36. APK signature verification passed. In-place updates on both
  Android emulator installations succeeded, retaining existing accounts and chats.
- Five targeted Android tests passed. A real notification-shade tap removes its preview
  without resuming Calculator. Independent-contact dismissal leaves another preview
  present. History separation, late profile refresh, avatar loading/encrypted storage,
  stale photo removal protection and replacement-badge rendering also pass.
- The actual Android notification-shade screenshot was inspected and shows the rounded
  square avatar and new phone design. The generated 192x192 avatar's near-corner pixel
  (20,20) is opaque while the outer corner (0,0) is transparent, confirming rounded-square
  geometry rather than the previous circle.
- Lint: zero errors, 16 warnings, seven informational findings. The broadcast content
  intent intentionally triggers LaunchActivityFromNotification because the requested
  tap behavior dismisses a preview instead of launching an activity. The traced vector
  remains a VectorPath performance warning; other findings predate this change.

The 237 JVM and ten broader Android checks from 1.2 are historical; the JVM suite,
media/network tests and fresh-install session smoke test were not repeated for this
notification-only change. Physical-phone/OEM rendering still needs a manual check.
This is a debug-signed test preview, not an independent security audit or a claim of
no remaining bugs. Public preview and launcher-shortcut privacy limits remain as
recorded in DISTRIBUTION.md and the previous verification reports.
