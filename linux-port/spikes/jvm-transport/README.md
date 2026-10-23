# WP01 JVM/Android transport spike

Standalone experiment, not a desktop product and not included in the root Gradle build.

## What runs

- JVM rust-nostr 0.44.8 and Tink 1.24.0.
- Disposable Android companion `relay.spike.android`, SDK 36 / minSdk 26, Android rust-nostr 0.44.8,
  Tink Android 1.23.0 (the versions used by the app).
- **The actual production `RelayPayload.kt`** is compiled on both platforms. Android copies that file into its
  generated build sources; the app source isn't edited. Crypto plumbing in `common/Interop.kt` reproduces the
  app's NIP-17 and `TYPE:ENC|CT:` format using those libraries, rather than extracting the app's Android services.
- A real AndroidKeysetManager creates a synthetic Keystore-wrapped HPKE key, exports its private JSON only in this
  debug experiment and verifies import/decryption on JVM. No production key export route was added.
- The existing Worker is run locally with synthetic allowlisted identities. Both clients authenticate HTTP/WS,
  sync, exchange encrypted text, fsync local receipt evidence, acknowledge and verify deletion.

## Requirements and run

Use a disposable emulator, JDK21, Android SDK 36, Node 24+, and the repo's Gradle wrapper. No provider credential is
needed for the local Worker. `npm ci` in `server/cloudflare` installs the pinned development dependencies.

```sh
export JAVA_HOME="$HOME/tools/jdk-21.0.12.1+1"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# Use your own Node24+ absolute path if it is not already on PATH:
python3 linux-port/spikes/jvm-transport/run-local.py --serial emulator-5554 --node node
```

The script refuses physical devices, refuses an occupied port/reverse mapping, never installs over `com.relay.app`,
and shuts down its Worker/reverse mapping on exit. It generates private **synthetic** fixtures under gitignored
`.local/wp01-<timestamp>/` (directory mode 0700); don't commit or attach those files. The isolated test APKs remain
installed for inspection; remove `relay.spike.android` and `relay.spike.android.test` when finished.

Run only JVM negative tests:

```sh
./gradlew -p linux-port/spikes/jvm-transport --no-daemon \
  -Dorg.gradle.java.home="$JAVA_HOME" :jvm:test --rerun-tasks
```

`gradle/verification-metadata.xml` belongs to this standalone project. Regenerate only deliberately, resolving
`:jvm:test :jvm:installDist :android:assembleDebug :android:assembleDebugAndroidTest` with `-PandroidProbe`.

## Actual app UI follow-up

`run-app-ui.py` exercises the real 1.15 app conversation, production outbox and transport against the local Worker.
See [follow-up evidence](../../evidence/WP01-app-ui.md) for exact commands, isolation and remaining gates. It only
resets disposable app state after an explicit flag and exact ownership marker. `test_app_ui_guards.py` checks those
protections without running adb. Do not install this local test artifact on a personal phone.

## Original companion boundaries

This proves cross-runtime crypto and local mailbox interoperability, **not** a release 1.15 app displaying a chat,
live `relay-mailbox-test` delivery, multi-device linking, a physical phone, Tor or incoming-call behaviour.
Those WP01 acceptance gaps remain open in [the evidence](../../evidence/WP01.md).
Do not use the fixture serializer as production key management or the debug manifest's cleartext permission in the
real app. The measurements are local runtime timings, not Cloudflare CPU benchmarks.
