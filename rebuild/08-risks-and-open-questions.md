# 08. Risks and open questions

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Free public relays delete or drop DMs too soon | Medium | High (breaks offline delivery) | Phase 0 retention test; write to several relays; allow user-chosen or private relay; escape hatch to own relay |
| Closed-app delivery unreliable (Doze, OEM task killers) | High | High | Foreground service + polling fallback; measure on several phones; optional notification bridge |
| rust-nostr Kotlin bindings are alpha and change | Medium | Medium | Pin a version; wrap behind `Transport`; keep the option to use a small pure-Kotlin implementation of NIP-44/59 |
| Battery drain from a permanent connection | Medium | Medium | Efficient subscriptions (`since` filters), one socket per relay, backoff; expose a setting |
| Metadata exposure to relays (recipient key, time, size, IP) | Certain | Medium | NIP-17 sender hiding, timestamp fuzz, optional Tor, honest UI text |
| No forward secrecy (stolen `nsec` or HPKE key reveals history) | Low | High | Tink key rotation (existing); later Double Ratchet; secure key storage; app lock |
| Users lose their phone and the `nsec` | Medium | High | Optional encrypted key backup/export; explain re-pairing |
| Existing users' history and contacts break in migration | Medium | Medium | Careful DB v10 migration; test from real v9 data; keep legacy contacts readable |
| Public relays flooded with spam to a known `npub` | Low | Low to Medium | Only accept from paired contacts; message-requests list; read-only from unknown |
| Legal/ToS issues with public relays or media hosts | Low | Low | Use reputable hosts; document data handling |
| Scope creep (Iroh, Tor embed, ratchet, push bridge) | High | Medium | Phases above; each extra is an explicit gate |
| Nostr key material leaks through Android auto-backup (`allowBackup="true"`) | Medium | High | Verify `backup_rules.xml` and `data_extraction_rules.xml`; exclude key prefs and DB passphrase |

## Open decisions

1. **Inner Tink layer: keep or drop?** Recommendation: keep for v1 (reuses rotation and signing), hide behind an
   interface. Revisit when considering a ratchet.
2. **Wake-up strategy.** Recommendation: foreground service first, polling fallback, bridge only if measurements
   demand it. Decide after Phase 4.
3. **Media.** Ship text/location first and add Blossom later, or include media in v1? Which Blossom servers?
4. **Nostr identity vs. Tink keys:** derive one from the other, or independent keys? Independent is simpler and
   safer; derived keys would simplify backup. Recommendation: independent.
5. **Map tiles in Tor mode:** proxy OSM tile requests through Tor, use a Tor-friendly tile source, or warn that tiles
   leak the IP.
6. **Optional SMS invite:** send an invite through the standard `ACTION_SENDTO` intent (no permission, works with any
   SMS app)? Recommendation: yes, it is tiny and keeps the "reach someone by number" spirit.
7. **Live location cadence** over public relays and whether to add a P2P fast path later.
8. **Legacy behaviour:** how long to keep displaying old SMS-based conversations from the local database.

## Things to verify before relying on them (from research labelled [knowledge] or [open])

- Android foreground service type for a messaging app and the exact permission name on Android 14+/15+/16.
- NIP-40 `expiration` tag behaviour on gift wraps and which relays honour it.
- Public relay policies for kind 1059, kind 10050, size limits and retention.
- Actual APK size and ABI coverage of `nostr-sdk-jvm` (native libs).
- Which library to embed for Tor in 2026 (tor-android / Guardian Project / Arti) and its battery cost.
- Blossom server availability, limits and retention.
- Data-SMS carrier support and segment size, only if the SMS fallback is ever revived.
- Current status of Nostr push notification proposals (issue #257, PR #2194) in case a bridge is needed.

## Alternatives if Nostr fails the Phase 0 tests

1. **Own tiny relay + FCM/UnifiedPush** (03, option 6): reliable, needs one small service (Supabase could host it).
2. **Email transport, Delta Chat model** (03, option 8): no server of our own, push comes from the mail provider.
3. **Data SMS** as an offline-only, text-only fallback (03, option 2).
4. **Matrix or XMPP** public servers (03, option 9).

## Status after the build (added at the end of the rebuild)

Resolved by building: foreground service type/permission (confirmed in the SDK 36 `android.jar`); the Kotlin API of
`nostr-sdk-kmp-android:0.44.8` covers everything used (NIP-17 wrap/unwrap, kind 10050, throwaway-key signing for
Blossom auth, SOCKS proxy mode); `androidx.work` fits as the safety-net poller.

Still open and now blocking a release, all needing a device or network: relay retention, foreground-service
reliability and battery, real Blossom server behaviour, whether the SDK resolves DNS through the Tor proxy, and the
`BOOT_COMPLETED` foreground-service start on Android 15+. See `PROGRESS.md` for the ordered next steps.
