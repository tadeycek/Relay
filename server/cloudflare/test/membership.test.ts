import { describe, expect, it } from "vitest";
import { newIdentity, MailboxClient, wrapDm } from "../client/mailbox-client";
import { BASE, client, newId, sendDm, user, worker } from "./helpers";

/** Someone the founders' allowlist doesn't know: a fresh key, like a new phone. */
function newcomer() {
  const me = newIdentity();
  return new MailboxClient(BASE, me, worker);
}

const code = () => crypto.randomUUID().replaceAll("-", "");
const soon = () => Date.now() + 30 * 60 * 1000;

describe("joining by QR pairing", () => {
  it("lets a member vouch for the newcomer whose QR they scanned", async () => {
    const carol = newcomer();
    expect((await carol.inbox()).status).toBe(403);
    const pair = await client(0).pair(carol.me.pk, code());
    expect(pair.body).toEqual({ v: 1, you: true, peer: true });
    expect((await carol.inbox()).status).toBe(200);
    // And she is reachable straight away: the pairing request can follow.
    expect((await sendDm(0, 0, "warm-up")).result).toBeDefined();
    const toCarol = await client(0).send([{ to: carol.me.pk, id: newId(), envelope: wrapDm(user(0), carol.me.pk, "welcome") }]);
    expect(toCarol.body.results[0].status).toBe("stored");
  });

  it("lets a newcomer join with the one-time code from a member's QR", async () => {
    const carol = newcomer();
    const shown = code();
    expect((await client(1).invite(shown, soon())).status).toBe(200);
    const pair = await carol.pair(user(1).pk, shown);
    expect(pair.body).toEqual({ v: 1, you: true, peer: true });
    expect((await carol.inbox()).status).toBe(200);
  });

  it("uses each invite code once", async () => {
    const shown = code();
    await client(1).invite(shown, soon());
    expect((await newcomer().pair(user(1).pk, shown)).body.you).toBe(true);
    expect((await newcomer().pair(user(1).pk, shown)).body.you).toBe(false);
  });

  it("refuses a wrong, expired or foreign code", async () => {
    const shown = code();
    await client(1).invite(shown, soon());
    expect((await newcomer().pair(user(1).pk, code())).body.you).toBe(false);
    // The code is only valid together with the member who showed it.
    expect((await newcomer().pair(user(2).pk, shown)).body.you).toBe(false);
    const expired = code();
    await client(1).invite(expired, Date.now() - 1);
    expect((await newcomer().pair(user(1).pk, expired)).body.you).toBe(false);
  });

  it("doesn't let a newcomer vouch, invite or register codes", async () => {
    const carol = newcomer();
    const dave = newcomer();
    expect((await carol.pair(dave.me.pk, code())).body).toEqual({ v: 1, you: false, peer: false });
    expect((await dave.inbox()).status).toBe(403);
    expect((await carol.invite(code(), soon())).status).toBe(403);
  });

  it("gives an admitted newcomer the full service, photos included", async () => {
    const carol = newcomer();
    await client(0).pair(carol.me.pk, code());
    const put = await carol.putMedia(new Uint8Array([1, 2, 3, 4]), [user(0).pk]);
    expect(put.status).toBe(200);
    expect((await client(0).getMedia(put.hash)).status).toBe(200);
    const toCarol = await client(0).putMedia(new Uint8Array([5, 6, 7]), [carol.me.pk]);
    expect(toCarol.status).toBe(200);
  });

  it("still refuses photos from and to people who never joined", async () => {
    const stranger = newcomer();
    expect((await stranger.putMedia(new Uint8Array([1]), [user(0).pk])).status).toBe(403);
    expect((await client(0).putMedia(new Uint8Array([2]), [stranger.me.pk])).status).toBe(400);
  });
});
