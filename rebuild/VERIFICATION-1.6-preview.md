# Calculator 1.6 preview verification — 2026-10-17

Changes: no startup connection-service notification while the activity is visible;
hold-to-reply replaces the visible Reply buttons in individual and group chats;
app-rendered direct notification content keeps the avatar rounded-square and uses
the latest supplied purple badge.

## Host verification

- Preview application and Android test APKs compile: version 1.6-preview, code 7.
- All 257 JVM tests pass, with no failures, errors or skips. These cover existing
  session, messaging, group, reply-codec and typing policies; they do not execute
  Android touch/lifecycle behavior. Three new preview-history tests check ordering,
  the five-line bound, timestamp preservation on profile refresh and isolation.
- Lint passes with zero errors, 18 warnings and 7 information items. The APK signature and application/launcher identity
  are checked before packaging. Package com.relay.app and the existing debug signing
  certificate are retained; Android application identity is Viber and its supplied
  purple phone image, while MainActivity stays Calculator with the calculator icon.
- Host SQLite group migration/rollback/constraint checks still pass.
- No Android device is connected and no emulator profiles were recreated. Android
  instrumentation tests are compiled, not run. There is no claim of bug-free behavior.

## Implementation checks and limits

MainActivity stops ConnectionService when starting/resuming. MessagingRuntime remains
process-wide, so stopping this foreground service does not stop the transport itself.
When the activity stops being visible (except rotation), the optional background
service starts. Permission dialogs that only pause the activity do not trigger it.
The service guards against queued starts and status notifications after UI return,
and removes its foreground notification on destruction. Background delivery remains
opt-in/out through the existing setting, which is on by default.

Android still requires a foreground-service notification for sustained background
work. This revision suppresses the notice while opening/using the app; it does not
claim to hide the operating system's background-service disclosure. See the official
[foreground-service overview](https://developer.android.com/develop/background-work/services/fgs)
and [visible-to-background start exemption](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

Each text/photo/video/location bubble uses combined tap/long-click handling. Holding
sets the quoted reply target, including holding an existing quote. Short taps retain
photo viewing, video playback and opening locations in Maps. Media can be held to
reply even while its decrypted display file is not available. Reply is exposed as a
labeled accessibility long-click action; Cancel reply clears the selected quote.
No notification quick-reply action is added.

## Notification photo and badge

Direct-message notifications now use DecoratedCustomViewStyle and a custom content
ImageView to avoid the platform conversation-avatar circle. The bitmap clips the
photo to rounded corners (radius 40 on a 192-pixel canvas). The new supplied badge
is stored unchanged as notification_sender_badge.png and composited at the bottom
right with rounded-square clipping, preserving its proportions. The application
icon still uses notification_avatar_badge.png; the Calculator launcher is unchanged.
Android retains its outer notification header; spacing and native conversation
classification/priority can differ. See [Android custom notification guidance](https://developer.android.com/develop/ui/views/notifications/custom-notification).

Preview text/timestamps use namespaced notification extras, bounded to five entries.
Visible older MessagingStyle histories are read for migration. Late profile refresh
updates the sender/avatar without appending or retimestamping messages. Native group
previews are unchanged. Android test assertions now inspect the custom content's
actual ImageView and sender/text rather than expecting MessagingStyle metadata.
Those tests compile but have not been run in this revision.

## Phone checks still pending

- Fresh launch: no separate Connected/Connecting service notice. Receive a normal
  message notification and confirm it is unaffected.
- Home/lock screen: background delivery continues with Android's required service
  notice; returning removes that notice. Rapid Home/return, rotation, permission
  dialogs, gallery return, process restart and disabling background delivery need
  runtime verification, including Android 12+ foreground-service start rules.
- On both phones, receive a fresh direct message after dismissing old previews.
  Check rounded-square photos, exact new badge, collapsed/expanded/heads-up content,
  light/dark modes, large fonts and TalkBack. Check history, profile refresh and
  dismiss-only tapping. Android runtime/visual checks have not been performed.
- Hold text/photo/video/location and quoted messages in individual/group chats;
  confirm the right quote is selected and can be cancelled/sent. Short media and
  location taps must still work. Check TalkBack's labeled Reply action.

Broader device/network checks in archived reports are historical. The 1.5 shared
conversation flows also retain their documented phone-test limitations; see
[VERIFICATION-1.5-preview.md](VERIFICATION-1.5-preview.md). This is a debug-signed test
preview with no independent security audit.
