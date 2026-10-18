import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import type { Mailbox } from "../src/mailbox";
import { OUTSIDER } from "./identities";
import { connect, mailboxStub, user } from "./helpers";

const rowsWritten = () => runInDurableObject(mailboxStub(), async (instance: Mailbox) => (instance as any).rows.written as number);

describe("live signals (typing, presence)", () => {
  it("passes a signal from socket to socket without storing anything", async () => {
    const alice = await connect(0);
    const bob = await connect(1);
    const carol = await connect(2);
    const before = await rowsWritten();
    alice.send({ type: "signal", to: user(1).pk, envelope: "typing-wrap" });
    expect(await bob.next()).toEqual({ type: "signal", from: user(0).pk, envelope: "typing-wrap" });
    expect(await carol.silent()).toBe(true);
    expect(await rowsWritten()).toBe(before);
    for (const s of [alice, bob, carol]) s.close();
  });

  it("drops a signal for someone who isn't connected", async () => {
    const alice = await connect(0);
    alice.send({ type: "signal", to: user(1).pk, envelope: "presence-wrap" });
    expect(await alice.silent()).toBe(true);
    // Connecting later doesn't replay it: signals are never queued.
    const bob = await connect(1);
    expect(await bob.silent()).toBe(true);
    alice.close();
    bob.close();
  });

  it("refuses recipients outside the pilot and oversized signals", async () => {
    const alice = await connect(0);
    alice.send({ type: "signal", to: user(OUTSIDER).pk, envelope: "x", ref: "r1" });
    expect(await alice.next()).toMatchObject({ type: "error", ref: "r1", error: { code: "unknown_recipient" } });
    alice.send({ type: "signal", to: user(1).pk, envelope: "x".repeat(16 * 1024 + 1), ref: "r2" });
    expect(await alice.next()).toMatchObject({ type: "error", ref: "r2", error: { code: "envelope_too_large" } });
    alice.close();
  });

  it("rate-limits a socket that floods signals", async () => {
    const alice = await connect(0);
    const bob = await connect(1);
    for (let i = 0; i < 6; i++) alice.send({ type: "signal", to: user(1).pk, envelope: `s${i}`, ref: `f${i}` });
    for (let i = 0; i < 5; i++) expect(await bob.next()).toMatchObject({ envelope: `s${i}` });
    expect(await alice.next()).toMatchObject({ type: "error", ref: "f5", error: { code: "rate_limited" } });
    alice.close();
    bob.close();
  });
});
