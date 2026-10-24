/**
 * Wire protocol v1 shared by the Worker router and the mailbox Durable Object. PROTOCOL.md is the
 * human-readable contract; this file is the source of truth for limits and error codes.
 */

export const PROTOCOL_VERSION = 1;

export type ErrorCode =
  | "bad_request"
  | "not_found"
  | "method_not_allowed"
  | "upgrade_required"
  | "auth_missing"
  | "auth_invalid"
  | "auth_expired"
  | "auth_replayed"
  | "auth_revoked"
  | "already_migrated"
  | "admin_key_in_use"
  | "auth_key_in_use"
  | "not_allowed"
  | "request_too_large"
  | "too_many_deliveries"
  | "too_many_media"
  | "media_unavailable"
  | "envelope_too_large"
  | "unknown_recipient"
  | "id_conflict"
  | "recipient_full"
  | "mailbox_full"
  | "rate_limited"
  | "forbidden"
  | "unknown_device"
  | "not_primary"
  | "device_limit"
  | "link_busy"
  | "sync_conflict"
  | "sync_limit"
  | "room_exists"
  | "room_full"
  | "session_limit"
  | "track_exists"
  | "not_configured"
  | "upstream_failed"
  | "storage_error";

/** Per-delivery and request limits. Defaults are the pilot budgets from the plan; tests override them. */
export interface Limits {
  maxEnvelopeBytes: number;
  maxPendingBytesPerRecipient: number;
  maxPendingBytesTotal: number;
  maxPageBytes: number;
  maxPageMessages: number;
  maxDeliveriesPerRequest: number;
  maxRequestBytes: number;
  maxSocketsPerIdentity: number;
  sessionTtlMs: number;
  /** Waiting messages older than this are dropped: the app refuses anything older than 7 days anyway. */
  pendingLifetimeMs: number;
  maxSignalBytes: number;
  signalsPerMinute: number;
  maxMediaBytes: number;
  maxMediaRecipients: number;
  /** Blob digests one send request may attach (WP04-AC4): a 100 MiB file is at most 4 parts. */
  maxMediaRefs: number;
  mediaLifetimeMs: number;
  mediaChunkBytes: number;
  /** Pilot safety net: at most this many people, founders included, can be admitted by pairing. */
  maxMembers: number;
  inviteMaxTtlMs: number;
  maxInvitesPerMember: number;
  /** Linked devices besides the primary (WP04). */
  maxLinkedDevices: number;
  /** A linked device silent for longer than this stops holding the queue and media (WP04, LP-D02). */
  dormantAfterMs: number;
  /** The sealed link bundle: identity keys, contacts, groups, settings. No history (LP-D06). */
  maxLinkBytes: number;
  linkTtlMs: number;
  /** How long a GET protects the bundle for a retry before it counts as abandoned (not consumed). */
  linkLeaseMs: number;
  /** Guesses per slot and per client bucket per minute; in memory, best effort (WP05/G1). */
  linkAttemptsPerMinute: number;
  /** Decoded sync body; base64 of this still fits the existing 16 KiB envelope bound. */
  maxSyncBodyBytes: number;
  maxSyncParts: number;
  /** Unfinished assemblies one identity may have at a time. */
  maxSyncAssemblies: number;
  syncTtlMs: number;
  /** Retained events and bytes per identity, so the channel can never grow without bound. */
  maxSyncEvents: number;
  maxSyncEventBytes: number;
  maxSyncPageEvents: number;
  maxSyncPageBytes: number;
  /** Lifetime asked of the TURN provider; never above 600 s (WP11, G4). */
  callIceTtlS: number;
  /** A caller's minted credentials are reused for this long, so retries and glare do not each hit the provider. */
  callIceCacheMs: number;
  callIcePerMinute: number;
  callIceTimeoutMs: number;
  maxCallIceResponseBytes: number;
  /** Urgent (call-invite) wakes: one per recipient per interval, plus per-sender and server-wide budgets. */
  urgentRecipientIntervalMs: number;
  urgentPerSenderPerMinute: number;
  urgentGlobalPerMinute: number;
  /** WP15 group calls: at most 17 participants (the largest Relay group). */
  maxCallParticipants: number;
  /** A dropped socket keeps its pid for this long, so a client can rejoin without appearing as a new participant. */
  callReconnectGraceMs: number;
  callJoinsPerMinute: number;
  callSfuPerMinute: number;
  /** A rejoin with a pid still calls the Mailbox for revocation/membership; this is how often its socket heartbeats do too. */
  callHeartbeatRecheckMs: number;
  /** An unjoined room is wiped after this long, so an abandoned call start does not hold a DO row forever. */
  callUnjoinedTtlMs: number;
  callCreatesPerMinute: number;
  /** Create/end bodies are tiny; SFU bodies carry bounded SDP and at most 64 track descriptors. */
  maxCallRequestBytes: number;
  maxSfuRequestBytes: number;
  maxSfuResponseBytes: number;
  sfuTimeoutMs: number;
  maxCallTracksPerRequest: number;
  maxCallSdpBytes: number;
}

