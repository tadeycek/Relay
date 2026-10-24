# WP09 shared-core review fixes

Scope: fixes to the existing uncommitted implementation on `feat/linux-port`; no commit, push, merge or deployment.
The four reproduced core defects are fixed. **WP09 / G3 platform readiness is not complete.** No app/desktop UI,
task board, mailbox transport implementation, credentials, `.local` or `.private` files were touched by this fix.
The parallel transport agent owns its own mailbox files/tests.

## Actual API and wire

Frozen planning constants (unchanged): maximum file **104857600**, stored-object cap **26214464**, full plaintext
part **26212814**, maximum **5** parts. Raw framing does not increase the plaintext planning limit.

Stored file part, exactly:

```
52 4c 59 46 | 01 | random IV[12] | AES-256-GCM ciphertext[plainLength] | tag[16]
    RLYF      v1
```

Stored length is `plainLength + 33`: full part **26212847**, empty part **33**, fifth part of a maximum file
**6344** plaintext / **6377** stored. `FilePartEncryptor.UPLOAD_CONTENT_TYPE = application/octet-stream`.
Unknown magic/version, truncation and excess bytes fail closed. Legacy photo `MediaContainer` and `MediaCrypto`
are untouched. Inspection of `server/cloudflare/src/media.ts` `put()` shows that it accepts opaque authenticated
bytes without restricting Content-Type or requiring JPEG; **no server change was necessary**. No server suite or
server typecheck was rerun in this fix, since no server code/tests changed. Live server raw upload remains NOT RUN.

Canonical authenticated body (ASCII, bounded to 16384 characters):

```
TYPE:FILE|V:1|NAME64:<padded b64 utf-8>|MIME:<type/subtype>|SIZE:<0..104857600>|SHA256:<64 lowercase hex full plaintext digest>|ID64:<padded b64 utf-8 original RelayPayload.id>|KEY:<padded b64 32 bytes>|PARTS:<1..5>|P1:<stored sha256>,<stored length>|...|URLBASE:<owned https media base>[|CAP64:<caption/reply>]
```

