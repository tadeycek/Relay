import { PROTOCOL_VERSION, errorResponse, json, limitsFrom } from "./protocol";

export { Mailbox } from "./mailbox";

const MAILBOX_ROUTES = new Set(["/v1/send", "/v1/inbox", "/v1/ack", "/v1/ws"]);

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
    if (!MAILBOX_ROUTES.has(pathname)) return errorResponse(404, "not_found");

    const authorization = request.headers.get("Authorization");
    if (!authorization) return errorResponse(401, "auth_missing");
    if (!authorization.startsWith("Nostr ")) return errorResponse(401, "auth_invalid");
    const length = Number(request.headers.get("Content-Length") ?? "0");
    if (!Number.isFinite(length) || length > limitsFrom(env as unknown as Record<string, unknown>).maxRequestBytes) {
      return errorResponse(413, "request_too_large");
    }

    // One object for the whole pilot. The hint only matters when it is first created (users are in Europe).
    const mailbox = env.MAILBOX.get(env.MAILBOX.idFromName("pilot"), { locationHint: "weur" });
    return mailbox.fetch(request);
  },
} satisfies ExportedHandler<Env>;
