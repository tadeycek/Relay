import { env } from "cloudflare:test";
import { exports } from "cloudflare:workers";
import { MailboxClient, authHeader, identity, wrapDm, type AuthOptions, type Identity } from "../client/mailbox-client";
import { testSecret } from "./identities";

export const BASE = "https://mailbox.test";

export const user = (i: number): Identity => identity(testSecret(i));
export const worker = (request: Request): Promise<Response> => exports.default.fetch(request);
export const client = (i: number) => new MailboxClient(BASE, user(i), worker);
export const mailboxStub = () => env.MAILBOX.get(env.MAILBOX.idFromName("pilot"));

let next = 0;
/** Unique client delivery id, like the app's payload UUIDs. */
export const newId = () => `t-${Date.now().toString(36)}-${(next++).toString(36)}`;

/** One real NIP-17 gift wrap from user [from] to user [to], sent alone. */
export async function sendDm(from: number, to: number, text = "hello", id = newId()) {
  const envelope = wrapDm(user(from), user(to).pk, text);
  const reply = await client(from).send([{ to: user(to).pk, id, envelope }]);
  return { reply, id, envelope, result: reply.body?.results?.[0] };
}

/** A request signed by user [i] with optional deliberate mistakes in the auth event. */
export async function signedRequest(i: number, method: string, path: string, body?: string, opts: AuthOptions = {}): Promise<Response> {
  const url = BASE + path;
  const headers: Record<string, string> = { Authorization: await authHeader(user(i).sk, method, url, body, opts) };
  return worker(new Request(url, { method, headers, body }));
}

export class TestSocket {
  readonly ws: WebSocket;
  readonly closed: Promise<{ code: number; reason: string }>;
  private readonly frames: any[] = [];
  private readonly waiting: Array<(frame: any) => void> = [];

  constructor(ws: WebSocket) {
    this.ws = ws;
    let onClose!: (c: { code: number; reason: string }) => void;
    this.closed = new Promise((resolve) => (onClose = resolve));
    ws.addEventListener("message", (e) => {
      const data = e.data as string;
      let frame: any = data;
      try {
        frame = JSON.parse(data);
      } catch {
        // Raw frames such as the "pong" auto-response.
      }
      const waiter = this.waiting.shift();
      if (waiter) waiter(frame);
      else this.frames.push(frame);
    });
    ws.addEventListener("close", (e) => onClose({ code: e.code, reason: e.reason }));
    ws.accept();
  }

  next(timeoutMs = 3000): Promise<any> {
    const queued = this.frames.shift();
    if (queued !== undefined) return Promise.resolve(queued);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("no frame within timeout")), timeoutMs);
      this.waiting.push((frame) => {
        clearTimeout(timer);
        resolve(frame);
      });
    });
  }

  /** True if nothing arrives within [ms]. */
  async silent(ms = 200): Promise<boolean> {
    if (this.frames.length > 0) return false;
    return new Promise((resolve) => {
      const waiter = (frame: any) => {
        this.frames.unshift(frame);
        resolve(false);
      };
      this.waiting.push(waiter);
      setTimeout(() => {
        const index = this.waiting.indexOf(waiter);
        if (index >= 0) {
          this.waiting.splice(index, 1);
          resolve(true);
        }
      }, ms);
    });
  }

  send(frame: unknown): void {
    this.ws.send(typeof frame === "string" ? frame : JSON.stringify(frame));
  }

  close(): void {
    try {
      this.ws.close(1000, "done");
    } catch {
      // Already closed.
    }
  }
}

/** Opens user [i]'s live socket. Returns the HTTP reply instead when the upgrade is refused. */
export async function connect(i: number): Promise<TestSocket> {
  const reply = await upgrade(i);
  if (reply.status !== 101 || !reply.webSocket) throw new Error(`upgrade refused: ${reply.status} ${await reply.text()}`);
  const socket = new TestSocket(reply.webSocket);
  const ready = await socket.next();
  if (ready?.type !== "ready") throw new Error(`expected ready, got ${JSON.stringify(ready)}`);
  (socket as any).instance = ready.instance;
  return socket;
}

export async function upgrade(i: number, headers: Record<string, string> = {}): Promise<Response> {
  const c = client(i);
  // The client signs the wss:// URL it dials; the Worker sees the same request as https://.
  const auth = headers.Authorization === undefined ? await c.socketHeaders() : {};
  return worker(new Request(`${BASE}/v1/ws`, { headers: { Upgrade: "websocket", ...auth, ...headers } }));
}
