import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("credentials are never persisted in browser storage", async () => {
  const source = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.doesNotMatch(source, /tpv-saas-credentials/);
  assert.doesNotMatch(source, /sessionStorage\.(?:getItem|setItem)\([^)]*credential/i);
  assert.doesNotMatch(source, /localStorage\.(?:getItem|setItem)\([^)]*credential/i);
});

test("language is the only intentionally persisted SaaS preference", async () => {
  const source = await readFile(new URL("../src/App.tsx", import.meta.url), "utf8");

  assert.match(source, /localStorage\.setItem\("tpv-saas-language"/);
});

test("authenticated requests use an opaque bearer token instead of the password", async () => {
  const source = await readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8");

  assert.match(source, /Bearer \$\{credentials\.accessToken\}/);
  assert.doesNotMatch(source, /btoa/);
});
