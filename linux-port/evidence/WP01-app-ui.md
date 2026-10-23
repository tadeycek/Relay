# WP01 follow-up — actual app conversation UI

2026-10-23. **Local UI gap closed; live-provider/physical-phone gates remain open.**
This supplements [WP01's historical first-run evidence](WP01.md), not a rewrite of it.

## What actually ran

The existing 1.15 production UI, calculator unlock, encrypted SQLCipher database, `IncomingMessageHandler`,
`RelayCrypto`, durable outbox and `MailboxTransport` exchanged messages with the JVM peer through the actual Worker
running locally. The host sent encrypted text; UiAutomator asserted it appeared in the real conversation and in the
app DB, entered a reply in the real composer and tapped Send. The host then received and NIP-17/HPKE-decrypted the
reply before signalling the instrumentation test to finish.

This is a **debug test artifact using production app code**, not the untouched published APK. Test-only controls:
- A custom instrumentation runner substitutes a plain Application and installs the local transport **before any
  retained Activity can resume**. Production Application/transport sources were not changed.
- `ui-local.init.gradle` removes the build's live mailbox URL and selects that test runner. Local cleartext is
  already allowed for `10.0.2.2` by the existing debug network policy. No production TLS setting is weakened.
- A synthetic trusted contact is seeded with the JVM peer's HPKE public key. **No real in-person QR pairing was
  performed** despite the fixture UI displaying 'Verified in person'.
- The host only targets emulators. It refuses an installed Relay app unless explicitly passed
  `--reset-owned-fixture` **and** the app-private ownership marker matches exactly. It never reads a personal
  database or exports the Android identity secret.

These test-only files extend WP01's original owned-files list into `app/src/androidTest/`; this is an explicit scope
extension to exercise the app rather than its companion. No `app/src/main/`, root dependency pins or server code
changed.

## Commands and observed results

```sh
export JAVA_HOME="$HOME/tools/jdk-21.0.12.1+1"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"
NODE24=$(npx --yes --package=node@24 which node)
# Fresh emulator without Relay installed:
python3 linux-port/spikes/jvm-transport/run-app-ui.py --node "$NODE24"
# Reset only a fixture previously marked as owned by this harness:
python3 linux-port/spikes/jvm-transport/run-app-ui.py --node "$NODE24" --reset-owned-fixture
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/jvm-transport/test_app_ui_guards.py -v
```

Root debug app/test APKs built successfully; initial clean build: **67 tasks executed**. The harness also rebuilds
the standalone JVM peer and executes its **5 tests (0 failures/errors/skips)** with `--rerun-tasks`.
Actual app instrumentation: **1 test passed**, retained final successful fixture
`.local/wp01-ui-1792753297566072120`, time **28.610 s**. Output:

```text
PASS JVM encrypted send to actual Android app identity
PASS actual app UI→production outbox→MailboxTransport→local Worker→JVM NIP-17/HPKE decrypt
com.relay.app.calculator.JvmAppInteropTest:.
Time: 28.61
OK (1 test)
```

The independently run existing root unit suite (`:app:testDebugUnitTest --rerun-tasks`) passed:
**329 tests, 0 failures, 0 errors, 0 skipped**, parsed from **53 fresh XML reports**. Build executed 25 tasks.

Python ownership/preflight tests: **4 passed**. RED was established by **mutation**, not first-run failure:
removing the exact ownership-marker comparison caused `test_reset_requires_exact_ownership_marker` to fail on
'Attempted app-data clear without valid fixture authorization'. Restoring it returned all four tests to green.
All destructive commands were mocked during that mutation check; no actual fixture/personal data was cleared by it.

## Visual/readback evidence

- [Instrumentation output](wp01-app-ui/instrumentation.txt)
- [Pass marker](wp01-app-ui/passed.json)
- [Actual conversation screenshot](wp01-app-ui/chat.png), inspected visually: incoming
  `JVM received in actual app 0cc09e56`, outgoing `Actual app UI reply 0cc09e56`, contact `JVM Spike`.
- Screenshot SHA-256: `f7d763ca04876d211a1783d86f86e147714e1be39131a7c0891b46ef9624aab5`.

## Failures retained / fixes

- First UI attempt failed at 'Unlocked app'. Granting the emulator's notification permission prevents its system
  dialog covering the unlocked UI. Failure was a harness setup issue, not evidence of broken decryption.
- Additional resets initially detected an already-created NostrTransport despite the plain test Application:
  retained Activity lifecycle can run before the test method. Local transport injection moved to the runner's
  `callApplicationOnCreate`, ahead of Activity restoration. A full UI exchange passed after that change.
- A further repeat after the retained success was interrupted when `emulator-5554` disappeared from adb. That
  repeat is **not a pass** and does not add to the test counts above. No cause is asserted without host evidence.

## Current acceptance boundaries

WP01 AC1/AC2 are now demonstrated against the real 1.15 app implementation and conversation UI, with the explicitly
controlled debug endpoint/startup and seeded contact. AC3/AC4 retain the earlier evidence. WP01 remains `doing`
because live `relay-mailbox-test`, real phone and security/linking release gates have not all been met. This does
not implement desktop product UI, linking, Cloudflare deployment or production calling. Private keys, message
fixture bodies and Worker state remain under ignored `.local/`; test artifacts must not be shipped to users.
