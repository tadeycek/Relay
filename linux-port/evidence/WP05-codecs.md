# WP05 codecs evidence — link bundle, SYNC bodies, link code

Status: **done for the `:core` codec slice.** 244 `:core` tests pass (198 baseline + 46 new), the
existing 328-entry golden corpus is byte-identical, no dependency was added and no existing codec
was modified. Date 2026-10-24, branch `feat/linux-port`, base `715da4b`. Nothing committed or pushed.

Scope: `core/src/main/kotlin/com/relay/app/linking/**` (new),
`core/src/test/kotlin/com/relay/app/linking/**` (new),
`core/src/test/resources/golden/linking-codecs.golden` (new), this file. No `app/`, `desktop/`,
`server/cloudflare/**`, `linux-port/tasks.md` or `linux-port/wps/**` file was touched.

## Commands

```sh
cd /home/tadej/repos/Relay
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1

# final run (the only run whose counts are quoted)
./gradlew --no-daemon -Dorg.gradle.java.home=$JAVA_HOME --console=plain --rerun-tasks :core:test

# recording the new corpus (once; the committed file is compared on every normal run)
RELAY_GOLDEN_RECORD=1 ./gradlew --no-daemon -Dorg.gradle.java.home=$JAVA_HOME \
  --console=plain --rerun-tasks :core:test --tests 'com.relay.app.linking.LinkingGoldenTest'

# leak checks
grep -rnE '^import (android|androidx)\.' core/src     # no match
grep -rn 'crypto.tink' core/src/main                  # no match (Tink stays test-only)
git diff --stat core/build.gradle                     # empty: no dependency change
```

XML timestamps 2026-10-24T12:08:22Z. Counts are parsed from
`core/build/test-results/test/TEST-*.xml` (`tests`/`failures`/`errors`/`skipped`), not from the
console.

## Results

| suite | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| `BodyCodecGoldenTest` (existing corpus) | 1 | 0 | 0 | 0 |
| `LinkCodeTest` | 7 | 0 | 0 | 0 |
| `LinkBundleTest` | 15 | 0 | 0 | 0 |
| `SyncCodecTest` | 11 | 0 | 0 | 0 |
| `SyncReassemblerTest` | 12 | 0 | 0 | 0 |
| `LinkingGoldenTest` | 1 | 0 | 0 | 0 |
| **all `:core` suites** | **38 suites / 244 tests** | **0** | **0** | **0** |

- New tests: **46**. Baseline (before this slice) 198 → 244.
- Existing golden corpus: `body-codecs.golden`, **328 entries**, sha256
  `aa9bdb1b73cfdb6b5d4c3c86927a0853766c01e226168e6af9aca724156a5802` — unchanged from WP03, so no
  existing codec output moved.
- New corpus: `linking-codecs.golden`, **41 entries**, sha256
  `34f0a24540b1161fd8bfd107ddca0a67f3a7fa8b9e516f6cb41915a899adf572`. It pins the link-code
  derivations, the canonical bundle plaintext, every SYNC wire body, the 5-part `KEYS` chunking and
  the hostile-input results. It is a separate file; `body-codecs.golden` was not opened for writing.

### Measured sizes (printed by the tests, copied from the JUnit XML `system-out`)

| measurement | value |
|---|---|
| maximum SENT: payload text | 9,025 chars |
| maximum SENT: wire body | **12,286 bytes** |
| maximum SENT: base64 of the wire body | **16,384 bytes = exactly the 16 KiB bound** |
| maximum SENT: next size up | refused by `SyncFormatException` |
| `KEYS` with a 40,000-char keyset: parts | 5 |
| `KEYS` part: largest wire / base64 | 11,089 / 14,788 bytes |
| representative bundle (2 contacts, 1 group, settings) | plaintext 1,822 B, sealed 1,860 B |
| link slot cap | 131,072 B (128 KiB), enforced before crypto |

## Wire format as implemented

### 1. Link code and slot (`LinkCode`)

