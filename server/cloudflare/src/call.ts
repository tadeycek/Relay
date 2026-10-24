/**
 * Call support (WP11): short-lived Cloudflare TURN credentials for 1:1 voice calls.
 *
 * The Worker holds the TURN API token; clients never see it. `GET /v1/call/ice` asks Cloudflare's
 * `generate-ice-servers` for a credential that expires within [MAX_ICE_TTL_S] and hands the client only the
 * sanitized ICE server list. Credential lifetime and revocation are the provider's job: nothing is stored here.
 *
 * Secrets come only from the Worker bindings TURN_KEY_ID and TURN_API_TOKEN. They are never logged, never
 * echoed, and never put in an error: failures are reported as a bare code.
 */

export interface TurnConfig {
  keyId: string;
  token: string;
}

export interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

type Fetch = (request: Request) => Promise<Response>;

/** The only host this code talks to. The key id is validated to a path-safe token before it enters the URL. */
export const TURN_API_ORIGIN = "https://rtc.live.cloudflare.com";
/**
 * Cloudflare's edge answers the default `Python-urllib` client with error 1010. An honest, specific
 * User-Agent is what the provider accepts (WP02 live run), and it tells the operator who is calling.
 */
export const CALL_USER_AGENT = "RelayMailbox/1.0 (call-ice; Cloudflare Worker)";
/** Hard ceiling on the lifetime asked of the provider, whatever CALL_ICE_TTL_S is set to. */
export const MAX_ICE_TTL_S = 600;

const SECRET = /^[A-Za-z0-9_-]{8,512}$/;
const MAX_SERVERS = 8;
const MAX_URLS_PER_SERVER = 8;
const MAX_URL_CHARS = 200;
const MAX_CREDENTIAL_CHARS = 512;
/** scheme:host[:port][?transport=udp|tcp]; the host must be a Cloudflare name. */
const ICE_URL = /^(stun|turn|turns):([a-z0-9.-]+)(?::(\d{1,5}))?(?:\?transport=(?:udp|tcp))?$/;
const CLOUDFLARE_HOST = /(^|\.)cloudflare\.com$/;
/** Port 53 is often blocked or hijacked by captive resolvers and makes ICE gathering slow; clients must not try it. */
const STRIPPED_PORT = "53";

/** The TURN secrets from the Worker env, or null when absent or malformed (the endpoint then says not_configured). */
export function turnConfigFrom(env: Partial<Record<string, unknown>>): TurnConfig | null {
  const keyId = env.TURN_KEY_ID;
  const token = env.TURN_API_TOKEN;
  if (typeof keyId !== "string" || typeof token !== "string") return null;
  if (!SECRET.test(keyId) || !SECRET.test(token)) return null;
  return { keyId, token };
}

export type IceOutcome = { ok: true; iceServers: IceServer[]; expiresAt: number } | { ok: false };

export interface MintOptions {
  ttlS: number;
  timeoutMs: number;
  maxResponseBytes: number;
}

/** Asks the provider for credentials. Never throws, never follows a redirect, never returns provider text. */
export async function mintIceServers(config: TurnConfig, fetcher: Fetch, now: number, options: MintOptions): Promise<IceOutcome> {
  const ttl = Math.max(1, Math.min(options.ttlS, MAX_ICE_TTL_S));
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const request = new Request(`${TURN_API_ORIGIN}/v1/turn/keys/${config.keyId}/credentials/generate-ice-servers`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.token}`,
        "Content-Type": "application/json",
        Accept: "application/json",
        "User-Agent": CALL_USER_AGENT,
      },
      body: JSON.stringify({ ttl }),
      redirect: "manual",
      signal: controller.signal,
    });
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        controller.abort();
        reject(new Error("timeout"));
      }, options.timeoutMs);
    });
    const work = (async (): Promise<IceOutcome> => {
      const response = await fetcher(request);
      // A 3xx is never followed (the token would travel with it) and is never a success.
      if (response.status < 200 || response.status > 299) {
        await response.body?.cancel().catch(() => undefined);
        console.error("call ice upstream refused", { status: response.status });
        return { ok: false };
      }
      const bytes = await readBounded(response, options.maxResponseBytes);
      if (!bytes) {
        console.error("call ice upstream response too large");
        return { ok: false };
      }
      const servers = sanitizeIceServers(parseJson(bytes));
      if (!servers) {
        console.error("call ice upstream response unusable");
        return { ok: false };
      }
      return { ok: true, iceServers: servers, expiresAt: now + ttl * 1000 };
    })();
    work.catch(() => undefined); // a late failure after the timeout won must not become an unhandled rejection
    // The timeout wins even against a fetcher that never settles.
    return await Promise.race([work, timeout]);
  } catch (e) {
    console.error("call ice upstream failed", { error: (e as Error)?.name });
    return { ok: false };
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/** Reads at most [limit] bytes; null when the body is bigger (declared or actual). Stops reading at the limit. */
async function readBounded(response: Response, limit: number): Promise<Uint8Array | null> {
  const declared = Number(response.headers.get("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > limit) {
    await response.body?.cancel().catch(() => undefined);
    return null;
  }
  if (!response.body) return new Uint8Array();
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel().catch(() => undefined);
      return null;
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

function parseJson(bytes: Uint8Array): unknown {
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
}

const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const safeText = (v: unknown): v is string => typeof v === "string" && v.length > 0 && v.length <= MAX_CREDENTIAL_CHARS && !/[\u0000-\u001f\u007f]/.test(v);

/**
 * Validates the provider answer and returns only what a client needs: `urls`, and `username`/`credential` where
 * present. Accepts `iceServers` as a list or as one object (both seen in the live run). Returns null unless at
 * least one authenticated TURN server remains, so a client is never handed a list it cannot relay through.
 */
export function sanitizeIceServers(body: unknown): IceServer[] | null {
  if (!isRecord(body)) return null;
  const raw = Array.isArray(body.iceServers) ? body.iceServers : isRecord(body.iceServers) ? [body.iceServers] : null;
  if (!raw || raw.length === 0 || raw.length > MAX_SERVERS) return null;
  const out: IceServer[] = [];
  let hasTurn = false;
  for (const entry of raw) {
    if (!isRecord(entry)) return null;
    const list = typeof entry.urls === "string" ? [entry.urls] : Array.isArray(entry.urls) ? entry.urls : null;
    if (!list || list.length > MAX_URLS_PER_SERVER) return null;
    const urls: string[] = [];
    let needsCredential = false;
    for (const url of list) {
      if (typeof url !== "string" || url.length > MAX_URL_CHARS) return null;
      const m = ICE_URL.exec(url);
      if (!m || !CLOUDFLARE_HOST.test(m[2])) return null;
      if (m[3] === STRIPPED_PORT) continue;
      if (m[3] !== undefined && Number(m[3]) > 65535) return null;
      if (m[1] !== "stun") needsCredential = true;
      urls.push(url);
    }
    if (urls.length === 0) continue;
    const server: IceServer = { urls };
    if (needsCredential) {
      if (!safeText(entry.username) || !safeText(entry.credential)) return null;
      server.username = entry.username;
      server.credential = entry.credential;
      hasTurn = true;
    }
    out.push(server);
  }
  return hasTurn ? out : null;
}
