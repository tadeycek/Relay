# WP09 step 1 — `:core` file codec and part crypto (historical evidence)

> **SUPERSEDED / reviewer found blockers.** This is the original implementation snapshot, not current
> acceptance evidence. Its JPEG wire, zero-byte rejection and provider-independent memory claims were incorrect.
> See [WP09-core-fixes.md](WP09-core-fixes.md) for the actual raw format, mandatory digest/id binding, staging
> design and provider measurements. Android peak heap remains NOT RUN.

Branch `feat/linux-port`, base HEAD d8759d7. Nothing committed, pushed or deployed. Scope: `core/.../files/**` (new),
`core/src/test/.../files/**` (new), `golden/files-codecs.golden` (new), two minimal edits in
`05-protocol-changes.md` §2 and `03-decisions.md` LP-D07 (numbers only).

## Commands and counts

```
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1
./gradlew --no-daemon --console=plain -Dorg.gradle.java.home=$JAVA_HOME -Dorg.gradle.jvmargs=-Xmx2g :core:test --rerun-tasks
RELAY_GOLDEN_RECORD=1 ... :core:test --tests com.relay.app.files.FilesGoldenTest      # records files-codecs.golden once
```
Counts parsed from `core/build/test-results/test/*.xml` (44 files): **314 tests, 0 failures, 0 errors, 0 skipped**
= the existing 252 + 62 new (FileBodyTest 20, FilePartCryptoTest 20, FileSafetyTest 11, FilePartPlanTest 10,
FilesGoldenTest 1). `body-codecs.golden` and `linking-codecs.golden` are untouched and still pass.
Tests were written before the implementation; the first run found 3 real defects (a test expectation, a too-lenient APP0 check in the decryptor, a wrong assumption about
`plainSize` gaps), all fixed before the green run.

## The spec conflict: arithmetic and decision

Server cap: `maxMediaBytes: 25 * MIB + 64` (`server/cloudflare/src/protocol.ts:111`) = **26,214,464** bytes, applied to
the STORED blob. Stored blob for a part of `n` plaintext bytes:

```
payload = 12 (IV) + n + 16 (tag)                                   = n + 28
stored  = MediaContainer.wrappedSize(payload)
        = 2 (SOI) + 18 (APP0) + 4*ceil(payload/65533) (COM headers) + payload + 2 (EOI)
```
- Largest `n` with `stored ≤ 26,214,464` is **26,212,814**; its stored blob is **exactly 26,214,464** (n+1 gives 26,214,465).
- A literal 25 MiB (26,214,400) plaintext part would store as 26,216,054 bytes, over the cap by 1,590. Overhead of a full part: 1,650 bytes
  = 28 crypto + 1,622 container (400 COM segments × 4 + 22 fixed).
- 4 × 26,212,814 = 104,851,256, **6,344 bytes short of 100 MiB** (104,857,600). So 4 parts cannot carry 100 MiB.

**Decision:** keep the 100 MiB file limit and the 25 MiB + 64 server cap unchanged; `PART_PLAIN_BYTES = 26,212,814`;
`MAX_PARTS = 5` (5 digests ≤ the 8-digest cap on `POST /v1/send`); `MAX_SIZE = 104,857,600`. A 100 MiB file is four full parts
plus a 6,344-byte fifth part. Spec edits made: 05 §2 (size paragraph, validation rule), 03 LP-D07 (part size, 5 objects).

Side finding (not changed, outside scope): the existing `TYPE:MEDIA` limit `MediaBody.MAX_MEDIA_BYTES = 25 MiB` plaintext
would store 1,590 bytes over the server cap (the +64 slack is too small). Only a ≈25 MiB image/video could hit it.

## Exact wire layout (desktop and phone implementers)

`FileBody`, travels inside `TYPE:ENC`, ASCII only (0x21–0x7e), at most 16,384 characters, fields separated by `|`, each
`KEY:value` (the first `:` splits; keys are case-sensitive; no whitespace; no trailing separator). Order is free on
parse, canonical on encode:

```
TYPE:FILE|V:1|NAME64:<b64 utf-8 name>|MIME:<type/subtype>|SIZE:<decimal>|KEY:<b64 32 bytes>|PARTS:<1..5>
  |P1:<sha256 hex lowercase>,<stored bytes>|P2:…|…|URLBASE:<https://host/v1/media/>[|CAP64:<b64 utf-8 caption or TYPE:REPLY body>]
```
- Base64 is standard, padded, **canonical** (re-encoding must reproduce the input; otherwise rejected).
- `SIZE` = `[1-9][0-9]{0,9}`, ≤ 104,857,600. `PARTS` = single digit 1–5 equal to `ceil(SIZE / 26,212,814)`. `Pi` for i = 1..PARTS exactly.
- Part i (0-based) has plaintext length 26,212,814 except the last (the remainder); its `<stored bytes>` must equal
  `storedSize(len)` above and be ≤ 26,214,464; hashes are distinct.