const KIB = 1024;
const MIB = 1024 * KIB;
const DAY = 24 * 60 * 60 * 1000;

export const DEFAULT_LIMITS: Limits = {
  maxEnvelopeBytes: 128 * KIB,
  maxPendingBytesPerRecipient: 32 * MIB,
  maxPendingBytesTotal: 256 * MIB,
  maxPageBytes: 1 * MIB,
  maxPageMessages: 100,
  // Ten-user pilot: one sender fans out to at most nine others; leave headroom.
  maxDeliveriesPerRequest: 16,
  // Sixteen maximal envelopes plus JSON framing.
  maxRequestBytes: 16 * 128 * KIB + 64 * KIB,
  maxSocketsPerIdentity: 4,
  sessionTtlMs: 60 * 60 * 1000,
  pendingLifetimeMs: 8 * DAY,
  // Typing and presence: a few hundred bytes of gift wrap each; a busy group chat stays well under the rate.
  maxSignalBytes: 16 * KIB,
  signalsPerMinute: 120,
  // The app's 25 MiB media limit plus the AES-GCM nonce and tag.
  maxMediaBytes: 25 * MIB + 64,
  // A group has at most sixteen members besides the sender.
  maxMediaRecipients: 16,
  maxMediaRefs: 8,
  mediaLifetimeMs: 8 * DAY,
  // Well under the 2 MB a single SQLite value may hold in a Durable Object.
  mediaChunkBytes: 1 * MIB,
  maxMembers: 100,
  // The app's QR invitations live 30 minutes.
  inviteMaxTtlMs: 60 * 60 * 1000,
  maxInvitesPerMember: 20,
  maxLinkedDevices: 3,
  dormantAfterMs: 72 * 60 * 60 * 1000,
  // A sealed bundle of keys, contacts, groups and settings (LP-D06); no message history.
  maxLinkBytes: 128 * KIB,
  linkTtlMs: 10 * 60 * 1000,
  linkLeaseMs: 60 * 1000,
  linkAttemptsPerMinute: 10,
  // 12 KiB decoded is at most 16 KiB base64, the envelope bound the sync body travels under.
  maxSyncBodyBytes: 12 * KIB,
  maxSyncParts: 8,
  maxSyncAssemblies: 4,
  // Self-sync is operational state, not a mailbox: a day is long enough for an offline computer.
  syncTtlMs: 24 * 60 * 60 * 1000,
  maxSyncEvents: 64,
  maxSyncEventBytes: 256 * KIB,
  maxSyncPageEvents: 8,
  maxSyncPageBytes: 64 * KIB,
  // WP11: 10 minutes, the value the WP02 live TURN run used. A longer call restarts ICE with fresh credentials.
  callIceTtlS: 600,
  callIceCacheMs: 60 * 1000,
  callIcePerMinute: 6,
  callIceTimeoutMs: 5000,
  maxCallIceResponseBytes: 8 * KIB,
  urgentRecipientIntervalMs: 3000,
  urgentPerSenderPerMinute: 6,
  urgentGlobalPerMinute: 60,
  maxCallParticipants: 17,
  callReconnectGraceMs: 30 * 1000,
  callJoinsPerMinute: 60,
  callSfuPerMinute: 120,
  // Revoked-device trust must end with a closed socket; one Mailbox check per socket per 12 s is cheap.
  callHeartbeatRecheckMs: 12 * 1000,
  // Abandoned call starts: an unjoined room is wiped after 12 minutes (the client's "voice active" banner is 10).
  callUnjoinedTtlMs: 12 * 60 * 1000,
  callCreatesPerMinute: 30,
  maxCallRequestBytes: 8 * KIB,
  maxSfuRequestBytes: 64 * KIB,
  maxSfuResponseBytes: 256 * KIB,
  sfuTimeoutMs: 15000,
  maxCallTracksPerRequest: 64,
  maxCallSdpBytes: 16 * KIB,
};

