# WP09 — shared streaming mailbox prerequisites

Status: **shared transport implemented and locally verified; platform integration and WP09 release gates remain open.** No commit, push, deploy, phone run or live-provider request was performed by this transport task.

## Scope and compatibility

Owned changes only:

- `core/src/main/kotlin/com/relay/app/transport/mailbox/MailboxMedia.kt`
- `core/src/main/kotlin/com/relay/app/transport/mailbox/MailboxAuth.kt`
- `core/src/main/kotlin/com/relay/app/transport/mailbox/MailboxProtocol.kt`
- `core/src/main/kotlin/com/relay/app/transport/Transport.kt`
- new `core/src/test/kotlin/com/relay/app/transport/mailbox/MailboxStreamingTest.kt`
- this evidence file.

Read the media, send-reference and device-auth contracts in `server/cloudflare/PROTOCOL.md` before implementation. No edits to file crypto/codecs, their tests/goldens, server, Android, desktop, incoming classification or `MessageType`. No new dependencies. No `.local`/`.private` inspection. Existing uncommitted work belongs to other agents and was not reverted.

Existing ByteArray media methods and their signatures remain available. Legacy `MailboxMedia.confirm(signer, url): Unit` remains best effort. The default legacy HTTP client now refuses redirects as a security hardening. The original 3-argument `sendBody` and the empty-reference overload produce the original JSONObject construction's exact bytes on the runtime in use. Existing NIP-98 header semantics, including omission of the payload tag for the legacy empty ByteArray, remain intact.

## Exact integration API

```kotlin
val media = MailboxMedia.Client(
    context = MailboxMedia.RequestContext(signer, device),
    // Optional: baseUrl = configured HTTPS origin,
    // Optional: httpClient = platformClient, preserving proxy/Tor/timeouts.
)
val cancel = MailboxMedia.Cancellation()

// Both overloads stream opaque ENCRYPTED bytes. No file encryption is performed here.
val referenceUrl: String = media.upload(ciphertextFile: java.io.File, ciphertextSha256, recipients, cancel)
val source = MailboxMedia.RawSource(length: Long) { freshInputStreamAtOffsetZero() }
val referenceUrl2: String = media.upload(source, ciphertextSha256, recipients, cancel)

// Target must NOT already exist. Parent must be a caller-controlled PRIVATE staging directory.
val storedBytes: Long = media.downloadTo(referenceUrl, ciphertextSha256, stagingFile, maxBytes, cancel)
val confirmation: MailboxMedia.Confirmation = media.confirm(referenceUrl, cancel)

// Callable from another thread / platform cancellation handler:
cancel.cancel()

// Signs a validated lowercase SHA-256, without reading a body:
MailboxAuth.headerWithDigest(signer, method, exactUrlIncludingQuery, ciphertextSha256)

// media is a top-level signed request-body field, NOT part of the encrypted envelope:
MailboxProtocol.sendBody(recipient, persistedDeliveryId, persistedEnvelope, mediaDigests)

// New compatible Transport default; mailbox implementations must override for nonempty refs:
transport.sendWithMedia(recipient, payload, mediaDigests, relayHints)
```

The snippet is an API guide, not literal standalone Kotlin (type annotations label argument types).