- Name: decoded UTF-8 (strict) ≤ 255 bytes, else reject; then sanitised (strip control/format/line-separator, `/ \ | < > : " ? *`,
  trim edge spaces and trailing dots, `_` before a leading dot or a Windows device name, ≤ 255 bytes keeping the extension,
  empty → `file`). MIME `[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*`, ≤ 100 chars, lowercased, else `application/octet-stream`.
- URLBASE: https, no credentials/query/fragment, ends in `/`, accepted by the injected "is ours" predicate
  (`FileBody.urlBasePredicate(MailboxMedia::isOurs)`). Part URL = `URLBASE + sha256Hex`.
- CAP64: ≤ 4,096 chars; plain captions are sanitised exactly like `MediaBody.sanitizeCaption` (≤ 200); a `TYPE:REPLY|` body
  must parse as a reply with text ≤ 200 or the whole file body is rejected.

Wire example (30,000,000-byte file → 2 parts):
```
TYPE:FILE|V:1|NAME64:cmVwb3J0LnBkZg==|MIME:application/pdf|SIZE:30000000|KEY:AQgPFh0kKzI5QEdOVVxjanF4f4aNlJuiqbC3vsXM09o=|PARTS:2|P1:0101010101010101010101010101010101010101010101010101010101010101,26214464|P2:1212121212121212121212121212121212121212121212121212121212121212,3787468|URLBASE:https://relay.example/v1/media/
```
(exact P2 length is `storedSize(30,000,000 − 26,212,814)`; the full strings are pinned in `files-codecs.golden`, lines `encode.*`.)

Rejection reasons are typed (`FileBodyReject`); parse never throws (3,000 mutated inputs plus 10 MB garbage in tests).

## Stored-blob layout (what is PUT to `/v1/media/<sha>` with `Content-Type: image/jpeg`)

```
FF D8 | FF E0 00 10 'JFIF' 00 01 01 00 00 01 00 01 00 00 | { FF FE <u16 len+2> <≤65,533 payload bytes> }+ | FF D9
payload (concatenated COM data) = IV[12] || AES-256-GCM ciphertext[n] || tag[16]
```
Key = file `KEY` (same for all parts); IV = 12 fresh random bytes **per part** (never derived from the index); no AAD; tag 128 bit.
Identical to what `MediaContainer.wrap` + `MediaCrypto` produce (`MediaCrypto.decrypt(MediaContainer.unwrap(stored), key)` decrypts a part —
tested), so the unmodified server accepts it. SHA-256 and byte length in the body are of this stored blob (= the object's address).

Authentication unit: **a part**. GCM verifies only at the tag, so the decryptor holds the whole part, checks the declared
SHA-256 first, parses the container strictly (exact JFIF header, segment bounds, exact payload length), decrypts, and only then
writes plaintext to the caller's sink. Any failure (BAD_SIZE, HASH_MISMATCH, BAD_CONTAINER, AUTH_FAILED, IO_ERROR) writes nothing;
the part is retried whole. Verified parts stay valid. Reordering/swapping parts fails the SHA-256 check against the body; two
parts of equal plaintext still differ (different IVs) so a body cannot legitimately repeat a hash.
The 05 gate note about AAD binding file id/index/count (10-readiness-gates G-files) is NOT implemented: this task fixed the format to the
existing container with no AAD; the body hashes bind order and count inside the E2E envelope. Raise if the gate must be literal.

## Memory model (measured in `FilePartCryptoTest`, printed lines `ENCRYPTOR ALLOC` / `DECRYPTOR ALLOC`)

- **Encryptor**: pure streaming (GCM encrypt is a stream; the tag comes last). Holds two 64 KiB buffers + framing. Thread allocation for
  a full 26,212,814-byte part: **216,768 bytes** (1 MB part: 164,184), i.e. independent of part size; documented ceiling
  `MAX_ALLOCATION_BYTES = 512 KiB`, asserted. A counting sink saw writes ≤ 64 KiB + 64 and the input saw reads ≤ 64 KiB.
- **Decryptor**: stored blob (26,214,464) + plaintext (26,212,814) + scratch: measured **52,442,960 bytes** total allocation for a full
  part (stored + plain + 15,682 bytes); ceiling `stored + plain + 256 KiB`, asserted. Peak is about 2 × part (≈ 50 MiB) before the sink gets
  the plaintext in ≤ 64 KiB writes; input sizes are validated before allocation, so a hostile body cannot force more than that.
  This is just under the AC3 budget of 64 MiB for a decrypt: the phone must not hold anything else large at the same time
  (download straight into the decryptor's `InputStream`; do not buffer the response separately).
- Neither side ever holds more than one part; file size does not appear in either bound.

## NOT RUN

- No phone: no Android build, no 60/100 MiB real transfer, no heap measurement on a device (WP09-AC2, AC3 on device, AC4, AC5, AC6).
- No live server: no `PUT /v1/media` of a real 26 MiB part, no `POST /v1/send` with 5 digests, no `server/cloudflare` multi-part test.
- No desktop code, no UI, no resume/DB state (steps 2–5 of WP09).
- Not exercised: concurrent use of one encryptor across threads (the IV set is synchronised but untested); 100 MiB end-to-end in one test
  (parts are tested one at a time at full size).