/** Optional numeric overrides from Worker vars (strings), e.g. small caps in tests. */
export function limitsFrom(env: Partial<Record<string, unknown>>): Limits {
  const pick = (name: string, fallback: number): number => {
    const raw = env[name];
    if (typeof raw !== "string" || raw.trim() === "") return fallback;
    const value = Number(raw);
    return Number.isSafeInteger(value) && value > 0 ? value : fallback;
  };
  return {
    maxEnvelopeBytes: pick("MAX_ENVELOPE_BYTES", DEFAULT_LIMITS.maxEnvelopeBytes),
    maxPendingBytesPerRecipient: pick("MAX_PENDING_BYTES_PER_RECIPIENT", DEFAULT_LIMITS.maxPendingBytesPerRecipient),
    maxPendingBytesTotal: pick("MAX_PENDING_BYTES_TOTAL", DEFAULT_LIMITS.maxPendingBytesTotal),
    maxPageBytes: pick("MAX_PAGE_BYTES", DEFAULT_LIMITS.maxPageBytes),
    maxPageMessages: pick("MAX_PAGE_MESSAGES", DEFAULT_LIMITS.maxPageMessages),
    maxDeliveriesPerRequest: pick("MAX_DELIVERIES_PER_REQUEST", DEFAULT_LIMITS.maxDeliveriesPerRequest),
    maxRequestBytes: pick("MAX_REQUEST_BYTES", DEFAULT_LIMITS.maxRequestBytes),
    maxSocketsPerIdentity: pick("MAX_SOCKETS_PER_IDENTITY", DEFAULT_LIMITS.maxSocketsPerIdentity),
    sessionTtlMs: pick("SESSION_TTL_MS", DEFAULT_LIMITS.sessionTtlMs),
    pendingLifetimeMs: pick("PENDING_LIFETIME_MS", DEFAULT_LIMITS.pendingLifetimeMs),
    maxSignalBytes: pick("MAX_SIGNAL_BYTES", DEFAULT_LIMITS.maxSignalBytes),
    signalsPerMinute: pick("SIGNALS_PER_MINUTE", DEFAULT_LIMITS.signalsPerMinute),
    maxMediaBytes: pick("MAX_MEDIA_BYTES", DEFAULT_LIMITS.maxMediaBytes),
    maxMediaRecipients: pick("MAX_MEDIA_RECIPIENTS", DEFAULT_LIMITS.maxMediaRecipients),
    maxMediaRefs: pick("MAX_MEDIA_REFS", DEFAULT_LIMITS.maxMediaRefs),
    mediaLifetimeMs: pick("MEDIA_LIFETIME_MS", DEFAULT_LIMITS.mediaLifetimeMs),
    mediaChunkBytes: pick("MEDIA_CHUNK_BYTES", DEFAULT_LIMITS.mediaChunkBytes),
    maxMembers: pick("MAX_MEMBERS", DEFAULT_LIMITS.maxMembers),
    inviteMaxTtlMs: pick("INVITE_MAX_TTL_MS", DEFAULT_LIMITS.inviteMaxTtlMs),
    maxInvitesPerMember: pick("MAX_INVITES_PER_MEMBER", DEFAULT_LIMITS.maxInvitesPerMember),
    maxLinkedDevices: pick("MAX_LINKED_DEVICES", DEFAULT_LIMITS.maxLinkedDevices),
    dormantAfterMs: pick("DORMANT_AFTER_MS", DEFAULT_LIMITS.dormantAfterMs),
    maxLinkBytes: pick("MAX_LINK_BYTES", DEFAULT_LIMITS.maxLinkBytes),
    linkTtlMs: pick("LINK_TTL_MS", DEFAULT_LIMITS.linkTtlMs),
    linkLeaseMs: pick("LINK_LEASE_MS", DEFAULT_LIMITS.linkLeaseMs),
    linkAttemptsPerMinute: pick("LINK_ATTEMPTS_PER_MINUTE", DEFAULT_LIMITS.linkAttemptsPerMinute),
    maxSyncBodyBytes: pick("MAX_SYNC_BODY_BYTES", DEFAULT_LIMITS.maxSyncBodyBytes),
    maxSyncParts: pick("MAX_SYNC_PARTS", DEFAULT_LIMITS.maxSyncParts),
    maxSyncAssemblies: pick("MAX_SYNC_ASSEMBLIES", DEFAULT_LIMITS.maxSyncAssemblies),
    syncTtlMs: pick("SYNC_TTL_MS", DEFAULT_LIMITS.syncTtlMs),
    maxSyncEvents: pick("MAX_SYNC_EVENTS", DEFAULT_LIMITS.maxSyncEvents),
    maxSyncEventBytes: pick("MAX_SYNC_EVENT_BYTES", DEFAULT_LIMITS.maxSyncEventBytes),
    maxSyncPageEvents: pick("MAX_SYNC_PAGE_EVENTS", DEFAULT_LIMITS.maxSyncPageEvents),
    maxSyncPageBytes: pick("MAX_SYNC_PAGE_BYTES", DEFAULT_LIMITS.maxSyncPageBytes),
    // The TTL can be lowered but never raised past the 600 s ceiling enforced in call.ts.
    callIceTtlS: Math.min(pick("CALL_ICE_TTL_S", DEFAULT_LIMITS.callIceTtlS), DEFAULT_LIMITS.callIceTtlS),
    callIceCacheMs: pick("CALL_ICE_CACHE_MS", DEFAULT_LIMITS.callIceCacheMs),
    callIcePerMinute: pick("CALL_ICE_PER_MINUTE", DEFAULT_LIMITS.callIcePerMinute),
    callIceTimeoutMs: pick("CALL_ICE_TIMEOUT_MS", DEFAULT_LIMITS.callIceTimeoutMs),
    maxCallIceResponseBytes: pick("MAX_CALL_ICE_RESPONSE_BYTES", DEFAULT_LIMITS.maxCallIceResponseBytes),
    urgentRecipientIntervalMs: pick("URGENT_RECIPIENT_INTERVAL_MS", DEFAULT_LIMITS.urgentRecipientIntervalMs),
    urgentPerSenderPerMinute: pick("URGENT_PER_SENDER_PER_MINUTE", DEFAULT_LIMITS.urgentPerSenderPerMinute),
    urgentGlobalPerMinute: pick("URGENT_GLOBAL_PER_MINUTE", DEFAULT_LIMITS.urgentGlobalPerMinute),
    maxCallParticipants: pick("MAX_CALL_PARTICIPANTS", DEFAULT_LIMITS.maxCallParticipants),
    callReconnectGraceMs: pick("CALL_RECONNECT_GRACE_MS", DEFAULT_LIMITS.callReconnectGraceMs),
    callJoinsPerMinute: pick("CALL_JOINS_PER_MINUTE", DEFAULT_LIMITS.callJoinsPerMinute),
    callSfuPerMinute: pick("CALL_SFU_PER_MINUTE", DEFAULT_LIMITS.callSfuPerMinute),
    callHeartbeatRecheckMs: pick("CALL_HEARTBEAT_RECHECK_MS", DEFAULT_LIMITS.callHeartbeatRecheckMs),
    callUnjoinedTtlMs: pick("CALL_UNJOINED_TTL_MS", DEFAULT_LIMITS.callUnjoinedTtlMs),
    callCreatesPerMinute: pick("CALL_CREATES_PER_MINUTE", DEFAULT_LIMITS.callCreatesPerMinute),
    maxCallRequestBytes: pick("MAX_CALL_REQUEST_BYTES", DEFAULT_LIMITS.maxCallRequestBytes),
    maxSfuRequestBytes: pick("MAX_SFU_REQUEST_BYTES", DEFAULT_LIMITS.maxSfuRequestBytes),
    maxSfuResponseBytes: pick("MAX_SFU_RESPONSE_BYTES", DEFAULT_LIMITS.maxSfuResponseBytes),
    sfuTimeoutMs: pick("SFU_TIMEOUT_MS", DEFAULT_LIMITS.sfuTimeoutMs),
    maxCallTracksPerRequest: pick("MAX_CALL_TRACKS_PER_REQUEST", DEFAULT_LIMITS.maxCallTracksPerRequest),
    maxCallSdpBytes: pick("MAX_CALL_SDP_BYTES", DEFAULT_LIMITS.maxCallSdpBytes),
  };
}

