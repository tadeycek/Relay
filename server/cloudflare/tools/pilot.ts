/**
 * Pilot tool for the mailbox, against local `wrangler dev` or the deployed test Worker.
 *
 *   node tools/pilot.ts identities                create the synthetic test identities (once)
 *   node tools/pilot.ts allowlist                 print their pubkeys for --var ALLOWED_PUBKEYS:...
 *   node tools/pilot.ts smoke     --url URL       end-to-end privacy and delivery checks
 *   node tools/pilot.ts load      --url URL       a 1,000-message pilot day, compressed; writes reports/
 *   node tools/pilot.ts probe     --url URL       one send → push → receipt round trip, logged as JSONL
 *   node tools/pilot.ts hibernate --url URL [--idle 60]   an idle socket survives a real eviction
 *   node tools/pilot.ts uptime [--days 7]         availability and latency from the probe log
 *
 * The URL can also come from RELAY_MAILBOX_URL, or from ~/.config/relay-mailbox/pilot-url (kept out of the repo). Identities are synthetic keys, never real users', and
 * are kept outside the repository. Logs record "local" or "remote", never the deployed hostname.
 */
import { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { parseArgs } from "node:util";
import {
  MailboxClient,
  authHeader,
  identityFromHex,
  newIdentity,
  openDm,
  secretHex,
  wrapDm,
  type Delivery,
  type Identity,
} from "../client/mailbox-client.ts";

const CONFIG_DIR = join(process.env.XDG_CONFIG_HOME ?? join(homedir(), ".config"), "relay-mailbox");
const CONFIG_FILE = join(CONFIG_DIR, "pilot-identities.json");
const URL_FILE = join(CONFIG_DIR, "pilot-url");
const STATE_DIR = join(process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state"), "relay-mailbox");
const USERS = 10;

interface Identities {
  users: Identity[];
  outsider: Identity;
}

function loadIdentities(): Identities {
  if (!existsSync(CONFIG_FILE)) throw new Error(`no identities yet: run "node tools/pilot.ts identities" first`);
  const raw = JSON.parse(readFileSync(CONFIG_FILE, "utf8"));
  return { users: raw.users.map((u: any) => identityFromHex(u.secret)), outsider: identityFromHex(raw.outsider.secret) };
}

function createIdentities(): void {
  if (existsSync(CONFIG_FILE)) {
    console.log(`identities already exist in ${CONFIG_FILE}; leaving them unchanged`);
    return;
  }
  const entry = (name: string, id: Identity) => ({ name, pubkey: id.pk, secret: secretHex(id) });
  const file = {
    version: 1,
    note: "Synthetic Relay mailbox pilot identities. Test keys only; not real users.",
    created: new Date().toISOString(),
    users: Array.from({ length: USERS }, (_, i) => entry(`pilot-${i}`, newIdentity())),
    outsider: entry("outsider", newIdentity()),
  };
  mkdirSync(dirname(CONFIG_FILE), { recursive: true, mode: 0o700 });
  writeFileSync(CONFIG_FILE, JSON.stringify(file, null, 2) + "\n", { mode: 0o600 });
  chmodSync(CONFIG_FILE, 0o600);
  console.log(`created ${USERS} pilot identities and one outsider in ${CONFIG_FILE} (mode 600)`);
}

const targetLabel = (url: string) => (/^https?:\/\/(127\.0\.0\.1|localhost)(:|\/|$)/.test(url) ? "local" : "remote");
let counter = 0;
const newId = () => `p-${Date.now().toString(36)}-${(counter++).toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function percentile(values: number[], p: number): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

const stats = (values: number[]) => ({
  n: values.length,
  p50: percentile(values, 50),
  p95: percentile(values, 95),
  max: values.length ? Math.max(...values) : null,
});

function rows(headers: Headers): { read: number; written: number } | null {
  const m = /read=(\d+);written=(\d+)/.exec(headers.get("x-relay-sql-rows") ?? "");
  return m ? { read: Number(m[1]), written: Number(m[2]) } : null;
}

/** A live mailbox socket in Node. Frames go to [onFrame] if set, otherwise to a queue read by next(). */
class LiveSocket {
  readonly ws: WebSocket;
  readonly closed: Promise<{ code: number; reason: string }>;
  instance = "";
  onFrame: ((frame: any) => void) | null = null;
  private readonly queue: any[] = [];
  private readonly waiting: Array<(frame: any) => void> = [];

  private constructor(ws: WebSocket) {
    this.ws = ws;
    let onClose!: (c: { code: number; reason: string }) => void;
    this.closed = new Promise((resolve) => (onClose = resolve));
    ws.addEventListener("close", (e) => onClose({ code: e.code, reason: e.reason }));
    ws.addEventListener("message", (e) => {
      let frame: any = e.data;
      try {
        frame = JSON.parse(String(e.data));
      } catch {
        // "pong"
      }
      if (this.onFrame) return this.onFrame(frame);
      const waiter = this.waiting.shift();
      if (waiter) waiter(frame);
      else this.queue.push(frame);
    });
  }

  static async open(client: MailboxClient, timeoutMs = 15000): Promise<LiveSocket> {
    // Node's WebSocket accepts headers; the upgrade is authenticated like any request.
    const ws = new WebSocket(client.socketUrl(), { headers: await client.socketHeaders() } as any);
    const socket = new LiveSocket(ws);
    const ready = await socket.next(timeoutMs);
    if (ready?.type !== "ready") throw new Error(`expected ready frame, got ${JSON.stringify(ready)}`);
    socket.instance = ready.instance;
    return socket;
  }

  next(timeoutMs = 10000): Promise<any> {
    const queued = this.queue.shift();
    if (queued !== undefined) return Promise.resolve(queued);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no frame within ${timeoutMs} ms`)), timeoutMs);
      this.waiting.push((frame) => {
        clearTimeout(timer);
        resolve(frame);
      });
    });
  }

  async until(predicate: (frame: any) => boolean, timeoutMs = 10000): Promise<any> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const frame = await this.next(Math.max(1, deadline - Date.now()));
      if (predicate(frame)) return frame;
    }
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

