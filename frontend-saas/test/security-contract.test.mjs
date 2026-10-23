import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { readFrontendSources } from "../test-support/source-helpers.mjs";

test("credentials are never persisted in browser storage", async () => {
  const source = await readFrontendSources();

  assert.doesNotMatch(source, /tpv-saas-credentials/);
  assert.doesNotMatch(source, /sessionStorage\.(?:getItem|setItem)\([^)]*credential/i);
  assert.doesNotMatch(source, /localStorage\.(?:getItem|setItem)\([^)]*credential/i);
});

test("language is the only intentionally persisted SaaS preference", async () => {
  const source = await readFrontendSources();

  assert.match(source, /localStorage\.setItem\("tpv-saas-language"/);
});

test("authenticated requests use an opaque bearer token instead of the password", async () => {
  const source = await readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8");

  assert.match(source, /Bearer \$\{credentials\.accessToken\}/);
  assert.doesNotMatch(source, /btoa/);
});

test("all API transports have a bounded abortable timeout", async () => {
  const source = await readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8");
  const deadline = await readFile(new URL("../src/lib/request-deadline.mjs", import.meta.url), "utf8");

  assert.match(source, /const REQUEST_TIMEOUT_MS = 20_000/);
  assert.match(deadline, /new AbortController\(\)/);
  assert.match(source, /withinRequestDeadline/);
  assert.equal((source.match(/fetchWithTimeout\(`/g) ?? []).length, 6);
  assert.equal((source.match(/\bfetch\(/g) ?? []).length, 1);
});
