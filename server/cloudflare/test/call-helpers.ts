import { runInDurableObject } from "cloudflare:test";
import type { Mailbox } from "../src/mailbox";
import type { TurnConfig } from "../src/call";
import { mailboxStub } from "./helpers";

/** Fake provider credentials. They exist only in these tests; nothing here is a real key. */
export const KEY_ID = "0123456789abcdef0123456789abcdef";
export const API_TOKEN = "turn-api-token-SECRET-do-not-leak-0123456789";
export const CRED_USER = "1796000000:relay-user";
export const CRED_PASS = "relay-ephemeral-credential-value";

export interface UpstreamCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string;
  redirect: string;
}

/** The shape the real generate-ice-servers call returned in the WP02 live run (status 201). */
export const providerBody = () => ({
  iceServers: [
    { urls: ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"] },
    {
      urls: [
        "turn:turn.cloudflare.com:3478?transport=udp",
        "turn:turn.cloudflare.com:53?transport=udp",
        "turn:turn.cloudflare.com:80?transport=tcp",
        "turns:turn.cloudflare.com:5349?transport=tcp",
        "turns:turn.cloudflare.com:443?transport=tcp",
      ],
      username: CRED_USER,
      credential: CRED_PASS,
    },
  ],
});

export type UpstreamHandler = (call: UpstreamCall, request: Request) => Response | Promise<Response>;

/** Configures the mailbox with fake TURN secrets and replaces its upstream fetch. Returns the recorded calls. */
export async function fakeTurn(handler?: UpstreamHandler, config: TurnConfig | null = { keyId: KEY_ID, token: API_TOKEN }): Promise<UpstreamCall[]> {
  const calls: UpstreamCall[] = [];
  const respond: UpstreamHandler = handler ?? (() => new Response(JSON.stringify(providerBody()), { status: 201, headers: { "content-type": "application/json" } }));
  await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
    (instance as any).turnConfig = () => config;
    (instance as any).turnFetch = async (request: Request) => {
      const headers: Record<string, string> = {};
      request.headers.forEach((value, key) => (headers[key] = value));
      const call: UpstreamCall = { url: request.url, method: request.method, headers, body: await request.text(), redirect: request.redirect };
      calls.push(call);
      return respond(call, request);
    };
  });
  return calls;
}

/** Changes a limit on the running object, e.g. to shrink a timeout or window so a test stays fast. */
export async function setLimit(name: string, value: number): Promise<void> {
  await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
    (instance as any).limits[name] = value;
  });
}

export const sqlRows = (headers: Headers): { read: number; written: number } => {
  const m = /read=(\d+);written=(\d+)/.exec(headers.get("x-relay-sql-rows") ?? "");
  if (!m) throw new Error("no x-relay-sql-rows header: EXPOSE_METRICS must be 1");
  return { read: Number(m[1]), written: Number(m[2]) };
};

/** Rows this object counted so far (includes work done after the response, e.g. the wake-up push). */
export const rowCounters = (): Promise<{ read: number; written: number }> =>
  runInDurableObject(mailboxStub(), async (instance: Mailbox) => ({ ...(instance as any).rows }));
