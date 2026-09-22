# Ideas

A running list of ideas for Relay, each with a description of what it is, how it could work and what to watch for.

## NFC contact exchange (tap two phones together)

**Idea:** Exchange contacts by holding two phones together, like Apple's tap-to-share, as a third option
in "Meet in person" next to "Scan their code" and "Show my code". QR stays as the fallback.

**Why it is not built in:** Android Beam did this and was removed in Android 10. Nothing in the OS replaces
it, so the app has to build it.

**How it could work:**
- One phone acts as an NFC tag through host card emulation, and the other reads it with NFC reader mode.
- The payload is the same contact code the QR carries (name, Nostr key, keys, relay hints), a few hundred
  bytes, so it fits. `QrContactCode` already produces and parses it and can be reused.
- Two phones cannot both read at once, so one tap moves the code one way. Either two taps with a swap of
  roles ("hold phones together", then swap), or one tap with automatic role flipping, which is fiddlier to
  make reliable.
- If the emulated tag is a standard NDEF tag, other NFC readers can read it too, iPhones included. That is
  only a bonus, since Relay is Android-only.

**One tap is enough, same as QR:** the tag payload goes to the same `QrContactExchange.onScanned` that the
QR scan uses. When A taps B, A's phone saves B, trusts B's key, marks B as verified in person and queues A's
own key and name to B over the internet (durable outbox, so it arrives even if B is offline). B then sees A
as a contact who has not been verified (under "Requests") and can message straight away. Only the phone that
read the other gets the "verified in person" mark. If both phones read each other (a two-way tap), both
sides end up verified. So the two-tap or role-flipping design is only needed for mutual verification, not for
being able to message.

**Limits and risks:**
- NFC has to be switched on. Antenna position differs between phones, so the first tap can take some
  fumbling. Some phones and cases handle it poorly.
- Range is a few centimetres, which suits "verified in person". It is not cryptographic proof: relay
  attacks on NFC exist, and the QR flow has the same weakness. A successful tap would count like a QR scan
  for the "verified in person" mark.
- Keep the "Add this person?" confirmation so a stray tap cannot add someone silently.
- Needs a second NFC phone to test.

**Cost:** about one focused step: the NFC permission, an emulation service and a reader-mode screen inside
"Meet in person".

**Status:** built (commit "Pairing (3/4)"), untested between two real phones. One phone emulates a Type 4 tag while "Show my code" is open; the other reads it in reader mode on the scan tab and feeds it through the same path as a QR scan. The tag protocol is unit-tested by running the emulator against the reader. Possible extra: register the contact MIME type so a tap opens Relay from outside the app.

## Trust and privacy

- **Compare glyphs remotely.** Verify someone without meeting them. The "glyphs" are the key-derived 4x4
  arc-tile avatars the app already draws (`GlyphGenerator`). Today each glyph comes from one person's key, so
  the two phones show different pictures for the same chat. This idea needs a new shared glyph made from
  both keys together (sorted, so both phones compute the same one). Both people open it on a call and
  confirm the pictures match, like Signal's safety numbers; if a key was swapped in transit they would
  differ. If they match, mark the contact verified. Drawing and hashing code can be reused, so it is small,
  but not free.
- **Hide message content in notifications.** A setting so the lock screen shows only "New message", with no
  sender or text.
- **Block screenshots and the recent-apps preview.** A setting that hides Relay's content from screenshots
  and the app switcher.
- **Per-chat disappearing messages.** Location messages already expire; extend that to text and media.

## Messaging

- **Voice messages.** Sent through the existing encrypted media path.
- **Replies and reactions.** Quote a message, and a small emoji response.
- **Share into Relay.** Share text, a photo or a location from any other app straight into a chat.

## Ownership and resilience

- **Encrypted backup and moving to a new phone.** Losing the phone currently loses the identity key. An
  export protected by a passphrase would fix that. Biggest gap on this list.
- **Relay health screen.** Show which relays are reachable and let people add their own.
- **Live location for a set time.** Share for 15 minutes or an hour, updating in the chat.

**Suggested first picks:** remote glyph comparison, the encrypted backup, and notification and screenshot
privacy. All are small, and the backup closes the largest gap.

## Mutual in-person verification (built)

A scans B's code (QR or NFC) and taps yes. B is asked "A added you. Add them back?". Both are marked verified
only if both say yes within about 3 minutes; otherwise nothing is kept and a contact the pairing created is
removed again.

- B's code carries a one-time code (QR v4 `PAIR` field). A repeats it in the request, so B's phone knows the
  sender really saw its screen; a request with an unknown, used or expired code is ignored and leaves no
  trace. Codes are single-use and rotate after 10 minutes.
- A waits 45 s longer than B has to answer (the "grace"), so B's answer can still arrive over the network.
- If both scan each other at the same time, each request counts as the other's yes.
- Older codes (no `PAIR` field) keep the one-sided flow, so un-updated phones still work.
- Known limit: the person who answers last is verified immediately; the first person only learns when the
  confirmation arrives. If that message is lost, one side ends up verified and the other does not. It fails
  towards "not verified" and a rescan fixes it.
- Not done: a notification when the recipient's app is in the background (the question shows on whatever
  screen the app is on), and an explicit "No" message (a "No" just lets the first person's wait run out or be
  cancelled).
- Untested end to end between two phones.

## "Remove me" message on delete (not built)

Deleting a contact, or deleting the app, only ever clears your own local copy — there is no server that
knows about the relationship, and Android gives no hook that fires on uninstall to send a farewell message
anyway. So the other side keeps the contact and can still try to message someone who is gone. This is a
consequence of the local-only, no-sync storage model, not a bug.

**Idea:** an explicit "Remove them and let them know" action (distinct from plain delete), available only
while the app is still installed, that sends a small control message ("X removed you") before deleting
locally. The other side would show the contact as removed and offer to clear it. Cannot help with a plain
uninstall, since nothing can run after that to send the message.

**Status:** proposed, not started.
