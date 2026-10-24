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
  | "not_allowed"
  | "request_too_large"
  | "too_many_deliveries"
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
  mediaLifetimeMs: 8 * DAY,
  // Well under the 2 MB a single SQLite value may hold in a Durable Object.
  mediaChunkBytes: 1 * MIB,
  maxMembers: 100,
  // The app's QR invitations live 30 minutes.
  inviteMaxTtlMs: 60 * 60 * 1000,
  maxInvitesPerMember: 20,
  maxLinkedDevices: 3,
  dormantAfterMs: 72 * 60 * 60 * 1000,
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
    mediaLifetimeMs: pick("MEDIA_LIFETIME_MS", DEFAULT_LIMITS.mediaLifetimeMs),
    mediaChunkBytes: pick("MEDIA_CHUNK_BYTES", DEFAULT_LIMITS.mediaChunkBytes),
    maxMembers: pick("MAX_MEMBERS", DEFAULT_LIMITS.maxMembers),
    inviteMaxTtlMs: pick("INVITE_MAX_TTL_MS", DEFAULT_LIMITS.inviteMaxTtlMs),
    maxInvitesPerMember: pick("MAX_INVITES_PER_MEMBER", DEFAULT_LIMITS.maxInvitesPerMember),
    maxLinkedDevices: pick("MAX_LINKED_DEVICES", DEFAULT_LIMITS.maxLinkedDevices),
    dormantAfterMs: pick("DORMANT_AFTER_MS", DEFAULT_LIMITS.dormantAfterMs),
  };
}

/** Lowercase hex x-only public keys, separated by commas and/or whitespace. */
export function allowlistFrom(raw: unknown): Set<string> {
  if (typeof raw !== "string") return new Set();
  return new Set(raw.split(/[\s,]+/).map((k) => k.trim().toLowerCase()).filter((k) => PUBKEY.test(k)));
}

export const PUBKEY = /^[0-9a-f]{64}$/;
/** Client delivery ids: the app's payload ids are UUIDs; allow any short url-safe token. */
export const DELIVERY_ID = /^[A-Za-z0-9_-]{1,64}$/;

/** A device id: the implicit `primary` (the phone, and every client that sends no parameter) or 32 lowercase hex. */
export const PRIMARY_DEVICE = "primary";
export const DEVICE_ID = /^(?:primary|[0-9a-f]{32})$/;
export const LINKED_DEVICE_ID = /^[0-9a-f]{32}$/;
/** `last_seen` is written at most this often per device, to stay inside the rows-written budget. */
export const DEVICE_SEEN_REFRESH_MS = 60 * 60 * 1000;
export const MAX_DEVICE_LABEL_CHARS = 64;

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
