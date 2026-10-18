import { verifyEvent, type VerifiedEvent } from "nostr-tools/pure";
import { PUBKEY, sha256Hex, type ErrorCode } from "./protocol";

/**
 * NIP-98 HTTP auth: every request (and the WebSocket upgrade) carries
 * `Authorization: Nostr <base64(event)>`, a kind-27235 event signed with the caller's Nostr identity
 * key — the same key the app pairs by QR. It is bound to the exact URL, the method and, when there is
 * a body, the body's SHA-256, and is only accepted within [AUTH_WINDOW_S] of the server clock.
 * Replay inside that window is stopped by the mailbox, which records each accepted event id.
 */
export const AUTH_KIND = 27235;
export const AUTH_WINDOW_S = 60;

export type AuthOutcome =
  | { ok: true; pubkey: string; eventId: string; createdAt: number }
  | { ok: false; status: 401 | 403; code: ErrorCode };

const fail = (code: ErrorCode, status: 401 | 403 = 401): AuthOutcome => ({ ok: false, status, code });

/** The upgrade request reaches the Worker as http(s); clients sign the ws(s) URL they dialled. */
function normalizeUrl(raw: string): string | null {
  try {
    const url = new URL(raw.replace(/^ws(s?):\/\//i, "http$1://"));
    url.hash = "";
    return url.href;
  } catch {
    return null;
  }
}

function decodeEvent(header: string | null): VerifiedEvent | null {
  if (!header?.startsWith("Nostr ")) return null;
  try {
    const binary = atob(header.slice("Nostr ".length).trim());
    const text = new TextDecoder().decode(Uint8Array.from(binary, (c) => c.charCodeAt(0)));
    const event = JSON.parse(text);
    const wellFormed =
      event && typeof event === "object" &&
      typeof event.id === "string" && typeof event.pubkey === "string" && typeof event.sig === "string" &&
      typeof event.content === "string" && Number.isSafeInteger(event.kind) && Number.isSafeInteger(event.created_at) &&
      Array.isArray(event.tags) && event.tags.every((t: unknown) => Array.isArray(t) && t.every((v) => typeof v === "string"));
    return wellFormed ? event : null;
  } catch {
    return null;
  }
}

const tag = (event: VerifiedEvent, name: string): string | undefined => event.tags.find((t) => t[0] === name)?.[1];

export async function verifyRequestAuth(
  request: Request,
  body: ArrayBuffer | null,
  nowS: number,
  allowed: ReadonlySet<string>,
): Promise<AuthOutcome> {
  const header = request.headers.get("Authorization");
  if (!header) return fail("auth_missing");
  const event = decodeEvent(header);
  if (!event || event.kind !== AUTH_KIND || !PUBKEY.test(event.pubkey)) return fail("auth_invalid");

  if (Math.abs(nowS - event.created_at) > AUTH_WINDOW_S) return fail("auth_expired");

  const signedUrl = normalizeUrl(tag(event, "u") ?? "");
  if (!signedUrl || signedUrl !== normalizeUrl(request.url)) return fail("auth_invalid");
  if (tag(event, "method")?.toUpperCase() !== request.method.toUpperCase()) return fail("auth_invalid");

  const payloadTag = tag(event, "payload");
  if (body && body.byteLength > 0) {
    if (payloadTag?.toLowerCase() !== (await sha256Hex(body))) return fail("auth_invalid");
  } else if (payloadTag !== undefined && payloadTag.toLowerCase() !== (await sha256Hex(new Uint8Array()))) {
    return fail("auth_invalid");
  }

  // Checks the id is the event hash and the BIP-340 signature is by `pubkey`.
  if (!verifyEvent(event)) return fail("auth_invalid");
  if (!allowed.has(event.pubkey)) return fail("not_allowed", 403);

  return { ok: true, pubkey: event.pubkey, eventId: event.id, createdAt: event.created_at };
}
