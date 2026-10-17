Calculator 1.11 preview — installation notes
============================================

Install 00-Calculator-preview.apk on Android 8/API 26 or later. This is a debug-signed
preview from feat/rebuild, including main's direct group delivery (PR #32).
The app is registered as Viber with the Calculator launcher/private-entry flow.
Install over a matching-signature existing copy to preserve data. Do not uninstall to
bypass a certificate mismatch: uninstalling deletes local data. The required signing
certificate is 06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9.

IMPORTANT: messages and app-private attachments now delete locally after SEVEN DAYS,
including older history when this version first runs. Contacts, identities, local aliases,
group membership and permissions remain. Updated phones apply their own retention;
Android may delay background cleanup until the next run. This cannot erase external
saved files or ciphertext retained by relays. Use test data for this preview.

The composer clears its accepted text while preserving newer typing/replies/attachments.
The top-right DOT opens mute/location/group options. The screenshot banner is removed;
support information is in the menu. Notification channels migrate to disable launcher
counts while retaining current importance/sound/vibration settings and in-app unread.

People and chat headers show approximate Active now / Last active for verified friends.
Both peers need the updated feature. Privacy and Tor → Share active status controls your
sharing. Only resumed private screens renew encrypted leases; a missing update expires
after one minute. Unknown status is not proof that a person is offline.

Open a photo → Save photo to export its original bytes through a chosen destination.
You confirm that a notice naming you and that exact photo will be sent. Saved copies are
outside app control. Android 14+ reported chat screenshots identify visible photos when
possible. Secure fullscreen viewing stays protected. External copies and unsupported
capture methods cannot be detected; older Android has no screenshot callback.

Every group participant must use a protocol-v2 build (published 1.10 or this 1.11).
Bring the upgraded creator online to refresh old rosters with member keys/relay hints.
Until refresh, non-contact member traffic uses NIP-17 without the extra inner Tink layer.
The creator still controls membership and group pictures.

Open the app once after updating to clear a legacy running-in-background notice. The
foreground connection service remains retired. Genuine message alerts remain subject to
mute/settings/permission. Android can delay background checks far beyond their 15-minute
minimum; prompt closed-app delivery is not guaranteed. Reselect original personal photos
to improve old reduced copies.

Read 02-TEST-RESULTS.txt. No phone was connected: real keyboard/launcher behavior,
screenshot callbacks, system export, SQLCipher upgrade and multi-phone sync remain
unverified. This is not an audited or bug-free production release.

This folder includes the APK first, source, installation/verification notes, build
provenance and checksums. Run sha256sum -c 04-SHA256SUMS.txt here. Signing keys, local
owner configuration and build caches are excluded. A local build needs the documented
Android/JDK toolchain and its own signing key; do not assume it can replace this APK.
