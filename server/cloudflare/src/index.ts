import { MEDIA_CONTROL_BYTES, MEDIA_PART_BYTES, PROTOCOL_VERSION, errorResponse, json, limitsFrom } from "./protocol";

export { Mailbox } from "./mailbox";
export { MediaBlob } from "./media";

const MAILBOX_ROUTES = new Set(["/v1/send", "/v1/inbox", "/v1/ack", "/v1/ws", "/v1/invite", "/v1/pair", "/v1/push-token"]);
const MEDIA_ROUTE = /^\/v1\/media\/([0-9a-f]{64})$/;
const MEDIA_V2_ROUTE = /^\/v2\/media\/([0-9a-f]{64})(?:\/(init|status|complete|parts\/(?:0|[1-9][0-9]{0,3})))?$/;

/**
 * Edge router. It only does cheap checks so junk never costs a Durable Object request; signature
 * verification, replay protection and all queue work happen inside the mailbox object.
 */
export default {
  async fetch(request, env): Promise<Response> {
    const { pathname } = new URL(request.url);
    if (pathname === "/v1/health") {
      return request.method === "GET" ? json({ ok: true, v: PROTOCOL_VERSION, media: { v: 2, partSize: MEDIA_PART_BYTES } }) : errorResponse(405, "method_not_allowed");
    }
    const mediaV2 = MEDIA_V2_ROUTE.exec(pathname);
    const media = mediaV2 ?? MEDIA_ROUTE.exec(pathname);
    if (!media && !MAILBOX_ROUTES.has(pathname)) return errorResponse(404, "not_found");

    const authorization = request.headers.get("Authorization");
    if (!authorization) return errorResponse(401, "auth_missing");
    if (!authorization.startsWith("Nostr ")) return errorResponse(401, "auth_invalid");
    const limits = limitsFrom(env as unknown as Record<string, unknown>);
    const length = Number(request.headers.get("Content-Length") ?? "0");
    const maxBytes = mediaV2 ? (mediaV2[2]?.startsWith("parts/") ? MEDIA_PART_BYTES : MEDIA_CONTROL_BYTES) : media ? limits.maxMediaBytes : limits.maxRequestBytes;
    if (!Number.isSafeInteger(length) || length < 0 || length > maxBytes) {
      return errorResponse(413, "request_too_large");
    }

    // Each file lives in its own object, so large transfers never hold up the message mailbox.
    if (media) return env.MEDIA.get(env.MEDIA.idFromName(media[1]), { locationHint: "weur" }).fetch(request);

    // One object for the whole pilot. The hint only matters when it is first created (users are in Europe).
    const mailbox = env.MAILBOX.get(env.MAILBOX.idFromName("pilot"), { locationHint: "weur" });
    return mailbox.fetch(request);
  },
} satisfies ExportedHandler<Env>;
