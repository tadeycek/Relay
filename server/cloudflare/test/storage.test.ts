import { evictDurableObject, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { openDm, wrapDm } from "../client/mailbox-client";
import type { Mailbox } from "../src/mailbox";
import { client, mailboxStub, newId, sendDm, user } from "./helpers";

const writes = (headers: Headers) => Number(/written=(\d+)/.exec(headers.get("x-relay-sql-rows") ?? "")?.[1]);

/**
 * Rewinds the live object's storage to the v1 layout, keeping its data, as an object deployed before
 * v2 would be. v1 kept acknowledged messages as dedup records, so one is added for [acknowledged].
 */
async function downgradeToV1(acknowledged: { recipient: string; sender: string; id: string }): Promise<void> {
  await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
    const sql = state.storage.sql;
    state.storage.transactionSync(() => {
      sql.exec("ALTER TABLE deliveries ADD COLUMN delivered_at INTEGER");
      sql.exec("CREATE INDEX deliveries_delivered ON deliveries (delivered_at) WHERE delivered_at IS NOT NULL");
      sql.exec(
        "INSERT INTO deliveries (recipient, sender, client_id, envelope_hash, envelope, size, stored_at, delivered_at) VALUES (?, ?, ?, 'h', NULL, 10, 1, 2)",
        acknowledged.recipient, acknowledged.sender, acknowledged.id,
      );
      sql.exec("DROP INDEX IF EXISTS deliveries_recipient");
      sql.exec("CREATE INDEX deliveries_pending ON deliveries (recipient, seq) WHERE envelope IS NOT NULL");
      sql.exec("DROP TABLE auth_seen");
      sql.exec("CREATE TABLE auth_seen (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)");
      sql.exec("CREATE INDEX auth_seen_expiry ON auth_seen (expires_at)");
      const counters = sql.exec<{ scope: string; bytes: number }>("SELECT scope, bytes FROM pending_bytes").toArray();
      sql.exec("DROP TABLE pending_bytes");
      sql.exec("CREATE TABLE pending_bytes (scope TEXT PRIMARY KEY, bytes INTEGER NOT NULL)");
      for (const c of counters) sql.exec("INSERT INTO pending_bytes (scope, bytes) VALUES (?, ?)", c.scope, c.bytes);
      sql.exec("INSERT INTO pending_bytes (scope, bytes) VALUES ('*', ?)", counters.reduce((a, c) => a + c.bytes, 0));
      sql.exec("UPDATE meta SET value = '1' WHERE key = 'schema'");
    });
  });
}

describe("schema migration", () => {
  it("upgrades a v1 mailbox in place without losing or reordering pending messages", async () => {
    const first = await sendDm(0, 1, "before migration");
    const second = await sendDm(2, 1, "also before");
    await downgradeToV1({ recipient: user(1).pk, sender: user(0).pk, id: "acknowledged-in-v1" });
    await evictDurableObject(mailboxStub()); // the next request constructs the object, which migrates

    const { messages } = (await client(1).inbox()).body;
    expect(messages.map((m: any) => m.seq)).toEqual([first.result.seq, second.result.seq]);
    expect(openDm(user(1), messages[0].envelope).content).toBe("before migration");

    const layout = await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => ({
      schema: state.storage.sql.exec("SELECT value FROM meta WHERE key = 'schema'").one().value,
      rows: state.storage.sql.exec("SELECT COUNT(*) AS n FROM deliveries").one().n,
      deliveryColumns: state.storage.sql.exec("SELECT name FROM pragma_table_info('deliveries')").toArray().map((c: any) => c.name),
      counterColumns: state.storage.sql.exec("SELECT name FROM pragma_table_info('pending_bytes')").toArray().map((c: any) => c.name),
      totalRow: state.storage.sql.exec("SELECT COUNT(*) AS n FROM pending_bytes WHERE scope = '*'").one().n,
      tables: state.storage.sql.exec("SELECT name, sql FROM sqlite_master WHERE type IN ('table', 'index') AND name NOT LIKE 'sqlite_%'").toArray(),
    }));
    expect(layout.schema).toBe("5");
    // The v1 dedup record is gone; only the two waiting messages remain.
    expect(layout.rows).toBe(2);
    expect(layout.deliveryColumns).not.toContain("delivered_at");
    expect(layout.counterColumns).toEqual(["scope", "bytes"]);
    expect(layout.totalRow).toBe(0);
    const ddl = Object.fromEntries(layout.tables.map((t: any) => [t.name, String(t.sql)]));
    expect(ddl.auth_seen).toMatch(/WITHOUT ROWID/);
    expect(ddl.pending_bytes).toMatch(/WITHOUT ROWID/);
    expect(ddl).toHaveProperty("deliveries_recipient");
    expect(ddl).not.toHaveProperty("deliveries_pending");
    expect(ddl).not.toHaveProperty("auth_seen_expiry");
    expect(ddl).not.toHaveProperty("deliveries_delivered");
    expect(ddl.members).toMatch(/WITHOUT ROWID/);
    expect(ddl.invites).toMatch(/WITHOUT ROWID/);

    // Sequence numbers keep growing after the migration, so client cursors stay valid.
    const third = await sendDm(0, 1, "after migration");
    expect(third.result.seq).toBeGreaterThan(second.result.seq);
    expect((await client(1).ack(third.result.seq)).body.acked).toBe(3);
    // Sequence numbers never go back, even once every row has been deleted.
    expect((await sendDm(0, 1, "after empty")).result.seq).toBeGreaterThan(third.result.seq);
    await client(1).ack(Number.MAX_SAFE_INTEGER);
    expect((await client(1).inbox()).body.messages).toHaveLength(0);
  });
});