- Entropy: **32 random bytes** (`SecureRandom` by default; injectable `java.util.Random` for a DRBG
  or deterministic harness).
- Canonical text: **64 lowercase hex characters**. This is exactly the server slice's `code()` shape.
- Human form: eight groups of eight, `00010203-04050607-…`; the normaliser also accepts spaces,
  colons and uppercase. Any other character, length ≠ 64 or non-hex → invalid (`null`).
- QR form: `RELAYLINK:1:<64 hex>`.
- **Slot id** = `sha256(UTF-8(canonical code text))`, 64 lowercase hex. The server computes
  `sha256Hex(code)` over the same text — not over the raw bytes (verified by vector and by reading
  `server/cloudflare/test/link-slot.test.ts`: `const slotFor = (code) => sha256Hex(code)`).
- **Proof header**: name `x-relay-link-proof` (matching the server slice; HTTP header names are
  case-insensitive), value = the canonical 64-hex code. Hex is URL-safe and header-safe. The desktop
  must send it on `GET` and `DELETE /v1/link/<slot>`; the server hashes the value and compares in
  constant time. The code is never a key: `LinkBundleCodec.seal` has no code parameter.

Fixed vectors (pinned in `linking-codecs.golden` and `LinkCodeTest`):

```
bytes 00..1f  -> 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
slot          -> 6c86c6aac5fb24bcf5d9939cb7d7d5645ce39418f449e03b262dd4fa14b4b92b
human         -> 00010203-04050607-08090a0b-0c0d0e0f-10111213-14151617-18191a1b-1c1d1e1f
```

### 2. Sealed link bundle (`LinkBundleCodec`)

Outer envelope, **all integers big-endian**:

| offset | size | field | bound / rule |
|---:|---:|---|---|
| 0 | 4 | magic | ASCII `RLYB` |
| 4 | 1 | envelope version | `1`; other → `UNSUPPORTED_VERSION` |
| 5 | 1 | HPKE suite | `1` = X25519 / HKDF-SHA256 / AES-128-GCM (Tink HPKE); other → `UNSUPPORTED_SUITE` |
| 6 | 4 | ciphertext length `N` (uint32) | `1 ≤ N ≤ 131,062`; larger → `TOO_LARGE` |
| 10 | N | HPKE ciphertext + AEAD tag | exact: `size == 10 + N`, else `TRUNCATED` / `TRAILING_BYTES` |

Total ≤ **131,072 bytes**. The plaintext JSON is capped at
`131,072 − 10 − 64 = 130,998` bytes. The decoder checks size, magic, version, suite and the length
field **before** calling HPKE, and never throws.

Inner plaintext is canonical JSON written by a hand-rolled writer (not `JSONObject.toString`, whose
map order is an implementation detail). Field order is fixed, optional fields are omitted when null,
so `decode → encode` is byte-identical:

```json
{"v":1,"createdAt":<ms>,"identity":{...},"contacts":[...],"groups":[...],"settings":{...}}
```

The schema is **closed**: unknown keys are `MALFORMED`, and `messages`/`history`/`chats`/`media`/
`messageHistory` are `HISTORY_FORBIDDEN` (LP-D06). There is no message field in the Kotlin model at
all (asserted reflectively).

