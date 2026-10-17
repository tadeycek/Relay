# Performance investigation — 2026-10-17

Issue #25 remains open. No physical device is connected, so this report cannot establish
which bottleneck dominates on the affected phone or provide end-to-end messaging p50/p95.

## Implemented changes and measured scope

Chats previously loaded/materialized the whole history. They now load the newest 200 rows
in deterministic timestamp/id order. Load older messages adds a page without discarding
the existing reading anchor; incoming updates retain the loaded lower boundary instead
of trimming the message the user is reading. Composite indexes cover conversation and
ordering. Group history resolves each distinct sender once and fetches delivery states
in batches, removing the previous name/state queries for every message. Group refreshes
query only the active group rather than loading all groups. Upload jobs remain separate
from the conversational text dispatcher, which already preserves recipient ordering.

`python3 rebuild/dev/benchmark-history.py` uses 50,000 synthetic messages in in-memory host
SQLite and 31 samples per query. It reads the implemented window SQL from HistoryWindow.kt.
Measured in this session:

| Query/materialization | Rows | p50 | p95 |
| --- | ---: | ---: | ---: |
| Previous full-history shape | 50,000 | 90.913 ms | 126.633 ms |
| Initial bounded window | 200 | 0.401 ms | 0.501 ms |

The script also checks equal-timestamp ordering, incoming-message anchor retention and
older-page expansion. These are host database measurements, not Android SQLCipher,
Compose frame times, cold/warm startup, send-button timing or relay latency. They do not
establish a phone-wide improvement factor. JVM/Android build results and pending device
checks are in VERIFICATION.md.

## Delivery and the removed background notice

The continuous foreground connection service is disabled and its legacy notification
and channel are removed at app startup. It cannot be re-enabled through old preferences,
boot, app backgrounding or the removed settings toggle. The process still receives while
alive; ordinary WorkManager checks have a 15-minute minimum and can run much later under
Doze, battery restrictions or force-stop. There is no promise of instant closed-app
notifications. This follows the explicit request to remove the running-in-background
notice, rather than trying to run an invisible foreground service. Android requires a
notification for a foreground service:
[Android foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/launch).
A future push bridge would require a separate, explicit architecture/hosting decision.

## Free hosting comparison (official documentation checked this date)

| Option | Retention and reliability | Privacy/operation/migration |
| --- | --- | --- |
| Existing public Nostr relays | Kind-1059 acceptance, retention and before/after latency still require real tests; no retention SLA is assumed | Smallest change. Retain recipient-advertised inbox routing. Relay sees connection metadata and encrypted events. No provisioning. |
| Own strfry relay on Oracle Always Free | You configure storage and retention; capacity may be unavailable and idle compute may be reclaimed | Existing transport can be retained, but requires TLS, updates, access policy, backups and monitoring. No anonymity from the operator. Not provisioned. |
| Cloudflare Workers + SQLite Durable Objects | Free limits currently include 100,000 requests/day, 13,000 GB-s/day and 5 GB total storage; exhaustion fails operations | A custom authenticated encrypted mailbox/adapter and new client integration would be necessary. WebSocket hibernation avoids unnecessary duration use. More migration work; not a drop-in Nostr relay. Not provisioned. |
| Render Free | Idle web services spin down; ephemeral local storage is unsuitable for durable offline mailboxes without separate persistence | Cold starts and storage constraints make this a poor default for low-latency durable messaging. Not provisioned. |

Recommendation: keep the current transport and measure on the affected phone before
changing hosting. The confirmed unbounded local query shape can be fixed without a new
service. The creator-forwarded group hop and Android wake-up constraints remain separate
from relay selection. Do not select a faster relay by silently ignoring the recipient's
advertised DM inbox. This is an engineering recommendation from the above constraints,
not a measured relay winner.

Sources:
- [Cloudflare pricing and storage limits](https://developers.cloudflare.com/durable-objects/platform/pricing/)
- [Cloudflare WebSocket hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- [Oracle Always Free capacity and reclamation](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
- [Render Free limits](https://render.com/docs/free)
- [NIP-17 publishing and inbox routing](https://nips.nostr.com/17)

## Outstanding measurements

On a release/profileable build and physical phones: cold/warm entry, long-history frames,
tap → local durable acceptance → visible bubble → dispatcher → relay ACK → recipient
render, with p50/p95. Compare direct/group, creator online/offline, Wi-Fi/mobile, slow or
offline recipient and reconnects. Use random disposable test identities for relay tests;
verify actual kind-1059 delivery and offline retention after 1h/24h/72h/7d, rate/auth
restrictions and Tor behavior. No such device/network/long-duration results are claimed.
