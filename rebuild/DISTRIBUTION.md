Calculator 1.9 preview — installation and release notes
======================================================

Install 00-Calculator-preview.apk first. Android 8/API 26 or newer is required.
Allow installation from the app used to open the APK. The app is installed as Viber;
its Calculator launcher and existing private-entry code behavior are unchanged.
This is a debug-signed preview, not a production/security-audited release. An in-place
update requires the same signing certificate; do not uninstall to work around a
signature mismatch without backing up anything you need (uninstall erases app data).

Upgrade all peers and group creators to 1.9 for group profile-name forwarding and the
larger profile photos. Reselect your original profile photo to gain quality; existing
128 px compressed copies cannot recover discarded detail. No public avatar URL is used.

The folder contains the APK first, these instructions, measured verification/remaining
phone checks, source, and SHA-256 checksums. On Linux run sha256sum -c 04-SHA256SUMS.txt.
Source excludes private owner state, signing keys, local SDK paths and credentials.
A fresh source checkout creates a different recovery owner unless you securely reuse
this computer's private owner configuration. Never publish that private configuration.

Read 02-TEST-RESULTS.txt before testing. No connected phone was available; timing,
scrolling and notification behavior still require device acceptance tests. Issues
#19/#20/#21/#26 intentionally remain open. Use disposable test data.

Account → Connection → Hide connection notification opens Android's channel settings.
Turn off only Relay connection and leave Messages on. Android controls visibility;
the app still uses the required foreground service for prompt background delivery.

Account → Security → Emergency: keep calculator only requires your four-digit code.
It attempts remote history deletion for up to 15 seconds, wipes this phone's messaging
data/keys, disables messaging, then closes. Reopen to use the calculator. The compiled
APK code remains; reinstalling/clearing data can reset this local mode. Wrong confirmation
codes count toward lockout. Remote deletion requires compatible recipient apps, reachable
relays and membership/contact authorization. It cannot guarantee erasure of offline or
old devices, saved/forwarded/quoted copies, screenshots or retained relay ciphertext.
Undelivered requests are lost when local data is wiped. Test only on disposable app data.

00-Calculator-preview.apk is the Android installer. The numbered names put it first
when sorting this folder by name. Android 8 or newer is required. Allow installation
from the app you use to open this APK if Android asks. This is a debug-signed test
build, not an audited production release; keep it on test devices.

After updating, dismiss the previous preview and receive a new message to check the
Viber source header. Install over the current app to retain its data and pairings.

First launch: enter four digits and press + to choose your private entry code.
Later, press C, enter those four digits and press + to open messaging. An unlock
lasts 10 minutes from code entry, including time spent outside the app. Returning
within that window resumes messaging. Expiry closes messaging and any open media
viewer; enter the code again. Rotation retains the deadline, but restarting the
app process ends the session. Use ordinary calculations normally.

Pairing: set a display name under Account if needed, then choose Show my code.
The invitation is valid for at most 30 minutes and one accepted request. Add the
other person with Add person / Scan; both people must approve. The code rotates
after expiry. Closing the app process also invalidates an unused invitation.
Existing contacts continue working after their invitation expires.

Photos: open a verified contact's conversation, attach a photo from the system
picker, then send. If the session expires, re-enter the code first. Tap a photo
to open/zoom it or a video to play it inside the app. Photos keep their original
aspect ratio and camera orientation. Only the selected photo
is shared with this app; full-gallery permission is not needed. Photos are
compressed and encrypted before uploading. Public storage services can see the
connection address, ciphertext size and timing. They cannot read the photo from
those encrypted bytes. Optional Orbot routing requires a separate working setup.

Both devices should install 1.2-preview or newer for the name-sync fix. Existing paired contacts
are retained when updating. Profile names and photos are refreshed on reconnect and
on incoming messages; late profile data updates an already-visible notification.
Locally chosen contact aliases remain local. Android controls the exact notification
layout and app attribution; a supported conversation template shows the sender avatar.
Conversation previews also publish a temporary Android conversation shortcut, so your
launcher may show the sender name/photo when you long-press Calculator. Opening the
chat or deleting its contact removes that shortcut. Tap a message preview to dismiss it without opening Calculator. This does not delete or mark the chat message as read.

The notification avatar uses a rounded square. The status-bar phone symbol is 40%
larger within Android's fixed icon slot; Android controls its final mask and colors.

