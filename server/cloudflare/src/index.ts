import { PROTOCOL_VERSION, errorResponse, json, limitsFrom } from "./protocol";

export { Mailbox } from "./mailbox";
export { MediaBlob } from "./media";
export { CallRoom } from "./callroom";

const MAILBOX_ROUTES = new Set(["/v1/send", "/v1/inbox", "/v1/ack", "/v1/ws", "/v1/invite", "/v1/pair", "/v1/push-token", "/v1/devices", "/v1/devices/admin", "/v1/sync", "/v1/sync/ack", "/v1/call/ice"]);
const DEVICE_ROUTE = /^\/v1\/devices\/[0-9a-f]{32}$|^\/v1\/devices\/primary$/;
const MEDIA_ROUTE = /^\/v1\/media\/([0-9a-f]{64})$/;
/** The only unauthenticated data path: GET/DELETE prove the one-time code with a header instead. */
const LINK_ROUTE = /^\/v1\/link\/[0-9a-f]{64}$/;
const SYNC_ID_ROUTE = /^\/v1\/sync\/[0-9a-f]{8,64}$/;
/** WP15: the room is part of the path (or the create body); the CallRoom object owns everything else. */
const CALL_ROOMS = "/v1/call/rooms";
const CALL_ROOM_ROUTE = /^\/v1\/call\/rooms\/([0-9a-f]{32})(?:\/(ws|end|sfu\/[a-z-]+))?$/;
const CALL_ROOM_ID = /^[0-9a-f]{32}$/;

/**
 * Edge router. It only does cheap checks so junk never costs a Durable Object request; signature
 * verification, replay protection and all queue work happen inside the mailbox object.
 */
export default {
  async fetch(request, env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/v1/health") {
      return request.method === "GET" ? json({ ok: true, v: PROTOCOL_VERSION }) : errorResponse(405, "method_not_allowed");
    }
    const media = MEDIA_ROUTE.exec(pathname);
    const link = LINK_ROUTE.test(pathname);
    const callRoom = CALL_ROOM_ROUTE.exec(pathname);
    const callCreate = pathname === CALL_ROOMS;
    if (!media && !link && !callCreate && !callRoom && !MAILBOX_ROUTES.has(pathname) && !DEVICE_ROUTE.test(pathname) && !SYNC_ID_ROUTE.test(pathname)) {
      return errorResponse(404, "not_found");
    }

    if (!link) {
      const authorization = request.headers.get("Authorization");
      if (!authorization) return errorResponse(401, "auth_missing");
      if (!authorization.startsWith("Nostr ")) return errorResponse(401, "auth_invalid");
    }
    const limits = limitsFrom(env as unknown as Record<string, unknown>);
    const length = Number(request.headers.get("Content-Length") ?? "0");
    // One byte of slack on the link path so the mailbox returns the precise error code for the boundary.
    const callCap = callCreate || (callRoom && callRoom[2] === "end")
      ? limits.maxCallRequestBytes
      : callRoom && callRoom[2]?.startsWith("sfu/")
        ? limits.maxSfuRequestBytes
        : limits.maxRequestBytes;
    const cap = media ? limits.maxMediaBytes : link ? limits.maxLinkBytes + 1 : callCap;
    if (!Number.isFinite(length) || length > cap) {
      return errorResponse(413, "request_too_large");
    }

    // Each file lives in its own object, so large transfers never hold up the message mailbox.
    if (media) return env.MEDIA.get(env.MEDIA.idFromName(media[1]), { locationHint: "weur" }).fetch(request);

    // WP15: the create body names the room, so the edge can pick the per-call object without an extra hop.
    if (callCreate) {
      if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
      const value = await request.clone().json().catch(() => null);
      const room = value && typeof value === "object" ? (value as { room?: unknown }).room : null;
      if (typeof room !== "string" || !CALL_ROOM_ID.test(room)) return errorResponse(400, "bad_request");
      return env.CALLROOM.get(env.CALLROOM.idFromName(room), { locationHint: "weur" }).fetch(request);
    }
    if (callRoom) return env.CALLROOM.get(env.CALLROOM.idFromName(callRoom[1]), { locationHint: "weur" }).fetch(request);

    // One object for the whole pilot. The hint only matters when it is first created (users are in Europe).
    const mailbox = env.MAILBOX.get(env.MAILBOX.idFromName("pilot"), { locationHint: "weur" });
    return mailbox.fetch(request);
  },
} satisfies ExportedHandler<Env>;
