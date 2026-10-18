import { reset } from "cloudflare:test";
import { afterEach } from "vitest";

// Every test starts with an empty mailbox; all files share the one "pilot" object.
afterEach(async () => {
  await reset();
});