| object | field | type | bound / rule |
|---|---|---|---|
| root | `v` | int | must be 1 (inner version) |
| root | `createdAt` | int64 | `0 … Long.MAX` |
| `identity` | `nostrSecretHex` | string | exactly 64 lowercase hex |
| `identity` | `nostrPubkeyHex` | string | exactly 64 lowercase hex |
| `identity` | `deviceId` | string | `[A-Za-z0-9_-]{1,64}` |
| `identity` | `deviceLabel` | string | 0–64 chars, no control/format chars |
| `identity` | `hpkeKeysetBase64` | string | 1–8,192 chars, standard base64 |
| `identity` | `hpkePreviousKeysetBase64` | string? | 1–8,192 chars, base64 |
| `identity` | `hpkeKeysetExpiresAt` | int64 | ≥ 0 |
| `contacts` | array | ≤ **1,000** entries | |
| `contact` | `name` | string | 1–100 chars, no control/format |
| `contact` | `phone` | string | 0–64 chars, no control/format |
| `contact` | `hasRelay` | bool | |
| `contact` | `trustLevel` | string | `trusted` / `ask` / `blocked` |
| `contact` | `publicKey` | string? | ≤ 512 chars, base64 |
| `contact` | `pendingPublicKey` | string? | ≤ 512 chars, base64 |
| `contact` | `signingPublicKey` | string? | ≤ 128 chars, base64 |
| `contact` | `nostrPubkey` | string? | exactly 64 lowercase hex |
| `contact` | `relayHints` | array | ≤ **5**, each ≤ 300 chars and `InboxRelays.isAcceptableRelay` |
| `contact` | `qrVerified` | bool | |
| `contact` | `deletedAt` | int64? | ≥ 0 |
| `contact` | `originalName` | string? | ≤ 100 chars |
| `groups` | array | ≤ **200** entries | |
| `group` | `id` | string | canonical UUID (36 chars, lowercase) |
| `group` | `name` | string | 1–100 chars |
| `group` | `sharedOwner` | string? | 64 lowercase hex |
| `group` | `members` | array | ≤ **256**, each 64 lowercase hex, no duplicates |
| `settings` | `typingIndicators` | bool | |
| `settings` | `notificationPreview` | bool | |
| `settings` | `retentionDays` | int | `0 … 365` |
| `settings` | `readReceipts` | bool | |
| `settings` | `mediaAutoDownload` | bool | |
| `settings` | `theme` | string | ≤ 32 chars |
| `settings` | `locale` | string | ≤ 16 chars |

JSON nesting is capped at depth 8 before `org.json` parses it, so a 128 KiB `[[[[…` input cannot
cause a `StackOverflowError` (`MALFORMED` instead).

### 3. `TYPE:SYNC` bodies (`SyncCodec`)

```
TYPE:SYNC|<KIND>|V:1|SID:<sync id>|R:<revision>|O:<origin device>|<kind fields>
```

- `SID`: `[A-Za-z0-9_-]{1,64}`; `newSyncId()` is 128 random bits as 32 lowercase hex.
- `R`: int64 ≥ 0, monotonic per sync id (the caller enforces ordering; the codec rejects negatives).
- `O`: `[A-Za-z0-9_-]{1,64}` (device id; the phone is `primary`). This is **not** cryptographic proof
  of phone origin (G1) — device authorisation is the separate server slice.
- Encoder order is fixed; decoder accepts any order, rejects duplicate keys, unknown keys, missing
  fields, extra fields.
- Every single-part body must satisfy **base64(wire) ≤ 16,384 bytes**, enforced at encode time;
  `MAX_RAW_BYTES = 12,288` (12,288 → exactly 16,384 in standard base64).

| kind | fields (after the common header) | bound |
|---|---|---|
| `SENT` | `TO:<64-hex contact>` or `group:<64-hex owner>:<uuid>`; `P:<base64 RelayPayload JSON>` | target ≤ 160 chars; `P` ≤ 32,768 chars base64 and must parse as a `RelayPayload` (id ≤ 64, body ≤ 16 KiB) |
| `READ` | `C:<conversation>`; `T:<ts>` | conversation `[A-Za-z0-9:_.-]{1,150}`; ts any int64 |
| `KEYS` | `K:<base64 sealed keysets>` | 1–65,536 chars base64; the sealed payload contains current + previous + expiry |
| `CONTACT` | `J:<base64 contact JSON>` | 1–65,536 chars base64 |
| `CONTACT_DEL` | `J:<base64 contact JSON>` | same |
| `GROUP` | `J:<base64 roster+meta JSON>` | same |
| `SETTINGS` | `J:<base64 settings JSON>` | same |
| `DELETE` | `C:<conversation>`; `ID:<payload uuid>` | canonical UUID |
| any | `PART:1`; `P:<i>/<n>`; `T:<total bytes>`; `H:<sha256 hex>`; `D:<base64url chunk>` | `1 ≤ n ≤ 64`, `1 ≤ i ≤ n`, `1 ≤ T ≤ 262,144`, `D` decodes to ≤ 8,192 bytes |

