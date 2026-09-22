package com.relay.app.media

/**
 * Starter Blossom media servers, tried in order — the first to accept an upload wins. Only their
 * existence as public Blossom servers was seen in research; their upload limits, authentication or
 * payment requirements and retention are **unverified** and must be checked before relying on them
 * (see rebuild/08-risks-and-open-questions.md). Uploads are always of end-to-end encrypted blobs
 * wrapped to look like a real image file (see MediaContainer), so a server learns only size and timing.
 *
 * blossom.nostr.build and blossom.band are confirmed (on a real device) to actually decode uploads —
 * they run an image-processing pipeline (thumbnails, EXIF stripping) that fails on the wrapped
 * ciphertext with a 500, since it is not a real, decodable photo. cdn.satellite.earth is listed as a
 * thin Blossom gateway over Cloudflare R2 object storage rather than an image CDN, so it is a better
 * architectural fit, but this has **not** been confirmed to work — it may also require a funded
 * account for real storage. Kept last so the other two are still tried first.
 */
object DefaultBlossomServers {
    val LIST = listOf(
        "https://blossom.nostr.build",
        "https://blossom.band",
        "https://cdn.satellite.earth",
    )
}
