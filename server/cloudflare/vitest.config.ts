import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";
import { ALLOWED, testPubkeys } from "./test/identities";

export default defineConfig({
  test: {
    setupFiles: ["./test/setup.ts"],
    // Files share the single mailbox object, so they run one after another.
    fileParallelism: false,
  },
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        // Small caps so the limit tests stay fast; the defaults are checked separately.
        bindings: {
          ALLOWED_PUBKEYS: testPubkeys(ALLOWED).join(","),
          EXPOSE_METRICS: "1",
          MAX_ENVELOPE_BYTES: "8192",
          MAX_PENDING_BYTES_PER_RECIPIENT: "24576",
          MAX_PENDING_BYTES_TOTAL: "49152",
          MAX_PAGE_BYTES: "12288",
          MAX_PAGE_MESSAGES: "20",
          // Small media limits so chunking and the size cap are exercised without huge buffers.
          MAX_MEDIA_BYTES: "65536",
          MEDIA_CHUNK_BYTES: "16384",
          MAX_MEDIA_RECIPIENTS: "4",
          SIGNALS_PER_MINUTE: "5",
        },
      },
    }),
  ],
});