Profile photos: open Account, then Profile photo, then Choose photo and share.
Your photo is sent privately to mutually approved contacts who use the updated
app. It is reduced to at most 128 px / 6 KiB and carried inside encrypted messages,
not hosted as a public profile. Removing it sends a newer removal update. Contacts
can save anything you share. Notifications show their name and received photo,
with a generated-avatar fallback and the supplied flat purple phone badge. Android's
source attribution now says Viber. Android settings and the installer also show Viber; the home-screen launcher remains Calculator with the calculator icon.

Control Panel recovery needs no separately hosted web server. It receives encrypted
access reports and sends signed unblock commands through the public relay network.
Only your existing Control Panel holds the private owner key. Phones check its
public key, their own recovery ID and the lockout sequence before clearing failures.
They still need their original Calculator code after unblocking. The panel and
phone need internet; offline delivery depends on relay availability and retention.

This APK contains only the PUBLIC owner key. Private owner state, phone identities,
local service tokens and signing keys are excluded from the package. Use the
existing Control Panel on the owner's computer to manage this APK. A fresh copy of
its source creates a different owner identity and cannot unblock this APK's users.
Back up the private state separately, never to public GitHub.

Optional device/app diagnostics require the phone user's explicit agreement and
can be stopped/deleted. The panel cannot observe phones' connection IP addresses
through encrypted relays. It receives no PINs, contacts or conversation contents.

03-Calculator-and-Control-Panel-source.zip contains the Android project, Control
Panel, its supplied icon and build/test/package scripts. Extract it, then use:

  python3 owner/install-desktop.py  # Control Panel desktop shortcut on Linux
  owner/run.sh                    # GUI plus encrypted relay connection
  rebuild/dev/build.sh            # test build with the owner public key
  rebuild/dev/build.sh -PrelayPreview=true assembleDebug  # token-free preview

Control Panel needs Python 3 with Tk on Linux (Fedora: python3-tkinter; Debian or
Ubuntu: python3-tk). The Android build needs JDK 17 and Android SDK platform 36.
See README.md for setup, consent, routing, recovery and remaining limitations.

02-TEST-RESULTS.txt states what was actually checked. 04-SHA256SUMS.txt lets you
verify the files. Local databases, phone identities, credentials, private logs,
signing keys and SDK/JDK installations are excluded from the source archive.

Groups and replies
------------------
People → New group opens a shared conversation with up to sixteen verified friends.
The creator mediates group delivery using encrypted messages and must reconnect for
members' replies to be forwarded. Names and messaging IDs are shared with members.
The creator can add/remove friends; a removed member cannot submit further posts.
Members aren't automatically added or marked verified on other members' phones.
Delete closes a creator-owned group; on other phones it deletes the local copy.
Existing local-only groups are retained for reading and do not send private direct
messages masquerading as group messages. Create a new shared group instead.
Group chats support text, photos, videos and quoted replies. Tap Members in the
header to manage verified friends (creator only). Failed downloads retry when reopening
the group or reconnecting. Group location requests are not supported. It trusts the creator to attribute and forward other members'
posts; it does not independently authenticate each original author to every member.

Touch and hold a text, photo, video or location message, choose Reply, type, and send.
The same menu offers Copy, Delete for me, and Delete for everyone on authored messages
with stable ids. Install 1.8 on all devices; older group rows allow local deletion only. Cancel reply clears the quote.
Typing indicators stop after a pause/send/exit and expire within eight seconds if
interrupted. They use the existing encrypted transport and are not stored in chat
history or the durable send queue; encrypted relay events can still be retained by
relays. Disable them under Account → Privacy and Tor → Typing indicators.

Phone checks still required: picker selection/cancel/camera return, session expiry
inside a picker, three-phone invitations/replies/removal/offline delivery, quoted
photo/video captions and typing indicator appearance/clear. No emulator profiles
were recreated. No claim of a bug-free or audited release is made.

Notification photo and badge
----------------------------
Direct previews use native MessagingStyle, with the sender supplied as a Person and
conversation shortcut. The custom photo/text row from 1.6 has been removed. The
registered app icon and roundIcon both use the supplied notification_sender_badge.png;
MainActivity explicitly keeps Calculator and the calculator launcher icon. The
monochrome status-bar icon is unchanged. Android owns the app-badge decoration and
may mask the sender photo differently on different phones. This build cannot promise
an identical rounded-square photo mask on all manufacturers.

History migrates from visible 1.6 custom previews into the native template and stays
bounded to five entries. Profile refresh preserves the message timestamps. Install
on both devices, dismiss the old previews, then receive a fresh message. Actual
Samsung/native badge positioning still needs phone verification.