- **Signer choice is the caller's responsibility:** Android after migration must inject its phone-only **admin signer**, not the shared identity signer; desktop injects its **enrolled device signer** and its 32-lowercase-hex device id. Explicit `primary` is accepted. `null` preserves legacy no-query requests. Malformed device ids fail before HTTP. The precise normalized outgoing `?device=...` URL is what the NIP-98 `u` tag signs for PUT, GET and DELETE.
- Client snapshots a strict HTTPS origin (no userinfo/path/query/fragment). Download requires the exact canonical origin/path/hash reference, with no sender-supplied query or fragment. Device queries are added locally. All redirects, including same-origin redirects, are disabled. Upload returns the query-free reference for the encrypted message, never the per-device signed URL.
- Stored ciphertext is bounded to **26,214,400 bytes**; download caller bound must be within that cap. Copy buffer is 64 KiB, with at most one logical overflow-probe byte beyond the cap (Okio also has its own small fixed-size read buffer). No new transport path materializes the blob as a ByteArray. HTTP error bodies are bounded to 4 KiB, and error codes are syntax/length validated.
- Raw upload uses `application/octet-stream`, never a photo-specific type. Source must be immutable and replayable: every `open` returns a newly owned stream at zero. Preflight verifies size and SHA-256 before contacting HTTP; each actual request-body write opens, hashes and checks again. Thus normal upload reads the source twice with bounded memory. A mutation during actual upload cannot be reported successful, though already-written bytes cannot be unsent. File/path callers can use `path.toFile()`.
- On an explicit retry, call `upload` again with the same digest/recipients and source. A fresh signature nonce is generated; the body itself is replayable for OkHttp connection retries. This seam does not add its own retry scheduler.
- Cancellation is explicit and thread-safe: closes registered source/input/output streams and cancels the OkHttp call. Connect a coroutine/job/platform cancel callback to `cancel.cancel()` and perform these blocking methods off the UI thread. The source provider must itself support close-unblocks-read semantics. Interrupted/cancelled media operations throw `InterruptedIOException`; cancellation of confirm propagates rather than becoming a success/failure receipt.
- `downloadTo` atomically creates a NEW owner-only `rw-------` staging file using CREATE_NEW/POSIX attributes; unsupported filesystem providers fail closed. Existing files/symlinks are not replaced. All failures remove this operation's partial file. It does **not** publish to Downloads or authenticate/decrypt the inner file format. After return, consume through the separately owned file-crypto API, durably store, then confirm. The caller must keep the parent directory private/trusted and not race another writer against this path. Staging is deliberately non-public even while partial.
- Upload/download HTTP failures throw `MailboxMedia.HttpFailure(status, code, retryable)`. `401 auth_revoked` and `403 unknown_device` remain explicit and non-retryable. Confirm returns `Confirmation.Confirmed` or `Confirmation.Failed(status: Int?, code: String?, retryable: Boolean)`; null status is local I/O/validation failure. Legacy best-effort helper remains unchanged in behavior.
- `sendBody(..., media)` accepts **0–8 distinct lowercase 64-hex** digests. Invalid, duplicate or excess references throw before serialization/signing. Empty omits the field entirely. Persist the **same media list, delivery id and envelope** across retries and include that generated body when signing POST /v1/send.
- `Transport.sendWithMedia(recipient, payload, mediaDigests, relayHints)` is a compatible default: empty refs delegate to existing durable `send`; nonempty refs fail non-retryably until the mailbox platform implements the override. It never silently drops references. Existing overrides of `send` compile unchanged. **Phone and desktop still need to implement this override and persist references in their outboxes.**

## Verification

A first shared-output Gradle attempt hit `NoClassDefFoundError: MailboxProtocol` while another agent was building core. That was a harness/output collision, **not product RED**. Subsequent runs isolate core compilation, JARs and reports with a scratch init script, and only select mailbox tests, not another agent's file tests.

Scratch init script `/home/tadej/.hermes/cache/scratch/wp09-transport.init.gradle`:

```groovy
gradle.beforeProject { p ->
    if (p.path == ':core') {
        p.layout.buildDirectory.set(new File('/home/tadej/.hermes/cache/scratch/wp09-transport-build'))
        p.afterEvaluate {
            p.tasks.withType(Test).configureEach {
                systemProperty 'java.io.tmpdir', '/home/tadej/.hermes/cache/scratch'
            }
        }
    }
}
```

Final command, from `/home/tadej/repos/Relay`:

```sh
JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 ./gradlew --no-daemon --console=plain \
  -I /home/tadej/.hermes/cache/scratch/wp09-transport.init.gradle \
  -Dorg.gradle.java.home=/home/tadej/tools/jdk-21.0.12.1+1 \
  -Dorg.gradle.jvmargs=-Xmx2g \
  :core:test --tests 'com.relay.app.transport.mailbox.*'
```