/** Lowercase hex x-only public keys, separated by commas and/or whitespace. */
export function allowlistFrom(raw: unknown): Set<string> {
  if (typeof raw !== "string") return new Set();
  return new Set(raw.split(/[\s,]+/).map((k) => k.trim().toLowerCase()).filter((k) => PUBKEY.test(k)));
}

export const PUBKEY = /^[0-9a-f]{64}$/;
/** A media blob address: the lowercase hex SHA-256 of the encrypted file. */
export const MEDIA_DIGEST = /^[0-9a-f]{64}$/;
/** Client delivery ids: the app's payload ids are UUIDs; allow any short url-safe token. */
export const DELIVERY_ID = /^[A-Za-z0-9_-]{1,64}$/;

/** A device id: the implicit `primary` (the phone, and every client that sends no parameter) or 32 lowercase hex. */
export const PRIMARY_DEVICE = "primary";
export const DEVICE_ID = /^(?:primary|[0-9a-f]{32})$/;
export const LINKED_DEVICE_ID = /^[0-9a-f]{32}$/;
/** `last_seen` is written at most this often per device, to stay inside the rows-written budget. */
export const DEVICE_SEEN_REFRESH_MS = 60 * 60 * 1000;
export const MAX_DEVICE_LABEL_CHARS = 64;
/** Administration-key operations (migration, enrollment, revocation) per identity per minute. */
export const MAX_ADMIN_OPS_PER_MINUTE = 10;

