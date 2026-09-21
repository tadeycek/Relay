# 05. Optional Tor mode

Goal: let users hide their IP address from Nostr relays (and Blossom hosts) with a setting, explained honestly in the
UI. Off by default. This is **not** an in-app VPN.

## Why not an in-app VPN

Android's `VpnService` only lets an app create a local tunnel. Traffic still has to leave through a server that
forwards it, and that operator sees your real IP and destinations. That trades "relays see my IP" for "the VPN
operator sees it", and it means running a server again. A commercial VPN chosen by the user works with no code from
us. Tor is the option that removes the single trusted party.

## How the toggle works

Two implementations, built in this order:

| | Phase 1: external Orbot | Phase 2: embedded Tor |
|---|---|---|
| User effort | Install and start Orbot | None |
| App size | Unchanged | Several MB more |
| Code | Route relay/HTTP connections via SOCKS5 at `127.0.0.1:9050`, detect whether it is up | Start/stop a bundled Tor, wait for bootstrap, expose SOCKS port |
| Maintenance | Orbot updates itself | We ship and update Tor with the app |

Library choice for embedding (tor-android, Guardian Project components, or Arti) is **not researched yet** (see 08).

## Rules the implementation must follow

1. **Fail closed.** If Tor mode is on and Tor is not connected, do not send and do not fall back to a direct
   connection unless the user picked "Fall back to direct" explicitly. Default: wait and show status.
2. **No leaks.** Every relay WebSocket, Blossom upload/download, and NIP-11 relay-info fetch goes through the proxy.
   Use SOCKS5 with **remote DNS** so hostnames are resolved by Tor, not by the phone.
3. **Separate relay list** for Tor mode, with Tor-friendly and `.onion` relays. Some relays block Tor exit nodes.
4. **Map tiles** (OSMDroid) fetch from OSM servers directly and would leak the IP; decide whether to proxy them in Tor
   mode or warn (see 08).
5. **Push path**: FCM/UnifiedPush endpoints see the device IP. In Tor mode disable push-based wake-ups or say so in the
   UI. The foreground-service connection can go through Tor.
6. Transport must accept an injectable `Proxy`/socket factory from day one, even before Tor exists.

## Settings UI

- Switch: **Hide my IP from relays (Tor)**, off by default.
- Status line under it: `Off` / `Connecting to Tor...` / `Connected via Tor` / `Tor unavailable, messages are waiting`.
- Choice when Tor fails: **Wait for Tor** (default) or **Fall back to direct connection**.
- "What this does" expandable panel with the text below.

### Explanation text (draft)

**What you gain**
- Relays can't see your IP address or approximate location.
- No single party sees both who you are and what you connect to.
- Someone watching your network can't easily tell which relays you use.

**What you give up**
- Messages are slower, and the first connection takes several seconds.
- More battery and data use.
- Some relays block Tor, so fewer relays are available.
- Push notifications may be unavailable or can reveal your IP to the push service.
- Your friend's IP is still visible to relays unless they use Tor too.
- Your mobile carrier can still see that you're using Tor.
- It doesn't protect you if your phone is compromised.

## Limits worth stating honestly
- Timing correlation by an observer who sees both ends is not fully prevented by Tor.
- Tor hides the IP, not the fact that a locked message for a given public key exists.
- Embedded Tor increases APK size and startup time; battery cost of a permanent Tor circuit needs measuring.
