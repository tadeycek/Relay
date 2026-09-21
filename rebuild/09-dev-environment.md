# 09. Dev environment and phone testing

## Versions in the repo today

Read from `build.gradle`, `app/build.gradle`, `gradle/wrapper/gradle-wrapper.properties`:

| Item | Version |
|---|---|
| Android Gradle Plugin | 8.3.2 |
| Kotlin / Compose compiler plugin | 2.0.21 |
| Gradle wrapper | 8.14 (`gradle-8.14-all.zip`) |
| JDK | 17 (`sourceCompatibility`/`jvmTarget` 17) |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 26 (Android 8.0) |
| Compose BOM | 2024.04.01 |
| Crypto | Tink 1.23.0, SQLCipher 4.5.4, security-crypto 1.1.0, biometric 1.1.0 |
| QR / camera | zxing 3.5.4, CameraX 1.4.2, ML Kit barcode 17.3.0 |

Note: AGP 8.3.2 with `compileSdk 36` and Gradle 8.14 works today according to the repo, but Android Studio may nag about
supported SDK/AGP combinations. Do not upgrade AGP as part of the rebuild unless something forces it.

## What to install

On the computer:
1. **Android Studio** (bundles SDK, Gradle handling and `adb`). SDK Platform 36 via SDK Manager.
2. **JDK 17** (Android Studio's bundled JBR is fine).
3. **USB driver (Windows only)**: most phones use the default; Samsung/Xiaomi and some others need the OEM driver or
   Google's USB driver from the SDK Manager.

On the phone (Android 8.0 or newer):
1. Settings -> About phone -> tap **Build number** 7 times.
2. Settings -> Developer options -> **USB debugging** on.
3. Connect the USB-C cable, choose file-transfer mode if asked, accept the "Allow USB debugging?" prompt.
4. `adb devices` must show the phone as `device`, not `unauthorized`.

## WSL note

The repo lives in WSL (`\\wsl.localhost\FedoraLinux-44\home\tadej\projects\Relay`). WSL does not see USB devices by
default. Options:
- **Simplest:** run Android Studio and `adb` on Windows and open the project from the UNC path, or clone a second copy
  on the Windows filesystem for faster builds (builds over the UNC path can be slow).
- **Alternative:** `usbipd-win` to attach the phone to WSL, then run `adb` and the Android SDK inside Fedora.

## Build and install

```
./gradlew installDebug        # builds and installs on the connected phone
./gradlew assembleDebug       # APK at app/build/outputs/apk/debug/app-debug.apk
```

## Testing the rebuilt app

- **Two phones are needed** to test a conversation (or one phone plus an emulator for the second party in early UI work).
- **No SIM is needed** for the internet transport (unlike the SMS version). Wi-Fi or mobile data is enough.
- Emulator is fine for UI, DB migration and unit tests; use real phones for background delivery, battery, Doze and
  cellular NAT behaviour, which are the interesting risks.
- Use a **private test relay** (for example a local relay on the LAN or a throwaway one) for early development to
  avoid spamming public relays; switch to public relays for the retention tests in Phase 0.
- For Tor tests: install Orbot on the test phone (Phase 7a), capture traffic on the router or via a proxy/VPN capture
  tool and confirm no direct connections to relay hosts.

## Repo/workflow notes

- Work on a new branch off `main` (for example `rebuild/nostr-transport`); the current branch is `fix/audit`.
- For `gh`/`git` push as the `tadeycek` account, follow `CLAUDE.md` (export `GH_TOKEN` from
  `~/.config/gh-tokens/maturamore.token` inline before each command; add the safe.directory exception if git
  complains about ownership on the WSL UNC path).
- Keep each phase in its own commits/PR so the SMS code can be removed only at the end (Phase 8).