**BUILD SUCCESSFUL. XML parsed: 43 tests, 0 failures, 0 errors, 0 skipped.** This includes **22 new streaming tests**, plus Auth 4, MediaConfig 3, Protocol 7 and Session 7. All shared main/test Kotlin sources compiled as part of this command, but non-mailbox tests were not executed. Existing file-test Java Thread.id deprecation and Gradle deprecation warnings remain; not introduced/fixed here.

Reports: `/home/tadej/.hermes/cache/scratch/wp09-transport-build/test-results/test/TEST-*.xml`.
Final log: `/home/tadej/.hermes/cache/scratch/wp09-transport-final.log`.

Coverage exercised: exact opaque upload bytes/digest; wrong digest before network; changed source during replay; 25 MiB synthetic source streamed with 64 KiB max reads (no 25 MiB source array); source reopen on request-body replay and explicit retry with new nonce; source closure/cross-thread blocked-read cancellation; exact signed device URL and payload tags; successful owner-only staging; false/unknown Content-Length and oversized responses bounded; wrong digest/disconnection/cancellation partial cleanup; existing-file preservation; foreign/ambiguous URL refusal; explicit confirmation I/O/HTTP errors and revoked status; reference limits/legacy bytes; fail-closed Transport defaults.

Redirect test additionally starts **two real loopback HTTPS servers**, generates a disposable test-only keystore using the JDK keytool, and sends PUT, GET and DELETE through OkHttp to a 307 response pointing at the other origin. The other origin receives zero requests. Trust-all TLS is scoped strictly to this test fixture; production TLS validation is unchanged. Servers stop, connections close, test executor shuts down, and test keystore is removed in `finally`.

### RED / mutation evidence

Initial API-presence tests failed assertions for missing media sendBody overload, precomputed-digest auth and streaming Client before implementation; default Transport seam likewise failed before implementation. These early reflection probes were replaced/augmented with behavioral tests. A blocking-source cancellation behavior test then exposed a real bug: cancelled preflight reads propagated generic IOException. The copy loop now re-checks cancellation when an input read throws; final test passes with InterruptedIOException.

Some behavioral tests first ran green after the seam existed. **RED for those guards was established by deliberate mutation checks, not claimed as first-run TDD:**

| Temporary mutation, fully restored | Targeted result | Scratch log |
|---|---|---|
| Enable redirects on the streaming client | real loopback HTTPS redirect test: 1 test, 1 failure | `wp09-transport-mutation-redirect.log` |
| Omit active I/O cancellation closure registration | cross-thread blocked source test: 1 test, 1 failure | `wp09-transport-mutation-cancel.log` |
| Disable source hash/length fence, bypass media-list validation, corrupt payload tag | 4 selected digest/reference tests, 4 failures | `wp09-transport-mutation-digest.log` |
| Disable download hash/declared-length fence, ignore HTTP status, let default Transport discard refs | 4 selected integrity/outcome/default tests, 4 failures | `wp09-transport-mutation-outcome.log` |

All logs above are under `/home/tadej/.hermes/cache/scratch/`. Final 43-test run occurred **after restoring all mutations**. `git -c core.whitespace=cr-at-eol diff --check` passed before evidence writing.

An initial legacy-byte assertion incorrectly assumed JSONObject key iteration order; it was corrected to compare against the original construction expression rather than imposing a new serialization order. Production legacy serialization was not changed to satisfy the test.

## NOT RUN / remaining work

- Full-core suite execution, Android/desktop builds and their suites, emulator/physical phone flows, packaging, Mac/Windows filesystem behavior, live provider, deploy and row-cost measurement.
- WP09 60 MiB phone↔desktop transfers, 100 MiB physical-phone heap measurement, platform progress/resume DB/UI, safe save/open, actual device-key migration/revocation against a provider.
- No independent review of this transport implementation in this subtask; parent should rerun/review final source.
- Transport treats encryption as opaque. Crypto authentication, part planning and legacy/raw framing acceptance are separately owned; this document does not claim those gates.
- Existing platform send implementations do not yet override `sendWithMedia`; default deliberately refuses nonempty references until wired. This is a shared prerequisite, **not completed WP09**.