/** `PUT|GET|DELETE /v1/link/<sha256 of the one-time code>`. The server never learns the code itself. */
export const LINK_SLOT = /^[0-9a-f]{64}$/;
/** The one-time code travels only in this header on the unauthenticated read: never a query parameter. */
export const LINK_PROOF_HEADER = "x-relay-link-proof";
/** Sync ids are opaque hex; 8 bytes is the practical minimum for uniqueness. */
export const SYNC_ID = /^[0-9a-f]{8,64}$/;
/** Request body ceiling for a sync part (a 12 KiB body base64-encoded plus framing). */
export const MAX_SYNC_REQUEST_BYTES = 24 * KIB;

/**
 * The `device` query parameter of a request: absent means `primary`, a malformed value is null.
 * NOT proof of identity: every device holding the Nostr secret can sign any value (see PROTOCOL.md).
 */
export function deviceParam(url: URL): string | null {
  const values = url.searchParams.getAll("device");
  if (values.length === 0) return PRIMARY_DEVICE;
  return values.length === 1 && DEVICE_ID.test(values[0]) ? values[0] : null;
}

export interface Delivery {
  to: string;
  id: string;
  envelope: string;
}

export type DeliveryResult =
  | { to: string; id: string; status: "stored" | "duplicate"; seq: number }
  | { to: string; id: string; status: "rejected"; error: { code: ErrorCode; retryable: boolean } };

export interface InboxMessage {
  seq: number;
  from: string;
  id: string;
  envelope: string;
  storedAt: number;
}

export function json(body: unknown, status = 200, headers: HeadersInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store", ...headers },
  });
}

export function errorResponse(status: number, code: ErrorCode, retryable = false, headers: HeadersInit = {}): Response {
  return json({ v: PROTOCOL_VERSION, error: { code, retryable } }, status, headers);
}

export const utf8 = new TextEncoder();

export async function sha256Hex(data: ArrayBuffer | Uint8Array | string): Promise<string> {
  const bytes = typeof data === "string" ? utf8.encode(data) : data;
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** WP15: a room id is 16 random bytes; the join secret is 32 random bytes and only its SHA-256 is stored. */
export const CALL_ROOM_ID = /^[0-9a-f]{32}$/;
export const CALL_PID = /^[0-9a-f]{16}$/;
export const SHA256_HEX = /^[0-9a-f]{64}$/;
/** A provider session id: opaque url-safe token, bounded. */
export const SFU_SESSION_ID = /^[A-Za-z0-9_-]{1,256}$/;
/** A WebRTC media id / track name: short url-safe token. */
export const SFU_TRACK_ID = /^[A-Za-z0-9_-]{1,64}$/;

/** Compares two lowercase-hex digests without an early-exit on the first differing character. */
export function constantTimeEqualHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