/** Acknowledge everything pending for these clients so a run starts from an empty mailbox. */
async function drain(clients: MailboxClient[]): Promise<number> {
  let dropped = 0;
  for (const c of clients) {
    for (;;) {
      const page = await c.inbox(0);
      if (page.status !== 200) throw new Error(`drain inbox failed: ${page.status} ${JSON.stringify(page.body)}`);
      if (page.body.messages.length === 0) break;
      dropped += (await c.ack(page.body.cursor)).body.acked;
    }
  }
  return dropped;
}

// ---- smoke ---------------------------------------------------------------------------------

async function smoke(url: string): Promise<boolean> {
  const ids = loadIdentities();
  const clients = ids.users.map((u) => new MailboxClient(url, u));
  const [a, b, c] = clients;
  const results: Array<{ check: string; ok: boolean; detail?: string }> = [];
  const check = async (name: string, fn: () => Promise<boolean | string>) => {
    try {
      const outcome = await fn();
      results.push({ check: name, ok: outcome === true, detail: outcome === true ? undefined : String(outcome) });
    } catch (e) {
      results.push({ check: name, ok: false, detail: (e as Error).message });
    }
    const last = results.at(-1)!;
    console.log(`${last.ok ? "PASS" : "FAIL"}  ${name}${last.detail ? `: ${last.detail}` : ""}`);
  };

  await drain(clients);

  await check("health answers without auth", async () => (await fetch(`${url}/v1/health`)).status === 200);
  await check("anonymous inbox is refused (401)", async () => (await fetch(`${url}/v1/inbox`)).status === 401);
  await check("outsider identity is refused (403)", async () => (await new MailboxClient(url, ids.outsider).inbox()).status === 403);
  await check("replayed auth is refused", async () => {
    const header = await authHeader(a.me.sk, "GET", `${url}/v1/inbox`);
    const first = await fetch(`${url}/v1/inbox`, { headers: { Authorization: header } });
    const second = await fetch(`${url}/v1/inbox`, { headers: { Authorization: header } });
    return first.status === 200 && second.status === 401 ? true : `${first.status} then ${second.status}`;
  });

  const direct = { to: b.me.pk, id: newId(), envelope: wrapDm(a.me, b.me.pk, "smoke direct") };
  let directSeq = 0;
  await check("direct wrap is stored", async () => {
    const r = (await a.send([direct])).body.results[0];
    directSeq = r.seq;
    return r.status === "stored" || JSON.stringify(r);
  });
  await check("only the recipient can read and open it", async () => {
    const mine = (await b.inbox()).body.messages.find((m: any) => m.id === direct.id);
    if (!mine) return "not in recipient inbox";
    if (openDm(b.me, mine.envelope).content !== "smoke direct") return "wrong plaintext";
    try {
      openDm(c.me, mine.envelope);
      return "a third identity opened it";
    } catch {
      // expected
    }
    const leaked = (await c.inbox()).body.messages.some((m: any) => m.id === direct.id);
    return !leaked || "visible in another inbox";
  });
  await check("identical retry is a duplicate with the same seq", async () => {
    const r = (await a.send([direct])).body.results[0];
    return (r.status === "duplicate" && r.seq === directSeq) || JSON.stringify(r);
  });
  await check("new bytes under a used id are refused", async () => {
    const r = (await a.send([{ ...direct, envelope: wrapDm(a.me, b.me.pk, "changed") }])).body.results[0];
    return r.error?.code === "id_conflict" || JSON.stringify(r);
  });
  await check("fan-out to nine recipients", async () => {
    const deliveries: Delivery[] = clients.slice(1).map((r) => ({ to: r.me.pk, id: newId(), envelope: wrapDm(a.me, r.me.pk, "smoke group") }));
    const statuses = (await a.send(deliveries)).body.results.map((r: any) => r.status);
    if (!statuses.every((s: string) => s === "stored")) return statuses.join(",");
    for (const [i, r] of clients.slice(1).entries()) {
      const got = (await r.inbox()).body.messages.find((m: any) => m.id === deliveries[i].id);
      if (!got || openDm(r.me, got.envelope).content !== "smoke group") return `recipient ${i + 1} missing it`;
    }
    return true;
  });
  await check("live push and socket receipt", async () => {
    const socket = await LiveSocket.open(b);
    try {
      const d = { to: b.me.pk, id: newId(), envelope: wrapDm(a.me, b.me.pk, "smoke live") };
      const stored = (await a.send([d])).body.results[0];
      const pushed = await socket.until((f) => f.type === "message" && f.id === d.id);
      if (openDm(b.me, pushed.envelope).content !== "smoke live") return "wrong plaintext";
      socket.send({ type: "ack", through: stored.seq, ref: "smoke" });
      const acked = await socket.until((f) => f.type === "acked" && f.ref === "smoke");
      return acked.through === stored.seq || JSON.stringify(acked);
    } finally {
      socket.close();
    }
  });
  await check("keepalive ping is answered", async () => {
    const socket = await LiveSocket.open(c);
    try {
      socket.send("ping");
      return (await socket.until((f) => f === "pong")) === "pong";
    } finally {
      socket.close();
    }
  });
  await check("receipts empty every mailbox", async () => {
    await drain(clients);
    for (const r of clients) if ((await r.inbox()).body.messages.length) return "messages left";
    return true;
  });

  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n${results.length - failed}/${results.length} checks passed (${targetLabel(url)})`);
  return failed === 0;
}

// ---- load ----------------------------------------------------------------------------------

interface Track {
  sentAt: number;
  storedAt?: number;
  pushedAt?: number;
  receiptAt?: number;
  seq?: number;
  to: number;
}

/**
 * One assumed pilot day, compressed in time: ten users each send 100 messages (an assumption, not
 * measured usage). 70% go to one random contact, 30% to a group of all nine others. Messages go out
 * in concurrent bursts. Every recipient is connected and acknowledges each push after a simulated
 * local commit, as the app would.
 */
async function load(url: string, opts: { messages: number; burst: number; pauseMs: number }): Promise<void> {
  const ids = loadIdentities();
  const clients = ids.users.map((u) => new MailboxClient(url, u));
  await drain(clients);

  const tracks = new Map<string, Track>();
  const bySeq = new Map<string, string>(); // `${recipient}:${seq}` -> delivery key
  const envelopeBytes: number[] = [];
  const sendRows: Array<{ read: number; written: number; deliveries: number }> = [];
  const counters = { httpSends: 0, deliveries: 0, socketUpgrades: 0, socketFramesIn: 0, pushes: 0, rejected: 0 };

  const sockets = await Promise.all(clients.map((c) => LiveSocket.open(c)));
  counters.socketUpgrades = sockets.length;
  sockets.forEach((socket, r) => {
    socket.onFrame = (frame) => {
      if (frame?.type === "message") {
        counters.pushes++;
        const key = bySeq.get(`${r}:${frame.seq}`) ?? `${r}:${frame.id}`;
        const track = tracks.get(key);
        if (track) {
          track.pushedAt = Date.now();
          track.seq = frame.seq;
        }
        // Local commit would happen here; then the cumulative receipt.
        socket.send({ type: "ack", through: frame.seq });
        counters.socketFramesIn++;
      } else if (frame?.type === "acked") {
        const now = Date.now();
        for (const track of tracks.values()) {
          if (track.to === r && track.seq !== undefined && track.seq <= frame.through && track.receiptAt === undefined) track.receiptAt = now;
        }
      }
    };
  });

  const started = Date.now();
  const plan: Array<{ from: number; group: boolean; to: number }> = [];
  for (let i = 0; i < opts.messages; i++) {
    const from = i % USERS;
    const group = Math.random() < 0.3;
    let to = Math.floor(Math.random() * (USERS - 1));
    if (to >= from) to++;
    plan.push({ from, group, to });
  }

  // Wrapping is CPU-bound and synchronous; doing it before the clock starts keeps one burst's
  // encryption work from being counted as another message's delivery time.
  const prepare = (m: { from: number; group: boolean; to: number }) => {
    const sender = clients[m.from];
    const targets = m.group ? clients.map((_, i) => i).filter((i) => i !== m.from) : [m.to];
    const text = "x".repeat(20 + Math.floor(Math.random() * 280));
    return { sender, deliveries: targets.map((t) => ({ to: clients[t].me.pk, id: newId(), envelope: wrapDm(sender.me, clients[t].me.pk, text), t })) };
  };

  const sendOne = async ({ sender, deliveries }: ReturnType<typeof prepare>) => {
    const sentAt = Date.now();
    for (const d of deliveries) {
      tracks.set(`${d.t}:${d.id}`, { sentAt, to: d.t });
      envelopeBytes.push(Buffer.byteLength(d.envelope));
    }
    const reply = await sender.send(deliveries.map(({ t: _t, ...d }) => d));
    counters.httpSends++;
    counters.deliveries += deliveries.length;
    const storedAt = Date.now();
    const r = rows(reply.headers);
    if (r) sendRows.push({ ...r, deliveries: deliveries.length });
    if (reply.status !== 200) {
      counters.rejected += deliveries.length;
      return;
    }
    reply.body.results.forEach((res: any, i: number) => {
      const d = deliveries[i];
      const key = `${d.t}:${d.id}`;
      const track = tracks.get(key)!;
      if (res.status === "stored" || res.status === "duplicate") {
        track.storedAt = storedAt;
        track.seq ??= res.seq;
        bySeq.set(`${d.t}:${res.seq}`, key);
      } else {
        counters.rejected++;
        tracks.delete(key);
      }
    });
  };

  for (let i = 0; i < plan.length; i += opts.burst) {
    const burst = plan.slice(i, i + opts.burst).map(prepare);
    await Promise.all(burst.map(sendOne));
    await sleep(opts.pauseMs);
  }
  // Let the last pushes and receipts land.
  const settleUntil = Date.now() + 15000;
  while (Date.now() < settleUntil && [...tracks.values()].some((t) => t.receiptAt === undefined)) await sleep(100);
  const finished = Date.now();

  // Sample the HTTP catch-up and receipt paths once per user for their SQL row cost.
  const inboxRows: Array<{ read: number; written: number }> = [];
  const ackRows: Array<{ read: number; written: number }> = [];
  for (const c of clients) {
    const page = await c.inbox(0);
    const ri = rows(page.headers);
    if (ri) inboxRows.push(ri);
    const ack = await c.ack(page.body.cursor ?? 0);
    const ra = rows(ack.headers);
    if (ra) ackRows.push(ra);
  }
  sockets.forEach((s) => s.close());

  const all = [...tracks.values()];
  const report = {
    generated: new Date().toISOString(),
    target: targetLabel(url),
    workload: {
      assumption: "10 users x 100 messages/day (assumed, not measured); 70% direct, 30% to a 9-member group; compressed in time",
      userMessages: opts.messages,
      burst: opts.burst,
      pauseMs: opts.pauseMs,
      wallClockMs: finished - started,
    },
    counters,
    latencyMs: {
      stored: stats(all.filter((t) => t.storedAt).map((t) => t.storedAt! - t.sentAt)),
      pushed: stats(all.filter((t) => t.pushedAt).map((t) => t.pushedAt! - t.sentAt)),
      durableReceipt: stats(all.filter((t) => t.receiptAt).map((t) => t.receiptAt! - t.sentAt)),
    },
    undelivered: all.filter((t) => t.receiptAt === undefined).length,
    envelopeBytes: { ...stats(envelopeBytes), avg: Math.round(envelopeBytes.reduce((a, b) => a + b, 0) / Math.max(1, envelopeBytes.length)) },
    sqlRows: {
      perSendRequest: sendRows.length ? {
        readAvg: +(sendRows.reduce((a, r) => a + r.read, 0) / sendRows.length).toFixed(1),
        writtenAvg: +(sendRows.reduce((a, r) => a + r.written, 0) / sendRows.length).toFixed(1),
        writtenPerDelivery: +(sendRows.reduce((a, r) => a + r.written, 0) / sendRows.reduce((a, r) => a + r.deliveries, 0)).toFixed(2),
      } : "not exposed (EXPOSE_METRICS=0)",
      perInboxRead: inboxRows.length ? { readAvg: +(inboxRows.reduce((a, r) => a + r.read, 0) / inboxRows.length).toFixed(1) } : null,
      perHttpAck: ackRows.length ? { writtenAvg: +(ackRows.reduce((a, r) => a + r.written, 0) / ackRows.length).toFixed(1) } : null,
      totalWrittenBySends: sendRows.reduce((a, r) => a + r.written, 0),
    },
  };
  mkdirSync("reports", { recursive: true });
  const file = join("reports", `load-${report.target}-${report.generated.replace(/[:.]/g, "-")}.json`);
  writeFileSync(file, JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report, null, 2));
  console.log(`\nwritten to ${file}`);
}

// ---- probe ---------------------------------------------------------------------------------

/** One full message path, recorded whether it passes or fails, for the availability record. */
async function probe(url: string): Promise<boolean> {
  const ids = loadIdentities();
  const a = new MailboxClient(url, ids.users[0]);
  const b = new MailboxClient(url, ids.users[1]);
  const record: Record<string, unknown> = { ts: new Date().toISOString(), target: targetLabel(url), ok: false, stage: "connect" };
  const t0 = Date.now();
  let socket: LiveSocket | null = null;
  try {
    socket = await LiveSocket.open(b);
    record.connectMs = Date.now() - t0;
    record.stage = "send";
    const d = { to: b.me.pk, id: newId(), envelope: wrapDm(a.me, b.me.pk, `probe ${record.ts}`) };
    const t1 = Date.now();
    const reply = await a.send([d]);
    const result = reply.body?.results?.[0];
    if (reply.status !== 200 || result?.status !== "stored") throw new Error(`send: ${reply.status} ${JSON.stringify(reply.body)}`);
    record.storedMs = Date.now() - t1;
    record.stage = "push";
    const pushed = await socket.until((f) => f.type === "message" && f.id === d.id, 15000);
    record.pushedMs = Date.now() - t1;
    if (openDm(b.me, pushed.envelope).content !== `probe ${record.ts}`) throw new Error("push: wrong plaintext");
    record.stage = "receipt";
    // Also clears anything an earlier failed probe left behind.
    socket.send({ type: "ack", through: pushed.seq, ref: "probe" });
    await socket.until((f) => f.type === "acked" && f.ref === "probe", 15000);
    record.receiptMs = Date.now() - t1;
    record.stage = "done";
    record.ok = true;
  } catch (e) {
    record.error = (e as Error).message.slice(0, 200);
  } finally {
    socket?.close();
  }
  mkdirSync(STATE_DIR, { recursive: true });
  appendFileSync(join(STATE_DIR, "probe.jsonl"), JSON.stringify(record) + "\n");
  console.log(JSON.stringify(record));
  return record.ok === true;
}

// ---- hibernate -----------------------------------------------------------------------------

/**
 * Opens a socket, stays silent long enough for Cloudflare to hibernate the object, then sends.
 * A changed instance id plus a push on the old socket shows the session was rebuilt from storage.
 */
async function hibernate(url: string, idleS: number): Promise<boolean> {
  const ids = loadIdentities();
  const a = new MailboxClient(url, ids.users[0]);
  const b = new MailboxClient(url, ids.users[2]);
  const socket = await LiveSocket.open(b);
  const before = socket.instance;
  console.log(`socket open (instance ${before.slice(0, 8)}); idling ${idleS}s with no traffic...`);
  await sleep(idleS * 1000);
  const d = { to: b.me.pk, id: newId(), envelope: wrapDm(a.me, b.me.pk, "after idle") };
  const reply = await a.send([d]);
  const after = reply.headers.get("x-relay-instance") ?? "";
  let pushed = false;
  try {
    const frame = await socket.until((f) => f.type === "message" && f.id === d.id, 15000);
    pushed = openDm(b.me, frame.envelope).content === "after idle";
    socket.send({ type: "ack", through: frame.seq, ref: "h" });
    await socket.until((f) => f.type === "acked" && f.ref === "h", 15000);
  } catch (e) {
    console.log(`no push: ${(e as Error).message}`);
  }
  socket.close();
  const record = {
    ts: new Date().toISOString(),
    target: targetLabel(url),
    idleS,
    instanceChanged: before !== after,
    pushedToIdleSocket: pushed,
  };
  mkdirSync(STATE_DIR, { recursive: true });
  appendFileSync(join(STATE_DIR, "hibernation.jsonl"), JSON.stringify(record) + "\n");
  console.log(JSON.stringify(record));
  return pushed;
}

// ---- uptime --------------------------------------------------------------------------------

/**
 * Summarises the probe log. A probe that doesn't run (machine off, no network here) leaves a gap,
 * reported separately: it says nothing about the server.
 */
function uptime(days: number): void {
  const file = join(STATE_DIR, "probe.jsonl");
  if (!existsSync(file)) throw new Error(`no probe log yet at ${file}`);
  const since = Date.now() - days * 24 * 60 * 60 * 1000;
  const runs = readFileSync(file, "utf8").split("\n").filter(Boolean)
    .map((line) => JSON.parse(line))
    .filter((r) => r.target === "remote" && Date.parse(r.ts) >= since)
    .sort((a, b) => Date.parse(a.ts) - Date.parse(b.ts));
  if (runs.length === 0) throw new Error(`no remote probes in the last ${days} days`);

  let longestOutageMin = 0;
  let outageStart: number | null = null;
  let gaps = 0;
  let gapHours = 0;
  for (const [i, run] of runs.entries()) {
    const t = Date.parse(run.ts);
    if (i > 0) {
      const sinceLast = (t - Date.parse(runs[i - 1].ts)) / 60000;
      if (sinceLast > 25) {
        gaps++;
        gapHours += sinceLast / 60;
      }
    }
    if (!run.ok && outageStart === null) outageStart = t;
    if (run.ok && outageStart !== null) {
      longestOutageMin = Math.max(longestOutageMin, (t - outageStart) / 60000);
      outageStart = null;
    }
  }
  const ongoing = outageStart !== null;
  if (ongoing) longestOutageMin = Math.max(longestOutageMin, (Date.parse(runs.at(-1)!.ts) - outageStart!) / 60000);
  const ok = runs.filter((r) => r.ok);
  console.log(JSON.stringify({
    windowDays: days,
    first: runs[0].ts,
    last: runs.at(-1)!.ts,
    probes: runs.length,
    passed: ok.length,
    availabilityPct: +((100 * ok.length) / runs.length).toFixed(2),
    longestOutageMinutes: Math.round(longestOutageMin),
    failingNow: ongoing,
    failedStages: runs.filter((r) => !r.ok).reduce((acc: Record<string, number>, r) => ({ ...acc, [r.stage]: (acc[r.stage] ?? 0) + 1 }), {}),
    receiptMs: stats(ok.map((r) => r.receiptMs)),
    gapsWithoutProbes: { count: gaps, hours: +gapHours.toFixed(1), note: "probe machine off or offline; not counted as downtime" },
  }, null, 2));
}

// ---- main ----------------------------------------------------------------------------------

const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: {
    url: { type: "string" },
    messages: { type: "string", default: "1000" },
    burst: { type: "string", default: "25" },
    pause: { type: "string", default: "100" },
    idle: { type: "string", default: "60" },
    days: { type: "string", default: "7" },
  },
});
const command = positionals[0];
const url = (values.url ?? process.env.RELAY_MAILBOX_URL ?? (existsSync(URL_FILE) ? readFileSync(URL_FILE, "utf8").trim() : "")).replace(/\/$/, "");
const needUrl = () => {
  if (!url) throw new Error(`pass --url, set RELAY_MAILBOX_URL or write the URL to ${URL_FILE}`);
  return url;
};

try {
  switch (command) {
    case "identities":
      createIdentities();
      break;
    case "allowlist":
      console.log(loadIdentities().users.map((u) => u.pk).join(","));
      break;
    case "smoke":
      process.exitCode = (await smoke(needUrl())) ? 0 : 1;
      break;
    case "load":
      await load(needUrl(), { messages: Number(values.messages), burst: Number(values.burst), pauseMs: Number(values.pause) });
      break;
    case "probe":
      process.exitCode = (await probe(needUrl())) ? 0 : 1;
      break;
    case "hibernate":
      process.exitCode = (await hibernate(needUrl(), Number(values.idle))) ? 0 : 1;
      break;
    case "uptime":
      uptime(Number(values.days));
      break;
    default:
      console.log("usage: node tools/pilot.ts identities|allowlist|smoke|load|probe|hibernate|uptime [--url URL]");
      process.exitCode = 2;
  }
} catch (e) {
  console.error((e as Error).message);
  process.exitCode = 1;
}
