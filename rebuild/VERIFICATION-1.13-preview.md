# 1.13-preview — host validation and APK, 2026-10-19

## Result

Tadej requested tests/build, explicitly lifted the low-power restriction at home, and approved **1.13-preview / versionCode 14**. The expanded regression patch compiles, all host checks below pass, and an original-key-signed, non-debuggable release APK is built. No emulator, phone/ADB command, installation, public release, deployment, commit or push was performed. Physical cold-start/message latency and an actual Android SQLCipher upgrade remain unverified.

## Checks

| Check | Result |
| --- | --- |
| Android debug JVM suite | 328 tests, 52 suites; 0 failures/errors/skips |
| Android release JVM suite | Same 328 tests, 52 suites; 0 failures/errors/skips |
| Release lint | Passed; 0 errors/fatals, 20 warnings, 13 informational findings |
| Server `npm run typecheck` | Passed |
| Server `npm test` | 110 tests in 9 files passed; isolated local Worker runtime |
| Host SQLite schema smoke | Fresh relevant tables and synthetic v22→v23 migration pass; existing DM/group rows preserved, legacy delivery fields default correctly, new foreign-key cascades and `foreign_key_check` pass |
| APK verification | Signature verifies and matches the preserved prior release APK; package/version/non-debuggable checks pass |
| Build configuration | Firebase Android package matches `com.relay.app`; release BuildConfig contains the configured HTTPS mailbox URL |
| Work preservation | Primary checkout's independent tracked perf diff unchanged; final whitespace check passes |

The SQLite smoke check executes extracted source DDL in host SQLite with synthetic in-memory data, not Android SQLCipher or any real phone database. Server test logs contain local Durable Object reset/cleanup diagnostics; all 110 tests and the process exit code pass. Existing AGP/compileSdk compatibility and deprecated-API warnings remain; no dependency upgrades or unrelated repairs were made.

## Artifact

- Delivered file: `/home/tadej/Downloads/Relay-1.13-preview.apk`
- Build output: `app/build/outputs/apk/release/app-release.apk`
- Package: `com.relay.app`; versionName `1.13-preview`; versionCode `14`; non-debuggable.
- Size: 76,371,029 bytes.
- SHA-256: `4a4ed42a8529a9763e140a2aee37f92e033026852f9502a4907196b27ff277fa`
- Original signer certificate SHA-256: `06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9`
- Application source snapshot SHA-256: `54344867d762bb1d9d87e57a0770c3cd29f132b43b57ee213704c64357e959c0` (sorted changed/untracked `app` paths and exact bytes, each NUL-separated).
- Base branch/commit: `feat/cloudflare-mailbox` / `4f97ba0`; changes remain local/uncommitted.

The previous APK and test reports were preserved under the private validation directory before rebuilding. This APK is an update candidate, not a published or device-accepted release. **Install as an update only; do not uninstall or clear storage.** Version 23 of the database must not be opened by an older APK as a rollback method.

## Reproduction and evidence

JDK 17, Gradle 8.14, offline, no persistent daemon, four workers, Kotlin compilation in-process. Final successful Gradle invocation (1m 53s):

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew \
  --offline --no-daemon --max-workers=4 --console=plain \
  -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk \
  '-Dorg.gradle.jvmargs=-Xmx3072m -XX:ActiveProcessorCount=8 -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process -PrelayPreview=true \
  :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

Android commands ran within transient systemd user scopes with `MemoryMax=8G`, `MemorySwapMax=0`, `TasksMax=1024`, **no CPU quota**. Server scopes had a 3 GiB memory ceiling and no CPU quota. All five scopes are inactive/dead afterward. No new software was installed.

Private evidence: `.local/full-validation-20261019-NCMuDp/` contains Android/server logs, original APK/report backups, signing/badging checks and source/APK hashes. Current JUnit XML and lint XML/HTML are under `app/build/test-results/` and `app/build/reports/`.

## Still open

See `FIXES-2026-10-19.md` for the ordered two-device acceptance checklist. #39 and #47–#53 must remain open until cold opening People/chat lists/DM/groups, burst/photo delivery, offline/restart, signals/seen, actual upgrade/privacy/retention/deletion/wipe checks pass on appropriate test devices. Host tests do not establish that the reported 10–20-second delays are gone.
