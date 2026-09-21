# Rebuild progress log

Branch: `feat/rebuild`. Each phase is two commits. Status labels: **built** (compiles, unit tests where the logic is pure),
**device-unverified** (needs a real phone/relay to confirm; no device was available while this was written).

## Environment used for building

No JDK or Android SDK existed on the machine, so a toolchain was installed under `~/tools` in the WSL Fedora
distro: Temurin JDK 17, Android SDK (platform 36, build-tools 36.0.0 and 34.0.0, platform-tools), standalone
Gradle 8.14. The Android SDK licences were accepted non-interactively (`sdkmanager --licenses`) to allow this.
Use `rebuild/dev/build.sh <gradle args>`.

## Phase 0: Spike

- Built. Nostr SDK dependency `org.rust-nostr:nostr-sdk-kmp-android:0.44.8` resolves and compiles; the API surface
  guessed from the SDK sources matched on the first compile (Keys, NostrSigner, Client, EventBuilder.privateMsgRumor,
  giftWrapTo, UnwrappedGift, Filter, fetchEventsFrom).
- Debug APK builds with native libs for all four ABIs. **Debug APK is ~83 MB** (all ABIs, unshrunk); the SDK AAR alone
  is ~17 MB, roughly 4 to 6 MB per ABI. Release builds should use ABI splits/AAB.
- Debug-only `RelayLabActivity` (`adb shell am start -n com.relay.app/.lab.RelayLabActivity`) implements the retention
  probe: "Send probe" then "Check probe" after 1 h, 24 h, 72 h, 7 d.
- **Device-unverified:** relay retention, background survival, real latency. The Phase 0 exit criteria in
  `07-phases.md` are therefore **not yet confirmed**; run the lab on two phones before trusting later phases.
- Fixed a pre-existing compile error on `fix/audit` (`Manifest.permission.READ_MMS` does not resolve on SDK 36).
