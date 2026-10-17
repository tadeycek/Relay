Calculator 1.10 local preview — installation notes
================================================

This preview is prepared from feat/rebuild. It is not merged into main or published as a new release.
Install 00-Calculator-preview.apk on a test phone running Android 8/API 26 or later.
It is installed as Viber, with the Calculator launcher and existing private-entry code.
The preview is debug signed. Install over a build with the same signing certificate to
preserve data. Do not uninstall to bypass a signature mismatch without preserving data
that you need: uninstalling clears it.

The running-in-background connection notice is removed by retiring the continuous
foreground connection service. Open the app once after updating to clean the previous
notice/channel. Real incoming-message notifications remain enabled, subject to mute,
settings and Android permission. After Android stops the process, ordinary background
checks have a 15-minute minimum and can be delayed much longer. Open the app for prompt
delivery; this build cannot promise instant closed-app delivery without that service.

New controls: tap the dancing character or Skip animation; Import QR image / Download
my QR code; People → group → Edit → Group picture; Attach → Animated GIF / sticker;
hold a message for reactions or Remove my reaction. QR images keep the original
30-minute invitation expiry and still require mutual approval. GIFs are local files,
not a third-party search service. Only group creators can change group pictures.
Android 14+ chat screenshot notices are best effort; older Android versions explicitly
show that detection is unavailable. Not all capture methods can be detected.

Upgrade all participants/creators to 1.10 for these controls and events. Reselect the
original profile photo to improve old reduced copies. Existing paired identities,
local aliases, calculator session security and encrypted-message/media routes remain.

Read 02-TEST-RESULTS.txt. No phone was connected, so real UI, screenshot callbacks,
notification cleanup, restart recovery and multi-phone synchronization remain untested.
Use disposable data. This is not an audited or bug-free production release.

The folder includes the APK first, installation notes, verification results, complete
tracked source, build provenance and SHA-256 checksums. Run sha256sum -c 04-SHA256SUMS.txt.
Private owner configuration, signing keys, local SDK paths and build caches are excluded.
A fresh source build creates a different recovery owner unless you securely reuse the
existing private owner state. Never publish that state.

First launch: enter four digits and + to choose the private entry code. Later, press C,
enter that code and +. Unlock lasts ten minutes from entry, including background time;
rotation retains the deadline and process death ends the session. App lock still applies.
Pairing requires each person to approve. Codes expire after thirty minutes or first use.

Profile photos are limited to 256 px / 8 KiB and remain encrypted. Old reduced copies
cannot regain detail: select the original photo again. Saving a QR image intentionally
shares a public invitation, not a private identity/recovery key; its original expiry and
one-use issuer checks remain in force.

Control Panel: run owner/run.sh from source. Its private state is excluded from the
package; keep the original computer's state safe if you need to manage this APK.
Emergency calculator-only reset is destructive. Test on disposable data and read the
full README before using it; remote erasure of saved copies/offline devices is not assured.