describe("rows written per operation (Workers Free allows 100,000 a day)", () => {
  it("stays within budget for a direct send, a fan-out and a receipt", async () => {
    const direct = await sendDm(0, 1, "budget");
    const fanOut = await client(0).send([2, 3, 4, 5, 6, 7, 8, 9, 10].map((r) => ({ to: user(r).pk, id: newId(), envelope: wrapDm(user(0), user(r).pk, "budget") })));
    const ack = await client(1).ack(direct.result.seq);
    const costs = { direct: writes(direct.reply.headers), fanOutPerDelivery: writes(fanOut.headers) / 9, receipt: writes(ack.headers) };
    console.log("rows written:", JSON.stringify(costs));
    expect(costs.direct).toBeLessThanOrEqual(7);
    expect(costs.fanOutPerDelivery).toBeLessThanOrEqual(6);
    // Deleting the row and its two index entries, the counter update, and this request's auth record.
    expect(costs.receipt).toBeLessThanOrEqual(5);
  });
});

const reads = (headers: Headers) => Number(/read=(\d+)/.exec(headers.get("x-relay-sql-rows") ?? "")?.[1]);

describe("rows read by catch-up", () => {
  it("skips everything already acknowledged, even from cursor 0", async () => {
    // 30 delivered and acknowledged messages stay on record for sender dedup.
    for (let i = 0; i < 30; i++) await client(1).ack((await sendDm(0, 1, `old ${i}`)).result.seq);
    const fresh = await sendDm(0, 1, "new");
    const page = await client(1).inbox(0);
    expect(page.body.messages.map((m: any) => m.seq)).toEqual([fresh.result.seq]);
    // A handful of rows (state lookup, auth record, the one message), not the 30 retained records.
    expect(reads(page.headers)).toBeLessThanOrEqual(6);
  });

  it("keeps later messages visible when a receipt names a seq beyond anything stored", async () => {
    const first = await sendDm(0, 1, "one");
    await client(1).ack(Number.MAX_SAFE_INTEGER);
    const later = await sendDm(0, 1, "two");
    expect(later.result.seq).toBeGreaterThan(first.result.seq);
    expect((await client(1).inbox(0)).body.messages.map((m: any) => m.seq)).toEqual([later.result.seq]);
  });
});

describe("delete on receipt", () => {
  it("keeps nothing about a message once the recipient confirms it", async () => {
    const a = await sendDm(0, 1, "one");
    const b = await sendDm(2, 1, "two");
    await client(1).ack(b.result.seq);
    const left = await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => ({
      rows: state.storage.sql.exec("SELECT COUNT(*) AS n FROM deliveries").one().n,
      bytes: state.storage.sql.exec("SELECT COALESCE(SUM(bytes), 0) AS n FROM pending_bytes").one().n,
    }));
    expect(left).toEqual({ rows: 0, bytes: 0 });
    expect(a.result.seq).toBeLessThan(b.result.seq);
  });
});

describe("pending lifetime", () => {
  it("drops messages nobody collected within the lifetime and frees their space", async () => {
    const old = await sendDm(0, 1, "never collected");
    await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
      const target = instance as any;
      target.limits = { ...target.limits, pendingLifetimeMs: 1 };
      target.lastPruneAt = 0;
    });
    await new Promise((r) => setTimeout(r, 5));
    const fresh = await sendDm(2, 3, "triggers cleanup");
    expect((await client(1).inbox()).body.messages).toHaveLength(0);
    const bytes = await runInDurableObject(mailboxStub(), async (_: Mailbox, state) =>
      state.storage.sql.exec("SELECT bytes FROM pending_bytes WHERE scope = ?", user(1).pk).one().bytes);
    expect(bytes).toBe(0);
    expect(old.result.status).toBe("stored");
    expect(fresh.result.status).toBe("stored");
  });
});