`D` is base64url **without padding** so it never contains `+`, `/` or `=` inside a `|`-delimited
body. `PART:1` makes the part envelope unambiguous with `SENT`'s `P:` payload field.

`chunk(body)` returns `[wire]` when it already fits, else up to 64 parts of the **logical UTF-8
wire body** with the whole-body SHA-256 and byte length on every part. `encodeLogical` skips the
16 KiB check but is still bounded at 256 KiB.

### 4. Reassembly (`SyncReassembler`)

| rule | behaviour |
|---|---|
| key | `SID` |
| duplicate part (same index, same bytes) | `Duplicate(received, count)`, assembly unchanged |
| same index, different bytes | `Rejected(CONFLICTING_PART)` |
| different `R` for the same `SID` | `Rejected(CONFLICTING_REVISION)` |
| different count/total/hash for the same `SID` | `Rejected(CONFLICTING_PART)` |
| order | any; complete when all unique indices 1..n are present |
| parts | `n ≤ 64`; out-of-range index/count → `TOO_MANY_PARTS` |
| total | `1 … 262,144`; else `OVERSIZE_TOTAL` (checked on the first part) |
| per part | decoded data ≤ 8,192; else `BAD_PART` |
| concurrent assemblies | 8 by default; further new ids → `TOO_MANY_ASSEMBLIES`; a completed one frees the slot |
| age | 10 min default; a part arriving after `now − createdAt > ttl` → `EXPIRED`, assembly dropped, retry allowed |
| completion | total length **and** SHA-256 must match, else `Rejected(BAD_HASH)`; nothing partial is returned |

Not thread-safe by design (one instance per receiving device/dispatcher).

## Failure matrix (all pinned by tests)

| input | `LinkBundleCodec.open` |
|---|---|
| empty / < 10 bytes | `TRUNCATED` |
| > 128 KiB | `TOO_LARGE` |
| declared `N` > 131,062 | `TOO_LARGE` |
| declared `N` longer than buffer | `TRUNCATED` |
| bytes after `10 + N` | `TRAILING_BYTES` |
| magic ≠ `RLYB` | `BAD_MAGIC` |
| envelope version ≠ 1 | `UNSUPPORTED_VERSION` |
| suite ≠ 1 | `UNSUPPORTED_SUITE` |
| wrong desktop private key | `CRYPTO_FAILED` |
| flipped ciphertext/tag byte | `CRYPTO_FAILED` |
| plaintext > 130,998 (provider bug) | `TOO_LARGE` |
| plaintext JSON v ≠ 1 | `UNSUPPORTED_VERSION` |
| any field over bound | `FIELD_TOO_LARGE` |
| bad hex/base64/UUID/trust/relay hint/ordering | `MALFORMED` |
| unknown JSON key | `MALFORMED` |
| `messages`/`history`/`chats`/`media` | `HISTORY_FORBIDDEN` |
| depth > 8 | `MALFORMED` |

| input | `SyncCodec.decode` |
|---|---|
| not `TYPE:SYNC|` | `BAD_PREFIX` |
| body > 256 KiB (chars or UTF-8 bytes) | `TOO_LARGE` |
| `V` ≠ 1 | `UNSUPPORTED_VERSION` |
| kind not in the eight | `UNKNOWN_KIND` |
| duplicate/unknown/missing field, bad SID/O/revision text, bad base64, bad UUID | `MALFORMED` |
| `R` negative | `FIELD_TOO_LARGE` |
| part count/index/total/data over bound | `FIELD_TOO_LARGE` |
| `SENT` `P` not a valid `RelayPayload` | `MALFORMED` |

Encoder-side violations throw `LinkBundleException` / `SyncFormatException` (both
`IllegalArgumentException`), with the field path and the bound in the message. Decoders never throw.

