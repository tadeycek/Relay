# Relay Linux 0.1.0 development package — NOT A RELEASE

Two local x86_64 development artifacts were built from a copied, pre-existing Compose app image. They have **not** been published. The image predates WP07's pending blocker fixes and WP08 media implementation. No version was bumped.

- `.deb`: `relay_0.1.0_amd64.deb`, 93,121,024 bytes, SHA-256 `1f34a36fb7fcd9171692e943e565c47e561a839d854a6b46`.
- AppImage: `Relay-0.1.0-x86_64.AppImage`, 100,981,240 bytes, SHA-256 `1450dbb19c79a491f31b763a34f6dbdcb6b6ddd0340b0e80659fcd095f2122c9`.
- Local files: `/home/tadej/.hermes/cache/scratch/relay-wp10-output/`; `SHA256SUMS` lives beside the binaries. These are scratch artifacts, not release assets.

**Verified locally:** checksums; `.deb` metadata/listing and an extracted-tree launcher smoke (not installation); AppImage FUSE and extract-and-run on the Ubuntu host; extract-and-run in a Fedora 41 container with GUI libraries and forwarded authenticated X11. All launcher smoke processes were deliberately stopped by a 15–20 second timeout after no fatal exception (Skiko GL-context fallback logged); no full UI interaction was checked. Only the linux-x86-64 nostr native survived filtering. The custom icon is original WP10 artwork; no Android icon was copied.

**Not tested / not cleared:** clean Ubuntu 24.04 VM install/upgrade/removal and preserved user data; full non-Debian desktop distribution outside a Fedora container; real phone, macOS, provider calls, audio and microphone, packaged WP07 fixes/WP08 functionality, FUSE on a clean system, glibc minimum, native Corresponding Source offers and complete third-party notices. Native distribution licences (including Skiko/Skia, SQLite Multiple Ciphers, OpenJDK and AppImage runtime/libfuse) need legal/provenance review. **Do not distribute these artifacts as a release.**