`SHA256` and `ID64` are mandatory, with no constructor defaults. Existing name/MIME/URL validation remains.
Zero bytes means **one independently authenticated empty GCM part**, not zero parts: random IV and valid tag are
still required. Full digest is `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

- `FileSender.prepare` computes the full plaintext digest while reading, generates a new random 32-byte file key,
  uses fresh random IVs, and hands its callback only completed ciphertext plus `StoredPart`. The callback must
  durably persist/consume each completed stream before returning. Retry reuses these exact ciphertext bytes,
  hash and IV; never index-derived IVs or newly encrypted retries. Source length mismatches fail.
- `FileManifest.fromAuthenticatedPayload` requires a caller-authenticated E2E payload and checks that its `id`
  equals body `ID64`. The immutable part-list snapshot plus authenticated version, size, count, ordered stored
  hashes/lengths and file id implement G3's authenticated-manifest alternative to extra AEAD AAD.
- `FileAssembler.assemble` requires the original expected file id from the saved transfer task, checks it before
  fetching, verifies every part, stages the whole ordered plaintext privately and verifies its complete SHA-256
  and size **before any final sink write**. Wrong whole-file digest with valid per-part tags publishes zero bytes.
- Persist the original id AND exact authenticated manifest in platform retry/resume state; never replace that
  manifest under an existing task. SENT must carry the original `RelayPayload` and id, not the outer sync id.
  Tests exercise wrong payload/manifest association, wrong resume id and a real SyncCodec SENT roundtrip.
- Confirm a blob only after durable final storage. The sender-device enrollment race between PUT and SENT is
  **open server integration follow-up**, not fixed by these helpers or by their id binding.

## Provider-compatible memory design (not a phone heap claim)

The original fixed 64 KiB doFinal output failed with Conscrypt. The final implementation reads one bounded part
into **one reusable message array of plaintext length + 16**, then uses one-shot JCA `doFinal` with distinct
ByteBuffer views of that same backing array. This is the standard copy-safe overlapping-buffer API, exercised on
SunJCE and Conscrypt 2.5.2. It avoids assuming providers stream encryption or release plaintext incrementally,
and avoids simultaneous separate app stored-blob, plaintext and provider full-message arrays. Array contents are
wiped in `finally`. Input/output stream reads/writes remain at most 64 KiB.

Decrypt first spools/hash-checks ciphertext to a 0600 private stage. In-place tag verification finishes before
plaintext enters the second private stage. Both stages are removed **before** the final sink receives plaintext.
`FileStaging` / `FileStage` are injectable; platforms must inject their app-private staging/quota policy.
Cleanup failures propagate (including suppressed failures), and cleanup-failure, disk-error, bad-tag and
truncation paths publish zero final bytes. `releaseForRead` unlinks an already-open private file, supported by the
Linux/Android/macOS file model; other filesystem semantics need another staging implementation.
Whole assembly also uses private disk; conservative transient space is **157283294 bytes** for maximum whole-file
stage plus two full part stages, excluding durable retry ciphertext and the final destination. Account quotas,
crash leftovers and cancellation integration are platform work, not waived.

Final local measurements, parsed from the final JUnit XML (`2026-10-24T14:08:29Z` through `14:08:33Z`):

| Provider / operation | Thread-allocated Java bytes for one full part |
|---|---:|
| SunJCE encryption | 26253560 |
| SunJCE decryption | 26355416 |
| Conscrypt 2.5.2 encryption | 26447168 |
| Conscrypt 2.5.2 decryption | 26353256 |

These are **allocation totals**, not peak live heap, process RSS or native memory. The Conscrypt ByteBuffer path
allocates native/direct scratch: the JVM direct-buffer pool was **45056** before the pair of operations and
**104896344** afterward, before GC. This is a retained-pool snapshot, **not native peak usage**. Direct buffers may
survive a part until GC; do not sum thread allocations into a fake peak, claim constant 64 KiB crypto memory, or
claim total process memory below 64 MiB. The empirical provider test checks thread allocation below
`3 * partPlainLength + 2 MiB`; this regression budget is not a portable heap/native guarantee.

Intermediate experiments are retained honestly: switching update/doFinal to returned arrays fixed the exception
but allocated roughly 118.5 MB / 131.2 MB on Conscrypt; one-shot byte[] calls still allocated 107460112 bytes on
encryption. The allocation regression failed before moving to the final ByteBuffer API. Neither intermediate
implementation is the final source. **Real Android peak heap / native / CPU measurement remains NOT RUN.**

## RED / GREEN commands and real results

All commands run from `/home/tadej/repos/Relay`. The shared prefix for the original focused RED runs was:

```sh
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1
./gradlew --no-daemon --console=plain \
  -Dorg.gradle.java.home=/home/tadej/tools/jdk-21.0.12.1+1 -Dorg.gradle.jvmargs=-Xmx2g \
  :core:test --tests <test below> --rerun-tasks
```

| Finding | Test selection / change | Observed RED |
|---|---|---|
| Buffering provider | `com.relay.app.files.FileProviderRegressionTest`, with `-I /home/tadej/.hermes/cache/scratch/wp09-provider.init.gradle` before task | 1 test, 1 failure: `org.conscrypt.ShortBufferWithoutStackTraceException` |
| Raw framing | `com.relay.app.files.FilePartCryptoTest.storedBlobUsesVersionedRawFraming` | 1 test, 1 failure: expected raw header, got old JPEG |
| Missing full digest | `com.relay.app.files.FileBodyTest.manifestWithoutFullPlaintextDigestIsRejected` | 1 test, 1 failure: old parser accepted missing digest |
| Empty file | `--tests com.relay.app.files.FileBodyTest --tests com.relay.app.files.FilePartPlanTest.emptyFileHasOneAuthenticatedEmptyPart` | 22 tests, 1 failure: null plan instead of authenticated empty part; digest tests green |

Logs are respectively `wp09-red-provider.log`, `wp09-red-raw.log`, `wp09-red-digest.log`,
`wp09-red-empty-green-digest.log` under `/home/tadej/.hermes/cache/scratch/`.

New helper guards and follow-on tests also have **mutation evidence, not a fabricated first-run RED**:
removing digest/id/resume guards gave 6 tests / 3 failures (`wp09-red-transfer-mutation-isolated.log`);
removing digest/id guards for the later multi-part/SENT tests gave 2 tests / 2 failures
(`wp09-red-transfer-followon-mutation.log`). Moving ciphertext cleanup after publication gave 1 test / 1 failure
(`wp09-red-cleanup-mutation.log`). All mutations were restored. Provider allocation regression before the final
ByteBuffer fix gave 1 test / 1 failure (`wp09-red-provider-allocation.log`).

An initial helper run could not compile the other agent's in-progress `MailboxStreamingTest`; that was a harness
failure, **not RED evidence**. The first isolated script used a root-relative source path and returned NO-SOURCE;
that was also not a test pass. The corrected script uses the absolute test source path and independent outputs/cache.

Final isolated command (all owned file tests, provider jar included):

```sh
JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 ./gradlew --no-daemon --console=plain \
  -Dorg.gradle.java.home=/home/tadej/tools/jdk-21.0.12.1+1 -Dorg.gradle.jvmargs=-Xmx2g \
  -I /home/tadej/.hermes/cache/scratch/wp09-files.init.gradle \
  --project-cache-dir /home/tadej/.hermes/cache/scratch/wp09-files-cache \
  --no-build-cache -Pkotlin.incremental=false :core:test --rerun-tasks
