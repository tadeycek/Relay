/**
 * Minimal mailbox client shared by the tests (inside workerd) and the pilot tools (Node). It signs
 * requests with NIP-98 and builds/open real NIP-17 gift wraps, the same format the app sends today.
 * Erasable TypeScript only, so Node runs it directly.
 */
import { finalizeEvent, generateSecretKey, getPublicKey, verifyEvent, type NostrEvent } from "nostr-tools/pure";
import * as nip17 from "nostr-tools/nip17";
import * as nip44 from "nostr-tools/nip44";

export interface Identity {
  sk: Uint8Array;
  pk: string;
}

export interface Delivery {
  to: string;
  id: string;
  envelope: string;
}

export interface Reply<T = any> {
  status: number;
  body: T;
  headers: Headers;
}

const hex = (bytes: Uint8Array) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");

export function identity(sk: Uint8Array): Identity {
  return { sk, pk: getPublicKey(sk) };
}

export function newIdentity(): Identity {
  return identity(generateSecretKey());
}

export function secretHex(id: Identity): string {
  return hex(id.sk);
}

export function identityFromHex(skHex: string): Identity {
  return identity(Uint8Array.from(skHex.match(/../g)!.map((b) => parseInt(b, 16))));
}

async function sha256Hex(data: string): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(data))));
}

function base64(text: string): string {
  return btoa(String.fromCharCode(...new TextEncoder().encode(text)));
}

export interface AuthOptions {
  createdAt?: number;
  kind?: number;
  /** Override the signed URL / method / body hash, to build deliberately wrong headers in tests. */
  url?: string;
  method?: string;
  payload?: string | null;
}

/**
 * `Authorization` value for one request: a fresh kind-27235 event bound to URL, method and body.
 * The random nonce keeps two identical requests in the same second from sharing an event id,
 * which the server would otherwise refuse as a replay.
 */
export async function authHeader(sk: Uint8Array, method: string, url: string, body?: string, opts: AuthOptions = {}): Promise<string> {
  const tags = [["u", opts.url ?? url], ["method", opts.method ?? method], ["nonce", hex(crypto.getRandomValues(new Uint8Array(16)))]];
  const payload = opts.payload !== undefined ? opts.payload : body ? await sha256Hex(body) : null;
  if (payload !== null) tags.push(["payload", payload]);
  const event = finalizeEvent(
    { kind: opts.kind ?? 27235, created_at: opts.createdAt ?? Math.floor(Date.now() / 1000), tags, content: "" },
    sk,
  );
  return `Nostr ${base64(JSON.stringify(event))}`;
}

/** A NIP-17 direct message gift-wrapped for [recipientPk] (kind 14 rumor → kind 13 seal → kind 1059 wrap). */
export function wrapDm(sender: Identity, recipientPk: string, text: string): string {
  return JSON.stringify(nip17.wrapEvent(sender.sk, { publicKey: recipientPk }, text));
}

/**
 * Opens a gift wrap the way NostrTransport does: both layers decrypt, the wrap and seal signatures
 * verify, and the rumor's claimed author is the seal signer. Throws for anyone but the recipient.
 */
export function openDm(recipient: Identity, envelope: string): { sender: string; content: string } {
  const wrap = JSON.parse(envelope) as NostrEvent;
  if (wrap.kind !== 1059 || !verifyEvent(wrap)) throw new Error("not a valid gift wrap");
  const seal = JSON.parse(nip44.decrypt(wrap.content, nip44.getConversationKey(recipient.sk, wrap.pubkey))) as NostrEvent;
  if (seal.kind !== 13 || !verifyEvent(seal)) throw new Error("not a valid seal");
  const rumor = JSON.parse(nip44.decrypt(seal.content, nip44.getConversationKey(recipient.sk, seal.pubkey)));
  if (rumor.kind !== 14 || rumor.pubkey !== seal.pubkey) throw new Error("rumor author is not the seal signer");
  return { sender: seal.pubkey, content: rumor.content };
}

type Fetcher = (request: Request) => Promise<Response>;

export class MailboxClient {
  readonly base: string;
  readonly me: Identity;
  private readonly fetcher: Fetcher;

  constructor(base: string, me: Identity, fetcher: Fetcher = (r) => fetch(r)) {
    this.base = base.replace(/\/$/, "");
    this.me = me;
    this.fetcher = fetcher;
  }

  async request(method: string, path: string, body?: unknown): Promise<Reply> {
    const url = this.base + path;
    const text = body === undefined ? undefined : JSON.stringify(body);
    const headers: Record<string, string> = { Authorization: await authHeader(this.me.sk, method, url, text) };
    if (text !== undefined) headers["content-type"] = "application/json";
    const response = await this.fetcher(new Request(url, { method, headers, body: text }));
    const raw = await response.text();
    let parsed: unknown = raw;
    try {
      parsed = raw ? JSON.parse(raw) : null;
    } catch {
      // Non-JSON body (e.g. a platform error page); keep the text.
    }
    return { status: response.status, body: parsed, headers: response.headers };
  }

  send(deliveries: Delivery[]): Promise<Reply> {
    return this.request("POST", "/v1/send", { v: 1, deliveries });
  }

  inbox(after = 0, limit?: number): Promise<Reply> {
    const query = new URLSearchParams({ after: String(after) });
    if (limit !== undefined) query.set("limit", String(limit));
    return this.request("GET", `/v1/inbox?${query}`);
  }

  ack(through: number): Promise<Reply> {
    return this.request("POST", "/v1/ack", { v: 1, through });
  }

  socketUrl(): string {
    return this.base.replace(/^http/, "ws") + "/v1/ws";
  }

  /** Headers for the WebSocket upgrade; the signed URL is the ws(s) URL being dialled. */
  async socketHeaders(): Promise<Record<string, string>> {
    return { Authorization: await authHeader(this.me.sk, "GET", this.socketUrl()) };
  }
}