## Design notes

- **New seam `LinkHpke`** (justified): the existing `KeyMaterial.decryptMine` decrypts with the
  identity's current/previous keys. A link bundle is sealed to the **desktop's ephemeral link
  keypair**, which exists before the desktop has an identity and is not in `KeyMaterial`. The
  two-sided `LinkHpke.encryptTo(public, bytes)` / `LinkHpke.decryptWith(private, bytes)` interface is
  the minimum that works for both ends. The phone can implement `encryptTo` by delegating to
  `KeyMaterial.encryptTo` (shape is identical; tested). `:core` main stays free of Tink and Android.
- HPKE ciphertext makes any header/length tamper fail the AEAD tag as well; the structural checks
  are there so hostile input is rejected before allocation/crypto and gets a precise error.
- Canonical JSON writer rather than `org.json` serialisation: byte-stable on the JVM and for a
  Rust/desktop implementation (WP03 recorded that Android and JVM `org.json` order keys differently).
  Receivers must still read by key; the golden pins our writer, not a cross-runtime guarantee.
- No existing file was edited; no public signature changed. `:app:testDebugUnitTest` was therefore
  not required and is not run.

## What a Rust/desktop implementation must reproduce

1. Code: 32 random bytes → 64 lowercase hex; strip `-`/space/`:` and lowercase before use;
   `slot = SHA256(code_text)`; header `x-relay-link-proof: <code_text>`.
2. Seal: HPKE (X25519/HKDF-SHA256/AES-128-GCM) to the desktop's ephemeral public key; no code-derived
   key. Frame `RLYB | 0x01 | 0x01 | u32be len | ciphertext`.
3. Plaintext: the canonical JSON above, exactly the listed keys, no history. Optional null fields
   omitted; arrays always present (possibly empty); settings always all seven fields. If a Rust
   implementation emits a different key order, that is acceptable to the decoder but will not match
   the golden file.
4. SYNC grammar/field order and bounds above; `D` = base64url no padding; `H` = lowercase sha256 hex
   of the whole logical wire body; `T` = its byte length.
5. Reassembly limits: 64 parts, 8 assemblies, 256 KiB total, 8 KiB/part, 10 min TTL, duplicate
   tolerance, revision conflict rejection, hash+length verification before publishing.

## What the server slice must match

- `PUT /v1/link/<slot>` stores opaque bytes, cap **131,072**; the slot key is
  `sha256Hex(canonical code text)`, lowercase hex.
- `GET`/`DELETE` require `x-relay-link-proof` = the 64-hex code; server computes
  `sha256Hex(proof)` and constant-time compares. Missing/malformed → 401, wrong → 404.
  No proof query parameter.
- TTL 10 minutes, one slot per member, lease-then-complete semantics as in the server tests.
- The 16 KiB mailbox body cap is the binding limit for SYNC: a compliant client never sends a
  single-part body whose base64 exceeds 16,384 bytes; the server needs no SYNC-specific parsing.
- `TYPE:SYNC` is only accepted from one's own pubkey; that check belongs to the receiving client
  (the server cannot see the body). The `O:` value is not an authorisation signal (G1).

## Not run

- Real Tink HPKE seal/open on Android (`RelayCrypto`/Tink 1.23) and on the desktop
  (`SecretStore` + Tink 1.24). Tests use a test-only AES-GCM stand-in with the same interface
  contract; the WP01 probe showed JVM Tink 1.24 HPKE works, but this slice did not exercise it.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, any instrumentation, a real phone, a real desktop,
  a QR scan, or the live link-slot Worker. No shared `:app` signature changed.
- Cross-language (Rust) decoding of `linking-codecs.golden`; only the JVM writer is pinned.
- Fuzzing/property testing beyond the explicit hostile vectors; memory/CPU profiling of a
  maximum-size bundle or SYNC flood; concurrent multi-device sync.
- Server integration test against these exact bytes (the server slice is a separate WP05 task).