```

`wp09-files.init.gradle` (test-only harness outside the repo):

```groovy
allprojects { p ->
    if (p.name == 'core') {
        layout.buildDirectory.set(file('/home/tadej/.hermes/cache/scratch/wp09-files-build'))
        afterEvaluate {
            kotlin.sourceSets.test.kotlin.setSrcDirs([file('/home/tadej/repos/Relay/core/src/test/kotlin/com/relay/app/files')])
            dependencies { testRuntimeOnly files('/home/tadej/.hermes/cache/scratch/wp09-review/conscrypt-openjdk-uber-2.5.2.jar') }
        }
    }
}
```

The earlier provider-only init script adds the same testRuntimeOnly local jar without changing test sources or
outputs. No production dependency or dependency-verification metadata changed. The existing reviewer jar SHA-256:
`eaf537d98e033d0f0451cd1b8cc74e02d7b55ec882da63c88060d806ba89c348`.
Without that opt-in jar the provider test reports a JUnit assumption skip; that is not provider verification.

**Final result: 78 tests, 0 failures, 0 errors, 0 skipped**, from 8 XML reports under
`/home/tadej/.hermes/cache/scratch/wp09-files-build/test-results/test/`; log `wp09-files-final.log`.
Classes/counts: FileBody 21, FilePartCrypto 21, FilePartPlan 11, FileSafety 11, FilesGolden 1,
FileProviderRegression 1, FileStaging 4, FileTransfer 8. Full-size SunJCE and Conscrypt roundtrips, Conscrypt full-size
bad-tag/zero-publication and truncation, multi-part assembly, empty-file sender/codec/crypto/golden roundtrip all ran.
JDK/Gradle deprecation warnings remain; there were no test errors or skipped provider checks in this run.

Golden recording used the same isolated command with `RELAY_GOLDEN_RECORD=1` and
`--tests com.relay.app.files.FilesGoldenTest`; the final sweep ran with recording OFF. `encode.*` are explicit
synthetic codec metadata fixtures; `blob.*` are real deterministic encryption/hash vectors, including zero bytes.
New files golden SHA-256: `c3e847a863d506089d116e521966ea6a7d5895745d1a4d0b2cd6bea6555ad5e6`.
Old goldens match HEAD byte-for-byte by sha256sum:
- body-codecs: `aa9bdb1b73cfdb6b5d4c3c86927a0853766c01e226168e6af9aca724156a5802`
- linking-codecs: `34f0a24540b1161fd8bfd107ddca0a67f3a7fa8b9e516f6cb41915a899adf572`

## Remaining verification / integration gates

- Per the later explicit ownership coordination, this agent ran **only its isolated file test classes**; parent
  must rerun the entire final core suite after both writers finish. The prior 314-test baseline is historical,
  not a claimed final-tree result. Do not confuse this 78-test owned suite with all core tests.
- Android and desktop hosts: real 60/100 MiB transfers, Android <64 MiB peak heap (AC3), native memory/CPU,
  retry DB/state, app-private staging cleanup after crash, cancellation, quotas, final-save permissions,
  collision/symlink-safe atomic publication, UI, progress, explicit confirms and original SENT id wiring NOT RUN.
- Server whole-body hash/auth CPU/heap, live raw full-part upload, row/quota measurement and the self-sync grant
  race remain open. No live provider, Android build, device, Mac, release or deployment was exercised here.
- No readiness gate or lane was promoted; no platform scaffold is called complete.
